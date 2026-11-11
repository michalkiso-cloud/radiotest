package com.michal.radiotest

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * Odtwarza RMF24 z bufora nasłuchu z opóźnieniem (od początku mowy).
 * Gdy pozycja dojdzie do `cel`, wywołuje `naCel` (w wątku głównym).
 */
class OdtwarzaczRmf(private val nasluch: Nasluch) {
    private val glowny = Handler(Looper.getMainLooper())
    @Volatile private var dziala = false
    @Volatile var pozycja = 0L; private set
    @Volatile var cel = Long.MAX_VALUE
    @Volatile private var glosnosc = 0f
    private var watek: Thread? = null
    var naCel: (() -> Unit)? = null

    fun start(od: Long) {
        stop()
        pozycja = od.coerceIn(nasluch.ramkaNajstarsza(), nasluch.ramkaNajnowsza())
        cel = Long.MAX_VALUE
        glosnosc = 0f
        dziala = true
        val sr = nasluch.srBufora
        val k = nasluch.kanalyBufora
        watek = Thread({ petla(sr, k) }, "rmf24").apply { start() }
    }

    fun ustawGlosnosc(v: Float) { glosnosc = v.coerceIn(0f, 1f) }

    fun stop() {
        dziala = false
        watek?.join(500)
        watek = null
    }

    /** Opóźnienie względem RMF24 na żywo, w sekundach. */
    fun opoznienieS(): Float = (nasluch.ramkaNajnowsza() - pozycja) / nasluch.srBufora.toFloat()

    private fun petla(sr: Int, k: Int) {
        val maska = if (k == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(sr, maska, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sr).setChannelMask(maska)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(maxOf(min * 2, sr * k * 2 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val dane = ShortArray(1024 * k)
        var ustawiona = -1f
        try {
            track.setVolume(0f)
            track.play()
            var zgloszono = false
            while (dziala) {
                if (glosnosc != ustawiona) { ustawiona = glosnosc; track.setVolume(ustawiona) }
                val n = nasluch.czytaj(pozycja, dane)
                if (n == 0) { Thread.sleep(20); continue }
                track.write(dane, 0, n * k)
                pozycja += n
                if (!zgloszono && pozycja >= cel) {
                    zgloszono = true
                    glowny.post { naCel?.invoke() }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { track.pause(); track.flush(); track.release() }
        }
    }
}
