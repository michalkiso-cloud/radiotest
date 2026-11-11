package com.michal.radiotest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.exoplayer.ExoPlayer
import android.media.AudioManager
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.collect.ImmutableList
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import android.net.Uri
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import java.time.LocalDate
import java.security.SecureRandom
import java.util.Locale

const val KOMENDA_GRAJ = "graj"          // argument "id" = id stacji (tryb ręczny)
const val KOMENDA_LOSUJ = "losuj"        // gra stację z losowania (dzienny albo auto)
const val KOMENDA_STOP = "stop"          // przycisk Stop w powiadomieniu
private const val ID_KORZEN = "korzen"
private const val ID_LOSUJ = "losuj"
private const val TIK_S = 5
private val WZNOW_ODSTEPY_S = longArrayOf(2, 5, 15, 60, 120)
// przełączanie na RMF24 (jak w skrypcie)
private const val COFNIJ_BLOKOW = 1
private const val KONIEC_MOWY_S = 20f

/** Stan losowania, wspólny dla usługi i ekranu (SharedPreferences). */
object StanLosowania {
    fun prefs(c: Context) = c.getSharedPreferences("losowanie_stan", Context.MODE_PRIVATE)
    fun trybAuto(c: Context) = prefs(c).getBoolean("auto", false)
    fun ustawAuto(c: Context, auto: Boolean) = prefs(c).edit().putBoolean("auto", auto).apply()
    fun autoSek(c: Context) = prefs(c).getInt("auto_sek", 0)
    /** Tryb auto: zmiana stacji po tylu minutach. */
    fun autoMin(c: Context) = prefs(c).getInt("auto_min", 60)
    fun ustawAutoMin(c: Context, m: Int) = prefs(c).edit().putInt("auto_min", m.coerceIn(1, 1440)).apply()
    /** true = czas względny (nie liczą się minuty RMF24), false = bezwzględny (liczy się też RMF24). */
    fun wzgledny(c: Context) = prefs(c).getBoolean("wzgledny", true)
    fun ustawWzgledny(c: Context, w: Boolean) = prefs(c).edit().putBoolean("wzgledny", w).apply()
    fun losujeTeraz(c: Context) = prefs(c).getBoolean("aktywne", false)
}

/** Odtwarzanie w tle: powiadomienie i sterowanie z ekranu blokady robi Media3. */
class RadioService : MediaLibraryService() {
    private var sesja: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private var obecna: Stacja? = null
    /** Nowa stacja ładowana w tle do przenikania. */
    private var oczekujacy: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val los = SecureRandom()
    private var proby = 0
    private var ostatniZapis = 0L

    private val prefs get() = StanLosowania.prefs(this)
    private val atrybuty = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    // Spotify: gdy gra, ExoPlayer odtwarza ciszę (bez przejmowania fokusu dźwięku),
    // a pauza/stop z aplikacji i powiadomienia są przekazywane do Spotify.
    private var trybSpotify = false
    private var spotify: SpotifyAppRemote? = null
    private var spotifyUtwor = ""

    // Etap 3: nasłuch RMF24, YAMNet i przełączanie na RMF24 w czasie mowy
    private val nasluch by lazy { Nasluch(this) { naBlok(it) } }
    private val rmf by lazy { OdtwarzaczRmf(nasluch).also { it.naCel = { wrocZRmf() } } }
    private var trybRmf = false
    private var powrotUstawiony = false
    private val bloki = HashMap<Int, Blok>()
    private var ostatniWerdykt = "?"
    private var glosnoscGlowna = 1f

    private fun nasluchStart() {
        if (!UstMowy.wlaczone(this)) { nasluch.stop(); return }
        if (!nasluch.dziala) { bloki.clear(); ostatniWerdykt = "?" }
        nasluch.start()
    }

    private fun naBlok(b: Blok) {
        bloki[b.nr] = b
        bloki.keys.filter { it < b.nr - 15 }.forEach { bloki.remove(it) }
        val poprzedni = ostatniWerdykt
        ostatniWerdykt = b.werdykt
        if (!player.playWhenReady || player.mediaItemCount == 0) return
        if (b.werdykt == "MOWA" && poprzedni != "MOWA" && !trybRmf) wejdzNaRmf(b.seriaStart)
        else if (b.werdykt == "MUZYKA" && poprzedni != "MUZYKA" && trybRmf) ustawPowrot(b.seriaStart)
    }

    private fun sr() = nasluch.srBufora

    private fun wejdzNaRmf(ks: Int) {
        val kp = (ks - COFNIJ_BLOKOW).let { if (bloki.containsKey(it)) it else ks }
        val bazowy = bloki[kp] ?: bloki[ks] ?: return
        var start = bazowy.ramkaStart
        for (kk in kp..ks) {
            val b = bloki[kk] ?: continue
            val tp = b.pierwMowaS ?: continue
            start = b.ramkaStart + ((tp - UstMowy.margPocz(this)) * sr()).toLong()
            LogMowy.dodaj(String.format(Locale.US, "Początek mowy w bloku %+d: %.1f s", kk - ks, tp))
            break
        }
        trybRmf = true
        powrotUstawiony = false
        LogMowy.graRmf.value = true
        odswiezOpis()
        rmf.start(start)
        LogMowy.dodaj(String.format(Locale.US, "→ RMF24 (opóźnienie %.0f s)", rmf.opoznienieS()))
        if (trybSpotify) spotify?.playerApi?.pause()
        glosnoscGlowna = if (trybSpotify) 0f else player.volume
        przejscie { t ->
            rmf.ustawGlosnosc(t)
            if (!trybSpotify) player.volume = glosnoscGlowna * (1 - t)
        }
    }

    private fun ustawPowrot(ks: Int) {
        val b = bloki[ks] ?: return
        val ost = b.ostMowaS
        val pop = bloki[ks - 1]
        val MARGINES_S = UstMowy.margKon(this)
        val koniec: Float = when {
            ost != null -> minOf(KONIEC_MOWY_S, ost + MARGINES_S)
            pop?.ostMowaS != null ->
                ((pop.ramkaStart - b.ramkaStart) / sr().toFloat()) + pop.ostMowaS + MARGINES_S
            else -> KONIEC_MOWY_S
        }
        val cel = b.ramkaStart + (koniec * sr()).toLong()
        powrotUstawiony = true
        LogMowy.dodaj(String.format(Locale.US, "Koniec mowy: powrót %+.1f s od początku bloku muzyki", koniec))
        if (rmf.pozycja >= cel) wrocZRmf() else rmf.cel = cel
    }

    private fun wrocZRmf() {
        if (!trybRmf) return
        trybRmf = false
        LogMowy.dodaj("← powrót do radia głównego")
        odswiezOpis()
        if (trybSpotify) spotify?.playerApi?.resume()
        przejscie(koniec = { rmf.stop(); LogMowy.graRmf.value = false }) { t ->
            rmf.ustawGlosnosc(1 - t)
            if (!trybSpotify) player.volume = t
        }
    }

    /** Przerywa RMF24 bez przejścia (pauza, stop, zmiana stacji). */
    private fun przerwijRmf() {
        handler.removeCallbacks(krokPrzejscia)
        rmf.stop()
        if (trybRmf && trybSpotify) spotify?.playerApi?.resume()
        val byl = trybRmf
        trybRmf = false
        LogMowy.graRmf.value = false
        if (!trybSpotify) player.volume = 1f
        if (byl) odswiezOpis()
    }

    private var krokPrzejscia = Runnable {}
    private fun przejscie(koniec: () -> Unit = {}, krok: (Float) -> Unit) {
        handler.removeCallbacks(krokPrzejscia)
        val kroki = 20
        var i = 0
        krokPrzejscia = object : Runnable {
            override fun run() {
                i++
                krok(i / kroki.toFloat())
                if (i < kroki) handler.postDelayed(this, (UstMowy.czasPrzejscia(this@RadioService) * 1000 / kroki).toLong()) else koniec()
            }
        }
        handler.post(krokPrzejscia)
    }

    // Fokus dźwięku obsługujemy sami: powiadomienia i inne aplikacje nie zatrzymują radia,
    // pauza tylko na czas rozmowy telefonicznej.
    private val am by lazy { getSystemService(AudioManager::class.java) }
    private var pauzaRozmowa = false
    private fun rozmowa() = am.mode == AudioManager.MODE_IN_CALL ||
        am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE
    private val zadanieFokusu by lazy {
        android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ z ->
                when (z) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                        if (rozmowa() && player.playWhenReady) {
                            pauzaRozmowa = true
                            LogMowy.dodaj("Rozmowa telefoniczna: pauza")
                            player.pause()
                        } else LogMowy.dodaj("Inna aplikacja użyła dźwięku, gram dalej")
                    AudioManager.AUDIOFOCUS_GAIN -> if (pauzaRozmowa) {
                        pauzaRozmowa = false
                        LogMowy.dodaj("Koniec rozmowy: wznawiam")
                        player.play()
                    }
                }
            }, handler)
            .build()
    }
    private fun wezFokus() { if (!trybSpotify) am.requestAudioFocus(zadanieFokusu) }
    private fun oddajFokus() { am.abandonAudioFocusRequest(zadanieFokusu) }

    /** Dla sesji: radio na żywo, bez znanego czasu trwania (pasek --:-- także przy Spotify). */
    private fun owijka(p: ExoPlayer) = object : ForwardingPlayer(p) {
        override fun getDuration() = C.TIME_UNSET
        override fun getContentDuration() = C.TIME_UNSET
        override fun isCurrentMediaItemSeekable() = false
        override fun isCurrentMediaItemLive() = true
    }

    private fun nowyPlayer(fokus: Boolean): ExoPlayer = ExoPlayer.Builder(this)
        .setAudioAttributes(atrybuty, false)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    private val sluchacz = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            LogMowy.dodaj("Błąd strumienia: ${error.errorCodeName} (próba ${proby + 1})")
            wznow()
        }
        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            if (reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE)
                LogMowy.dodaj("Wstrzymane przez system (powód $reason, np. fokus dźwięku)")
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) proby = 0
        }
        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val e = metadata.get(i)
                if (e is IcyInfo && e.title != null && e.title != icyTytul) {
                    icyTytul = e.title!!
                    odswiezOpis()
                }
            }
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val powod = when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "przycisk"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "inna aplikacja przejęła dźwięk"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "odłączone słuchawki"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "sterowanie zdalne"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "koniec strumienia"
                else -> "powód $reason"
            }
            LogMowy.dodaj((if (playWhenReady) "Gra" else "PAUZA") + ": $powod")
            handler.removeCallbacks(probaWznowienia)
            if (!playWhenReady) {
                // pauza (także po odłączeniu słuchawek): bez RMF24 i bez nasłuchu
                przerwijRmf()
                nasluch.stop()
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    probyFokus = 0
                    handler.postDelayed(probaWznowienia, 3000)
                }
            } else if (player.mediaItemCount > 0) { nasluchStart(); wezFokus() }
            if (!trybSpotify) return
            spotify?.playerApi?.let { if (playWhenReady) it.resume() else it.pause() }
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_IDLE) { przerwijRmf(); nasluch.stop(); if (!pauzaRozmowa) oddajFokus() }
            if (state == Player.STATE_IDLE && trybSpotify) zakonczSpotify()
        }
    }

    /** Po przejęciu dźwięku przez inną aplikację (np. dźwięk powiadomienia) wznawia, gdy ona skończy. */
    private var probyFokus = 0
    private val probaWznowienia = object : Runnable {
        override fun run() {
            if (player.playWhenReady || player.mediaItemCount == 0) return
            val am = getSystemService(AudioManager::class.java)
            val rozmowa = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
                || am.mode == AudioManager.MODE_RINGTONE
            if (!rozmowa && !am.isMusicActive) {
                LogMowy.dodaj("Inna aplikacja skończyła, wznawiam radio")
                player.play()
            } else if (++probyFokus < 24) handler.postDelayed(this, 5000)
            else LogMowy.dodaj("Dźwięk zajęty dłużej niż 2 min, zostaje pauza")
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = nowyPlayer(true)
        player.addListener(sluchacz)
        val stop = CommandButton.Builder()
            .setDisplayName("Stop")
            .setIconResId(R.drawable.ic_stop)
            .setSessionCommand(SessionCommand(KOMENDA_STOP, Bundle.EMPTY))
            .build()
        sesja = MediaLibrarySession.Builder(this, owijka(player), Komendy())
            .setCustomLayout(ImmutableList.of(stop))
            .build()
        handler.postDelayed(tik, TIK_S * 1000L)
    }

    // ---------- Sesja: aplikacja, powiadomienie, Android Auto ----------

    private fun elementBiblioteki(id: String, tytul: String, podtytul: String?) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(tytul).setSubtitle(podtytul).setArtist(podtytul)
                .setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION).build()
        ).build()

    private fun listaAuta(): List<MediaItem> {
        val m = Magazyn(this)
        val tryb = if (StanLosowania.trybAuto(this)) "tryb auto" else "stacja dnia"
        return listOf(elementBiblioteki(ID_LOSUJ, "🎲 Graj losowo", tryb)) +
            m.stacje.map { elementBiblioteki(it.id, it.nazwa, if (m.wLosowaniu(it.id)) "w losowaniu" else null) }
    }

    /** Zastępczy element (cisza), dopóki właściwa stacja się nie uruchomi. */
    private fun zastepczy(id: String) = MediaItem.Builder()
        .setUri(Uri.fromFile(plikCiszy(this))).setMediaId(id).build()

    private inner class Komendy : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession, controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val k = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(KOMENDA_GRAJ, Bundle.EMPTY))
                .add(SessionCommand(KOMENDA_LOSUJ, Bundle.EMPTY))
                .add(SessionCommand(KOMENDA_STOP, Bundle.EMPTY))
                .build()
            // radio na żywo: bez przewijania (znika pasek czasu --:--) i bez poprzedni/następny
            val p = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .removeAll(
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                ).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(k).setAvailablePlayerCommands(p).build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                KOMENDA_GRAJ -> {
                    prefs.edit().putBoolean("aktywne", false).apply()
                    Magazyn(this@RadioService).stacja(args.getString("id") ?: "")?.let { graj(it) }
                }
                KOMENDA_LOSUJ -> losuj()
                KOMENDA_STOP -> { player.stop(); player.clearMediaItems(); obecna = null }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val korzen = MediaItem.Builder().setMediaId(ID_KORZEN).setMediaMetadata(
                MediaMetadata.Builder().setTitle("Radio").setIsBrowsable(true).setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS).build()
            ).build()
            return Futures.immediateFuture(LibraryResult.ofItem(korzen, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val lista = if (parentId == ID_KORZEN) listaAuta() else emptyList()
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(lista), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val e = listaAuta().firstOrNull { it.mediaId == mediaId }
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(e, null))
        }

        /** Wybór w Android Auto: uruchamiamy stację po swojemu, a sesji oddajemy element zastępczy. */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val id = mediaItems.firstOrNull()?.mediaId ?: ID_LOSUJ
            handler.post { uruchomZBiblioteki(id) }
            return Futures.immediateFuture(MediaItemsWithStartPosition(listOf(zastepczy(id)), 0, C.TIME_UNSET))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaItemsWithStartPosition> {
            handler.post { uruchomZBiblioteki(ID_LOSUJ) }
            return Futures.immediateFuture(MediaItemsWithStartPosition(listOf(zastepczy(ID_LOSUJ)), 0, C.TIME_UNSET))
        }
    }

    private fun uruchomZBiblioteki(id: String) {
        if (id == ID_LOSUJ) { losuj(); return }
        prefs.edit().putBoolean("aktywne", false).apply()
        Magazyn(this).stacja(id)?.let { graj(it) } ?: losuj()
    }

    // ---------- Losowanie ----------

    private fun wylosuj(m: Magazyn, bez: String?): Stacja? {
        val pula = m.losowanie.filter { it.id != bez && m.stacja(it.id) != null }
            .ifEmpty { m.losowanie.filter { m.stacja(it.id) != null } }
        val suma = pula.sumOf { it.waga }
        if (suma <= 0) return null
        var r = los.nextDouble() * suma
        for (l in pula) {
            r -= l.waga
            if (r < 0) return m.stacja(l.id)
        }
        return m.stacja(pula.last().id)
    }

    private fun losuj() {
        val m = Magazyn(this)
        val e = prefs.edit().putBoolean("aktywne", true)
        val s: Stacja? = if (StanLosowania.trybAuto(this)) {
            val id = prefs.getString("auto_id", null)
            val biezaca = id?.takeIf { m.wLosowaniu(it) }?.let { m.stacja(it) }
            if (biezaca != null && StanLosowania.autoSek(this) < StanLosowania.autoMin(this) * 60) biezaca
            else wylosuj(m, id)?.also { e.putString("auto_id", it.id).putInt("auto_sek", 0) }
        } else {
            val dzis = LocalDate.now().toString()
            val id = prefs.getString("dzien_id", null)
            val biezaca = id?.takeIf { prefs.getString("dzien_data", "") == dzis && m.wLosowaniu(it) }
                ?.let { m.stacja(it) }
            biezaca ?: wylosuj(m, null)?.also {
                e.putString("dzien_data", dzis).putString("dzien_id", it.id)
            }
        }
        e.apply()
        s?.let { graj(it) }
    }

    /** Co 5 s: w trybie auto liczy czas słuchania i po godzinie zmienia stację. */
    private val tik = object : Runnable {
        override fun run() {
            handler.postDelayed(this, TIK_S * 1000L)
            if (!StanLosowania.losujeTeraz(this@RadioService) ||
                !StanLosowania.trybAuto(this@RadioService) || !player.isPlaying) return
            // czas względny: minuty, gdy gra RMF24, się nie liczą
            if (trybRmf && StanLosowania.wzgledny(this@RadioService)) return
            // RMF24 (etap 3): w czasie względnym minuty RMF24 nie będą się liczyć
            val sek = StanLosowania.autoSek(this@RadioService) + TIK_S
            if (sek >= StanLosowania.autoMin(this@RadioService) * 60) {
                val m = Magazyn(this@RadioService)
                val nowa = wylosuj(m, prefs.getString("auto_id", null))
                prefs.edit().putInt("auto_sek", 0).putString("auto_id", nowa?.id).apply()
                LogMowy.dodaj("Auto: minął czas, zmiana stacji na ${nowa?.nazwa ?: "(brak)"}")
                nowa?.let { graj(it) }
            } else {
                val e = prefs.edit().putInt("auto_sek", sek)
                val teraz = System.currentTimeMillis()
                if (teraz - ostatniZapis > 30_000) { e.commit(); ostatniZapis = teraz } else e.apply()
                if (sek % 60 < TIK_S) odswiezOpis()
            }
        }
    }

    // ---------- Opis w powiadomieniu ----------

    /** Tytuł utworu ze strumienia (ICY). */
    private var icyTytul = ""

    /** Wiersz 1: stacja (+ licznik trybu auto), wiersz 2: co gra teraz. */
    private fun opis(s: Stacja): MediaMetadata {
        val auto = StanLosowania.losujeTeraz(this) && StanLosowania.trybAuto(this)
        val wiersz1 = if (auto) "${s.nazwa} · ${StanLosowania.autoSek(this) / 60}/" +
            "${StanLosowania.autoMin(this)} min" else s.nazwa
        val wiersz2 = when {
            trybRmf -> "Teraz gra RMF24"
            trybSpotify -> spotifyUtwor
            else -> icyTytul
        }.ifEmpty { null }
        return MediaMetadata.Builder().setStation(s.nazwa).setTitle(wiersz1).setArtist(wiersz2)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION).build()
    }

    private fun odswiezOpis() {
        val s = obecna ?: return
        val item = player.currentMediaItem ?: return
        if (item.mediaId != s.id) return
        val nowy = opis(s)
        val stary = item.mediaMetadata
        if (stary.artist == nowy.artist && stary.title == nowy.title) return
        // ten sam adres: odtwarzanie trwa, zmienia się tylko opis
        player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(nowy).build())
    }

    // ---------- Odtwarzanie ----------

    private fun graj(s: Stacja) {
        LogMowy.dodaj("Start stacji: ${s.nazwa}")
        przerwijRmf()
        anulujPrzenikanie()
        val poprzednia = obecna
        obecna = s
        icyTytul = ""
        if (s.adres == SPOTIFY_ADRES) { grajSpotify(s); return }
        val bylSpotify = trybSpotify
        if (trybSpotify) zakonczSpotify()
        val klucz = getSharedPreferences("ustawienia", MODE_PRIVATE).getString("klucz_di", "") ?: ""
        val item = MediaItem.Builder()
            .setUri(s.adres.replace(ZNACZNIK_DI, klucz.trim()))
            .setMediaId(s.id)
            .setMediaMetadata(opis(s))
            .build()
        proby = 0
        handler.removeCallbacks(ponow)
        // przenikanie: tylko radio → radio, gdy coś gra; DI.FM → DI.FM nie (jeden strumień naraz)
        val diFm = { a: String? -> a?.contains("di.fm") == true }
        val moznaPrzenikac = !bylSpotify && player.isPlaying && poprzednia != null &&
            !(diFm(poprzednia.adres) && diFm(s.adres))
        if (moznaPrzenikac) { przenikaj(item); return }
        player.setAudioAttributes(atrybuty, false)
        player.volume = 1f
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setMediaItem(item)
        player.prepare()
        player.play()
        wezFokus()
        nasluchStart()
    }

    private val przekroczenie = Runnable {
        val n = oczekujacy ?: return@Runnable
        LogMowy.dodaj("Nowa stacja nie ruszyła w 15 s, przełączam bez przenikania")
        val item = n.currentMediaItem
        anulujPrzenikanie()
        item?.let { player.setMediaItem(it); player.prepare(); player.play() }
    }

    private fun anulujPrzenikanie() {
        handler.removeCallbacks(przekroczenie)
        oczekujacy?.release()
        oczekujacy = null
    }

    /** Nowa stacja startuje po cichu w drugim odtwarzaczu; gdy gra, następuje przenikanie. */
    private fun przenikaj(item: MediaItem) {
        val nowy = nowyPlayer(false)
        oczekujacy = nowy
        nowy.volume = 0f
        nowy.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying || oczekujacy !== nowy) return
                nowy.removeListener(this)
                zamien(nowy)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (oczekujacy !== nowy) return
                nowy.removeListener(this)
                LogMowy.dodaj("Nowa stacja: błąd ${error.errorCodeName}, przełączam bez przenikania")
                handler.removeCallbacks(przekroczenie)
                oczekujacy = null
                nowy.release()
                player.setMediaItem(item); player.prepare(); player.play()
            }
        })
        nowy.setMediaItem(item)
        nowy.prepare()
        nowy.play()
        handler.postDelayed(przekroczenie, 15_000)
    }

    private fun zamien(nowy: ExoPlayer) {
        handler.removeCallbacks(przekroczenie)
        oczekujacy = null
        val stary = player
        stary.removeListener(sluchacz)
        player = nowy
        nowy.addListener(sluchacz)
        sesja?.player = owijka(nowy)
        LogMowy.dodaj("Przenikanie stacji")
        val g = stary.volume
        przejscie(koniec = {
            stary.release()
            nowy.setAudioAttributes(atrybuty, false)
        }) { t ->
            stary.volume = g * (1 - t)
            nowy.volume = t
        }
        nasluchStart()
    }

    private val ponow = Runnable {
        if (player.mediaItemCount > 0) { player.prepare(); player.play() }
    }

    // ---------- Spotify ----------

    private fun elementSpotify(s: Stacja, tytul: String): MediaItem {
        spotifyUtwor = tytul
        return MediaItem.Builder()
            .setUri(Uri.fromFile(plikCiszy(this)))
            .setMediaId(s.id)
            .setMediaMetadata(opis(s))
            .build()
    }

    private fun grajSpotify(s: Stacja) {
        val pl = SpotifyUst.wybrana(this)
        if (pl == null) {
            LogMowy.dodaj("Spotify: nie wybrano playlisty")
            return
        }
        handler.removeCallbacks(ponow)
        oddajFokus()     // fokus potrzebny aplikacji Spotify
        trybSpotify = true
        spotifyUtwor = ""
        // cisza w pętli, bez fokusu dźwięku (inaczej Spotify by ją wyciszał i odwrotnie)
        player.setAudioAttributes(atrybuty, false)
        player.volume = 0f
        player.repeatMode = Player.REPEAT_MODE_ONE
        spotifyUtwor = "Łączę ze Spotify..."
        player.setMediaItem(elementSpotify(s, spotifyUtwor))
        player.prepare()
        player.play()
        nasluchStart()
        val start = { r: SpotifyAppRemote ->
            r.playerApi.setShuffle(true).setResultCallback {
                if (pl.utwory > 1) r.playerApi.skipToIndex(pl.uri, los.nextInt(pl.utwory))
                else r.playerApi.play(pl.uri)
            }
        }
        spotify?.takeIf { it.isConnected }?.let { start(it); return }
        val params = ConnectionParams.Builder(CLIENT_ID).setRedirectUri(REDIRECT_URI)
            .showAuthView(false).build()
        SpotifyAppRemote.connect(this, params, object : Connector.ConnectionListener {
            override fun onConnected(r: SpotifyAppRemote) {
                if (!trybSpotify) { SpotifyAppRemote.disconnect(r); return }
                spotify = r
                start(r)
                r.playerApi.subscribeToPlayerState().setEventCallback { st ->
                    val t = st.track ?: return@setEventCallback
                    val tytul = "${t.artist?.name ?: ""} – ${t.name ?: ""}"
                    if (tytul != spotifyUtwor && trybSpotify) {
                        spotifyUtwor = tytul
                        odswiezOpis()
                    }
                }
            }

            override fun onFailure(t: Throwable) {
                Log.e("Radio", "Spotify: błąd połączenia", t)
                LogMowy.dodaj("Spotify: błąd połączenia ${t.javaClass.simpleName}")
                if (trybSpotify) player.replaceMediaItem(0,
                    elementSpotify(s, "Błąd Spotify: ${t.javaClass.simpleName}. Połącz w Ustawieniach."))
            }
        })
    }

    private fun zakonczSpotify() {
        trybSpotify = false
        spotify?.let { r ->
            r.playerApi.pause()
            SpotifyAppRemote.disconnect(r)
        }
        spotify = null
        player.repeatMode = Player.REPEAT_MODE_OFF
    }

    /** Zerwany strumień: ponowne próby po 2, 5, 15, 60 i 120 s. */
    private fun wznow() {
        if (proby >= WZNOW_ODSTEPY_S.size) return
        handler.postDelayed(ponow, WZNOW_ODSTEPY_S[proby] * 1000)
        proby++
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = sesja

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (trybSpotify) zakonczSpotify()
        anulujPrzenikanie()
        oddajFokus()
        rmf.stop()
        nasluch.zakoncz()
        sesja?.run { player.release(); release() }
        sesja = null
        super.onDestroy()
    }
}
