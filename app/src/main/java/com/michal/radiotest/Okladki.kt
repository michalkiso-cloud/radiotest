package com.michal.radiotest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Okładki do tytułów ICY („Wykonawca - Tytuł”): Deezer, potem iTunes (bez kluczy).
 * Wynik jest przyjmowany tylko, gdy zgadza się wykonawca (inaczej logo stacji).
 */
object Okladki {
    private val pamiec = object : LinkedHashMap<String, ByteArray?>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ByteArray?>) = size > 60
    }
    private val loga = HashMap<String, ByteArray>()

    /** Wywoływać poza wątkiem głównym. null = brak pewnej okładki. */
    fun szukaj(icy: String): ByteArray? {
        synchronized(pamiec) { if (pamiec.containsKey(icy)) return pamiec[icy] }
        val wynik = runCatching { znajdz(icy) }.getOrNull()
        synchronized(pamiec) { pamiec[icy] = wynik }
        return wynik
    }

    private fun znajdz(icy: String): ByteArray? {
        val czesci = icy.split(" - ", " – ", limit = 2)
        if (czesci.size < 2) return null          // np. „Gramy muzykę klubową 24/7”
        val wykonawca = czysc(czesci[0])
        val tytul = czysc(czesci[1])
        if (wykonawca.isBlank() || tytul.isBlank()) return null
        val q = "$wykonawca $tytul"
        val adres = deezer(q, wykonawca) ?: itunes(q, wykonawca) ?: return null
        return pobierz(adres)
    }

    private fun czysc(t: String) = t
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        .replace(Regex("(?i)\\S+\\.(eu|pl|com|net|fm)\\b"), " ")   // np. „Cmp3.eu”
        .replace(Regex("(?i)\\b(feat\\.?|ft\\.?|vs\\.?|x)\\b|&|,"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun norm(t: String) = Normalizer.normalize(t, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9 ]"), " ")

    /** Czy wykonawca z wyniku pasuje do wykonawcy z radia (pierwsze znaczące słowo). */
    private fun pasuje(zRadia: String, zWyniku: String): Boolean {
        val a = norm(zRadia).split(" ").filter { it.length >= 3 }
        val b = norm(zWyniku)
        return a.isNotEmpty() && a.any { b.contains(it) }
    }

    private fun json(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        return c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun deezer(q: String, wyk: String): String? = runCatching {
        val d = json("https://api.deezer.com/search?limit=5&q=" + URLEncoder.encode(q, "UTF-8")).getJSONArray("data")
        for (i in 0 until d.length()) {
            val o = d.getJSONObject(i)
            if (pasuje(wyk, o.getJSONObject("artist").getString("name")))
                return@runCatching o.getJSONObject("album").getString("cover_big")
        }
        null
    }.getOrNull()

    private fun itunes(q: String, wyk: String): String? = runCatching {
        val d = json("https://itunes.apple.com/search?entity=song&limit=5&country=PL&term=" +
            URLEncoder.encode(q, "UTF-8")).getJSONArray("results")
        for (i in 0 until d.length()) {
            val o = d.getJSONObject(i)
            if (pasuje(wyk, o.getString("artistName")))
                return@runCatching o.getString("artworkUrl100").replace("100x100", "500x500")
        }
        null
    }.getOrNull()

    private fun pobierz(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        val bajty = c.inputStream.use { it.readBytes() }
        // sprawdzenie, że to obraz
        return if (BitmapFactory.decodeByteArray(bajty, 0, bajty.size) != null) bajty else null
    }

    fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** Własne logo stacji: kafelek w kolorach ikony aplikacji z nazwą stacji. */
    fun logo(nazwa: String, n: Int = 512): ByteArray = synchronized(loga) {
        loga.getOrPut("$n|$nazwa") {
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val odcien = ((nazwa.hashCode() and 0x7fffffff) % 60 + 250).toFloat()   // fiolety–róże
            val gora = Color.HSVToColor(floatArrayOf(odcien, 0.75f, 0.55f))
            val dol = Color.HSVToColor(floatArrayOf(odcien, 0.85f, 0.2f))
            c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, n.toFloat(), gora, dol, Shader.TileMode.CLAMP)
            })
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            }
            val slowa = nazwa.split(" ").filter { it.isNotBlank() }
            val linie = if (slowa.size > 1 && nazwa.length > 9) slowa.chunked((slowa.size + 1) / 2).map { it.joinToString(" ") } else listOf(nazwa)
            p.textSize = n * 0.234f
            val najdl = linie.maxOf { p.measureText(it) }
            if (najdl > n * 0.84f) p.textSize *= n * 0.84f / najdl
            val wys = p.textSize * 1.15f
            var y = n / 2f - wys * (linie.size - 1) / 2f + p.textSize * 0.35f
            for (l in linie) { c.drawText(l, n / 2f, y, p); y += wys }
            png(b)
        }
    }
}
