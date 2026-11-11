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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.time.LocalDate
import java.security.SecureRandom

const val KOMENDA_GRAJ = "graj"          // argument "id" = id stacji (tryb ręczny)
const val KOMENDA_LOSUJ = "losuj"        // gra stację z losowania (dzienny albo auto)
private const val TIK_S = 5
private val WZNOW_ODSTEPY_S = longArrayOf(2, 5, 15, 60, 120)

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
class RadioService : MediaSessionService() {
    private var sesja: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private val los = SecureRandom()
    private var proby = 0
    private var ostatniZapis = 0L

    private val prefs get() = StanLosowania.prefs(this)

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = wznow()
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) proby = 0
            }
        })
        sesja = MediaSession.Builder(this, player).setCallback(Komendy()).build()
        handler.postDelayed(tik, TIK_S * 1000L)
    }

    private inner class Komendy : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession, controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val k = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(KOMENDA_GRAJ, Bundle.EMPTY))
                .add(SessionCommand(KOMENDA_LOSUJ, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(k).build()
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
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    // ---------- Losowanie ----------

    private fun wylosuj(m: Magazyn, bez: String?): Stacja? {
        val pula = m.losowanie.filter { it.id != bez && m.stacja(it.id) != null }
            .ifEmpty { m.losowanie.filter { m.stacja(it.id) != null } }
        val suma = pula.sumOf { it.waga }
        if (suma <= 0) return null
        var r = los.nextInt(suma)
        for (l in pula) {
            r -= l.waga
            if (r < 0) return m.stacja(l.id)
        }
        return null
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
            // RMF24 (etap 3): w czasie względnym minuty RMF24 nie będą się liczyć
            val sek = StanLosowania.autoSek(this@RadioService) + TIK_S
            if (sek >= StanLosowania.autoMin(this@RadioService) * 60) {
                val m = Magazyn(this@RadioService)
                val nowa = wylosuj(m, prefs.getString("auto_id", null))
                prefs.edit().putInt("auto_sek", 0).putString("auto_id", nowa?.id).apply()
                nowa?.let { graj(it) }
            } else {
                val e = prefs.edit().putInt("auto_sek", sek)
                val teraz = System.currentTimeMillis()
                if (teraz - ostatniZapis > 30_000) { e.commit(); ostatniZapis = teraz } else e.apply()
            }
        }
    }

    // ---------- Odtwarzanie ----------

    private fun graj(s: Stacja) {
        val klucz = getSharedPreferences("ustawienia", MODE_PRIVATE).getString("klucz_di", "") ?: ""
        val item = MediaItem.Builder()
            .setUri(s.adres.replace(ZNACZNIK_DI, klucz.trim()))
            .setMediaId(s.id)
            .setMediaMetadata(MediaMetadata.Builder().setStation(s.nazwa).setArtist(s.nazwa).build())
            .build()
        proby = 0
        handler.removeCallbacks(ponow)
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    private val ponow = Runnable {
        if (player.mediaItemCount > 0) { player.prepare(); player.play() }
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
        sesja?.run { player.release(); release() }
        sesja = null
        super.onDestroy()
    }
}
