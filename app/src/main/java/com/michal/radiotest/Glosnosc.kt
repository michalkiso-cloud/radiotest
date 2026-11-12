package com.michal.radiotest

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Ustawienia etapu 4 (zakładka Głośność). */
object UstGlosnosci {
    const val ID_RMF = "rmf24"
    private fun p(c: Context) = c.getSharedPreferences("glosnosc", Context.MODE_PRIVATE)
    fun wlaczone(c: Context) = p(c).getBoolean("wlaczone", true)
    fun ustawWlaczone(c: Context, v: Boolean) = p(c).edit().putBoolean("wlaczone", v).apply()
    /** Docelowa głośność (LUFS). */
    fun cel(c: Context) = p(c).getFloat("cel", -10f)
    fun ustawCel(c: Context, v: Float) = p(c).edit().putFloat("cel", v.coerceIn(-30f, -3f)).apply()
    /** Największe dopuszczalne podgłośnienie przez automat (dB). */
    fun maks(c: Context) = p(c).getFloat("maks", 1.5f)
    fun ustawMaks(c: Context, v: Float) = p(c).edit().putFloat("maks", v.coerceIn(0f, 6f)).apply()
    /** Ręczna korekta stacji (dB), dodawana do automatu. */
    fun korekta(c: Context, id: String) = p(c).getFloat("kor_$id", 0f)
    fun ustawKorekte(c: Context, id: String, v: Float) =
        p(c).edit().putFloat("kor_$id", v.coerceIn(-20f, 6f)).apply()
}

/** Podgląd na żywo w zakładce Głośność. */
object StanGlosnosci {
    val opis = mutableStateOf("—")
}

/**
 * Wyrównywanie głośności: pomiar LUFS (filtr K jak w EBU R128, średnia ok. 40 s),
 * powolne dociąganie do celu, podgłośnienie ograniczone, limiter szczytów.
 * Działa na 16-bitowym PCM z przeplotem kanałów.
 */
class Wyrownywacz {
    @Volatile var wlaczone = true
    @Volatile var cel = -10f
    @Volatile var maks = 1.5f
    @Volatile var korekta = 0f
    /** Zmierzona głośność (LUFS) albo NaN, gdy jeszcze brak pomiaru. */
    @Volatile var poziom = Float.NaN; private set
    @Volatile var wzmocnienie = 0f; private set

    private var sr = 0
    private var k = 0
    // współczynniki filtrów K (półka + górnoprzepustowy)
    private var s1 = DoubleArray(5)
    private var s2 = DoubleArray(5)
    private var z = Array(8) { DoubleArray(4) }

    private var blokRamek = 0
    private var wBloku = 0
    private var sumaBloku = 0.0
    private var energia = -1.0       // średnia energia (liniowo); <0 = brak pomiaru
    private var blokowOdStartu = 0
    private var g = 1.0              // bieżące wzmocnienie liniowe
    private var gCel = 1.0
    private var krokG = 0.0
    private var obwiednia = 0.0

    fun konfiguruj(sampleRate: Int, kanaly: Int) {
        if (sampleRate == sr && kanaly == k) return
        sr = sampleRate; k = kanaly.coerceIn(1, 8)
        s1 = polka(sr.toDouble()); s2 = gornoprzep(sr.toDouble())
        z = Array(8) { DoubleArray(4) }
        blokRamek = (sr * 0.4).toInt().coerceAtLeast(1)
        wBloku = 0; sumaBloku = 0.0
    }

    /** Nowa stacja: pomiar od zera, wzmocnienie startowe (dB). */
    @Synchronized fun reset(startDb: Float) {
        energia = -1.0; blokowOdStartu = 0; poziom = Float.NaN
        wBloku = 0; sumaBloku = 0.0
        g = 10.0.pow(startDb / 20.0); gCel = g; krokG = 0.0
        wzmocnienie = startDb
    }

    @Synchronized fun przetworz(d: ShortArray, n: Int) {
        if (k == 0) return
        val ramki = n / k
        val prog = 0.944   // −0.5 dBFS
        val zwolnienie = 1.0 - 1.0 / (sr * 0.15)
        var i = 0
        for (r in 0 until ramki) {
            // pomiar (przed wzmocnieniem)
            var e = 0.0
            for (ch in 0 until k) {
                val x = d[i + ch] / 32768.0
                e += filtrK(x, z[ch]).let { it * it }
            }
            sumaBloku += e
            if (++wBloku >= blokRamek) koniecBloku()
            // wzmocnienie (płynnie) + limiter
            if (krokG != 0.0) {
                g += krokG
                if ((krokG > 0 && g >= gCel) || (krokG < 0 && g <= gCel)) { g = gCel; krokG = 0.0 }
            }
            var szczyt = 0.0
            for (ch in 0 until k) szczyt = maxOf(szczyt, abs(d[i + ch] * g))
            szczyt /= 32768.0
            obwiednia = if (szczyt > obwiednia) szczyt else obwiednia * zwolnienie + szczyt * (1 - zwolnienie)
            val gl = if (obwiednia > prog) g * prog / obwiednia else g
            for (ch in 0 until k) {
                val y = d[i + ch] * gl
                d[i + ch] = y.coerceIn(-32768.0, 32767.0).toInt().toShort()
            }
            i += k
        }
    }

    private fun koniecBloku() {
        val ms = sumaBloku / wBloku
        wBloku = 0; sumaBloku = 0.0
        blokowOdStartu++
        val lufs = -0.691 + 10 * log10(ms + 1e-12)
        if (lufs > -60) {   // cisza nie psuje średniej
            val tau = if (blokowOdStartu < 20) 3.0 else 40.0   // pierwsze 8 s: szybko
            val a = 0.4 / tau
            energia = if (energia < 0) ms else energia + a * (ms - energia)
            poziom = (-0.691 + 10 * log10(energia + 1e-12)).toFloat()
        }
        val teraz = 20 * log10(g)
        if (wlaczone && poziom.isNaN()) return   // brak pomiaru: zostaje wzmocnienie startowe
        val autoDb = if (wlaczone) (cel - poziom).coerceAtMost(maks) else 0f
        val celDb = autoDb + korekta
        // tempo zmian: szybko na starcie, potem 0,25 dB/s
        val maxKrok = if (blokowOdStartu < 20) 1.0 else 0.1
        val nowyDb = teraz + (celDb - teraz).coerceIn(-maxKrok, maxKrok)
        wzmocnienie = nowyDb.toFloat()
        gCel = 10.0.pow(nowyDb / 20.0)
        krokG = (gCel - g) / blokRamek
    }

    private fun filtrK(x: Double, s: DoubleArray): Double {
        // dwa biquady, postać bezpośrednia II transponowana
        val y1 = s1[0] * x + s[0]
        s[0] = s1[1] * x - s1[3] * y1 + s[1]
        s[1] = s1[2] * x - s1[4] * y1
        val y2 = s2[0] * y1 + s[2]
        s[2] = s2[1] * y1 - s2[3] * y2 + s[3]
        s[3] = s2[2] * y1 - s2[4] * y2
        return y2
    }

    private fun polka(fs: Double): DoubleArray {
        val gDb = 3.99984385397; val q = 0.7071752369554193; val fc = 1681.9744509555319
        val a = 10.0.pow(gDb / 40); val w = 2 * PI * fc / fs; val al = sin(w) / (2 * q); val c = cos(w)
        val b0 = a * ((a + 1) + (a - 1) * c + 2 * sqrt(a) * al)
        val b1 = -2 * a * ((a - 1) + (a + 1) * c)
        val b2 = a * ((a + 1) + (a - 1) * c - 2 * sqrt(a) * al)
        val a0 = (a + 1) - (a - 1) * c + 2 * sqrt(a) * al
        val a1 = 2 * ((a - 1) - (a + 1) * c)
        val a2 = (a + 1) - (a - 1) * c - 2 * sqrt(a) * al
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun gornoprzep(fs: Double): DoubleArray {
        val q = 0.5003270373253953; val fc = 38.13547087613982
        val w = 2 * PI * fc / fs; val al = sin(w) / (2 * q); val c = cos(w)
        val a0 = 1 + al
        return doubleArrayOf((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
    }
}

/** Wpina Wyrownywacz w tor dźwięku ExoPlayera. */
@androidx.annotation.OptIn(UnstableApi::class)
class ProcesorGlosnosci(val w: Wyrownywacz) : BaseAudioProcessor() {
    private var bufor = ShortArray(0)

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        w.konfiguruj(f.sampleRate, f.channelCount)
        return f
    }

    override fun queueInput(we: ByteBuffer) {
        val bajty = we.remaining()
        if (bajty == 0) return
        val n = bajty / 2
        if (bufor.size < n) bufor = ShortArray(n)
        we.order(ByteOrder.nativeOrder())
        for (i in 0 until n) bufor[i] = we.getShort()
        we.position(we.limit())
        w.przetworz(bufor, n)
        val wy = replaceOutputBuffer(n * 2)
        for (i in 0 until n) wy.putShort(bufor[i])
        wy.flip()
    }
}
