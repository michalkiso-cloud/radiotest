package com.michal.radiotest

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Parametry jak w skrypcie radio_mowa_telefon.py
const val BLOK_S = 20
const val PROG_YAMNET = 0.25f
const val POTW_MOWA = 3
const val POTW_MUZYKA = 2
private const val SR = 16000
private const val IDX_MOWA = 0      // YAMNet: "Speech"
private const val IDX_MUZYKA = 132  // YAMNet: "Music"

/** Wynik analizy jednego bloku RMF24. */
data class Blok(
    val nr: Int, val start: Long, val mowa: Float, val muzyka: Float,
    val pierwMowaS: Float?, val ostMowaS: Float?, val werdykt: String,
    val jestMowa: Boolean, val seriaStart: Int, val seriaN: Int,
    /** pierwsza ramka (próbka na kanał) bloku w buforze RMF24 */
    val ramkaStart: Long, val dlS: Float
)

const val BUFOR_RMF_S = 180

/** Log wykrywania mowy widoczny w aplikacji. */
object LogMowy {
    val wpisy = mutableStateListOf<String>()
    val werdykt = mutableStateOf("?")
    val aktywny = mutableStateOf(false)
    /** true, gdy zamiast radia głównego gra RMF24 (mowa) */
    val graRmf = mutableStateOf(false)
    private val glowny = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun dodaj(tekst: String) {
        val linia = fmt.format(Date()) + " " + tekst
        Log.i("Radio", linia)
        glowny.post {
            wpisy.add(0, linia)
            while (wpisy.size > 300) wpisy.removeAt(wpisy.size - 1)
        }
    }
}

/** Ustawienia wykrywania mowy. */
object UstMowy {
    private fun p(c: Context) = c.getSharedPreferences("mowa", Context.MODE_PRIVATE)
    fun wlaczone(c: Context) = p(c).getBoolean("wlaczone", true)
    fun ustaw(c: Context, w: Boolean) = p(c).edit().putBoolean("wlaczone", w).apply()
    /** Zapas przed pierwszym oknem z mową przy przełączaniu na RMF24 (s). */
    fun margPocz(c: Context) = p(c).getFloat("marg_pocz", 4f)
    /** Zapas po ostatnim oknie z mową przy powrocie do muzyki (s). */
    fun margKon(c: Context) = p(c).getFloat("marg_kon", 2f)
    /** Czas płynnego przejścia (RMF24 i zmiana stacji), s. */
    fun czasPrzejscia(c: Context) = p(c).getFloat("przejscie", 1f)
    fun ustawCzasPrzejscia(c: Context, v: Float) = p(c).edit().putFloat("przejscie", v.coerceIn(0.2f, 10f)).apply()
    fun ustawMargPocz(c: Context, v: Float) = p(c).edit().putFloat("marg_pocz", v.coerceIn(0f, 20f)).apply()
    fun ustawMargKon(c: Context, v: Float) = p(c).edit().putFloat("marg_kon", v.coerceIn(0f, 20f)).apply()
}

/**
 * Nasłuch RMF24 w tle: strumień gra wyciszony, dźwięk (PCM) jest przechwytywany,
 * przeliczany na 16 kHz mono i co 20 s oceniany przez YAMNet.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class Nasluch(private val ctx: Context, private val naBlok: (Blok) -> Unit) {
    private val watek = HandlerThread("yamnet").apply { start() }
    private val analiza = Handler(watek.looper)
    private val glowny = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var model: Interpreter? = null
    private var dlRamki = 15600

    // przeliczanie na 16 kHz
    private var srWe = 44100
    private var kanaly = 2
    private var poprzednia = 0f
    private var nWe = 0L
    private var nWy = 0L
    private var blok = FloatArray(BLOK_S * SR)
    private var wBloku = 0
    private var startBloku = 0L
    private var nrBloku = 0
    private var ramkaBloku = 0L

    // bufor RMF24 w oryginalnej jakości (do odtwarzania z opóźnieniem)
    private val zamek = Object()
    private var bufor = ShortArray(0)
    @Volatile var srBufora = 44100; private set
    @Volatile var kanalyBufora = 2; private set
    @Volatile private var ramkiZapisane = 0L
    private var seriaStart = 0

    // werdykt
    private var seriaMowa: Boolean? = null
    private var seriaN = 0
    private var werdykt = "?"

    private val tee = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            if (sampleRateHz != srWe || channelCount != kanaly || bufor.isEmpty()) {
                synchronized(zamek) {
                    srWe = sampleRateHz
                    kanaly = channelCount
                    srBufora = sampleRateHz
                    kanalyBufora = channelCount
                    bufor = ShortArray(sampleRateHz * channelCount.coerceAtLeast(1) * BUFOR_RMF_S)
                }
            }
        }

        override fun handleBuffer(buffer: ByteBuffer) = przyjmij(buffer)
    })

    val dziala get() = player != null

    fun start() {
        if (player != null) return
        analiza.post { ladujModel() }
        val sink = DefaultAudioSink.Builder(ctx).setAudioProcessors(arrayOf(tee)).build()
        val renderery = RenderersFactory { handler, _, audio, _, _ ->
            arrayOf(MediaCodecAudioRenderer(ctx, MediaCodecSelector.DEFAULT, handler, audio, sink))
        }
        val p = ExoPlayer.Builder(ctx, renderery)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                false
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.volume = 0f
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                LogMowy.dodaj("RMF24: błąd ${error.errorCodeName}, ponawiam za 5 s")
                glowny.postDelayed({ player?.let { it.prepare(); it.play() } }, 5000)
            }
        })
        p.setMediaItem(MediaItem.fromUri(RMF24))
        p.prepare()
        p.play()
        player = p
        resetuj()
        LogMowy.aktywny.value = true
        LogMowy.dodaj("Nasłuch RMF24: start")
    }

    fun stop() {
        val p = player ?: return
        player = null
        glowny.removeCallbacksAndMessages(null)
        p.release()
        LogMowy.aktywny.value = false
        LogMowy.dodaj("Nasłuch RMF24: stop")
    }

    fun zakoncz() {
        stop()
        analiza.post { model?.close(); model = null }
        watek.quitSafely()
    }

    private fun resetuj() {
        wBloku = 0; nWe = 0; nWy = 0; nrBloku = 0
        synchronized(zamek) { ramkiZapisane = 0 }
        startBloku = System.currentTimeMillis()
        seriaMowa = null; seriaN = 0; werdykt = "?"
        LogMowy.werdykt.value = "?"
    }

    private fun ladujModel() {
        if (model != null) return
        try {
            val fd = ctx.assets.openFd("yamnet.tflite")
            val bufor = FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            val m = Interpreter(bufor, Interpreter.Options().setNumThreads(2))
            val ksztalt = m.getInputTensor(0).shape()
            val n = ksztalt.fold(1) { a, b -> a * b }
            if (n < 1000) m.resizeInput(0, intArrayOf(15600)) else dlRamki = n
            m.allocateTensors()
            dlRamki = m.getInputTensor(0).shape().fold(1) { a, b -> a * b }
            model = m
            LogMowy.dodaj("YAMNet załadowany (ramka $dlRamki próbek)")
        } catch (e: Exception) {
            LogMowy.dodaj("YAMNet niedostępny: ${e.message}")
        }
    }

    /** Wywoływane z wątku odtwarzacza: PCM 16-bit, przeplatane kanały. */
    private fun przyjmij(b: ByteBuffer) {
        val bb = b.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val k = kanaly.coerceAtLeast(1)
        val krok = srWe.toDouble() / SR
        val buf = bufor
        while (bb.remaining() >= 2 * k) {
            var suma = 0f
            if (buf.isNotEmpty()) {
                var poz = ((ramkiZapisane % (buf.size / k)) * k).toInt()
                repeat(k) { val v = bb.short; buf[poz++] = v; suma += v / 32768f }
                ramkiZapisane++
            } else repeat(k) { suma += bb.short / 32768f }
            val x = suma / k
            // wyjście 16 kHz: próbki między poprzednią a bieżącą (interpolacja liniowa)
            while (nWy * krok <= nWe) {
                val t = (nWy * krok - (nWe - 1)).toFloat().coerceIn(0f, 1f)
                dodajProbke(poprzednia + (x - poprzednia) * t)
                nWy++
            }
            poprzednia = x
            nWe++
        }
    }

    private fun dodajProbke(v: Float) {
        if (wBloku == 0) { startBloku = System.currentTimeMillis(); ramkaBloku = ramkiZapisane }
        blok[wBloku++] = v
        if (wBloku == blok.size) {
            val gotowy = blok
            val start = startBloku
            val ramka = ramkaBloku
            val nr = nrBloku++
            blok = FloatArray(BLOK_S * SR)
            wBloku = 0
            analiza.post { analizuj(gotowy, start, nr, ramka) }
        }
    }

    /** Najnowsza zapisana ramka RMF24. */
    fun ramkaNajnowsza(): Long = ramkiZapisane

    /** Najstarsza ramka jeszcze dostępna w buforze. */
    fun ramkaNajstarsza(): Long {
        val dl = if (kanalyBufora > 0 && bufor.isNotEmpty()) bufor.size / kanalyBufora else 0
        return (ramkiZapisane - dl + srBufora).coerceAtLeast(0)   // 1 s zapasu
    }

    /** Kopiuje ramki od `od` do `dst`; zwraca liczbę skopiowanych ramek (0, gdy jeszcze ich nie ma). */
    fun czytaj(od: Long, dst: ShortArray): Int {
        synchronized(zamek) {
            val k = kanalyBufora
            val buf = bufor
            if (buf.isEmpty() || od >= ramkiZapisane) return 0
            val dl = buf.size / k
            val n = minOf((ramkiZapisane - od).toInt(), dst.size / k)
            var poz = ((od % dl) * k).toInt()
            for (i in 0 until n * k) {
                dst[i] = buf[poz++]
                if (poz >= buf.size) poz = 0
            }
            return n
        }
    }

    private fun analizuj(x: FloatArray, start: Long, nr: Int, ramka: Long) {
        val m = model ?: return
        val n = dlRamki
        val wej = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        val wyj = Array(1) { FloatArray(m.getOutputTensor(0).shape().last()) }
        var sumaMowa = 0f
        var sumaMuzyka = 0f
        var ramki = 0
        var pierw: Float? = null
        var ost: Float? = null
        var st = 0
        while (st + n <= x.size) {
            wej.rewind()
            for (i in st until st + n) wej.putFloat(x[i])
            wej.rewind()
            m.run(wej, wyj)
            val mowa = wyj[0][IDX_MOWA]
            sumaMowa += mowa
            sumaMuzyka += wyj[0][IDX_MUZYKA]
            if (mowa >= PROG_YAMNET) {
                ost = (st + n) / SR.toFloat()
                if (pierw == null) pierw = st / SR.toFloat()
            }
            ramki++
            st += n / 2
        }
        if (ramki == 0) return
        val sm = sumaMowa / ramki
        val smu = sumaMuzyka / ramki
        val jestMowa = sm >= PROG_YAMNET
        if (jestMowa == seriaMowa) seriaN++ else { seriaMowa = jestMowa; seriaN = 1; seriaStart = nr }
        if (jestMowa && seriaN >= POTW_MOWA) werdykt = "MOWA"
        else if (!jestMowa && seriaN >= POTW_MUZYKA) werdykt = "MUZYKA"
        val w = werdykt
        val b = Blok(nr, start, sm, smu, pierw, ost, w, jestMowa, seriaStart, seriaN, ramka,
            x.size / SR.toFloat())
        glowny.post {
            LogMowy.werdykt.value = w
            naBlok(b)
        }
        LogMowy.dodaj(String.format(Locale.US, "blok: mowa %.2f | muzyka %.2f | %s | werdykt: %s",
            sm, smu, if (jestMowa) "mowa" else "muzyka", w))
    }
}
