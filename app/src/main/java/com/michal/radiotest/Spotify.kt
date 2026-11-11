package com.michal.radiotest

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val CLIENT_ID = "b9612d932c4540dab2a687aa1a7849b1"
const val REDIRECT_URI = "radiotest://callback"
val SPOTIFY_ZAKRESY = arrayOf(
    "app-remote-control", "playlist-read-private", "playlist-read-collaborative",
    "user-read-playback-state"
)

data class Playlista(val uri: String, val nazwa: String, val utwory: Int)

/** Ustawienia Spotify zapisane w telefonie. */
object SpotifyUst {
    private fun p(c: Context) = c.getSharedPreferences("spotify", Context.MODE_PRIVATE)
    fun token(c: Context): String? = p(c).getString("token", null)
        ?.takeIf { p(c).getLong("token_do", 0) > System.currentTimeMillis() }
    fun zapiszToken(c: Context, t: String, sek: Int) = p(c).edit().putString("token", t)
        .putLong("token_do", System.currentTimeMillis() + (sek - 60) * 1000L).apply()
    fun polaczone(c: Context) = p(c).getBoolean("polaczone", false)
    fun ustawPolaczone(c: Context) = p(c).edit().putBoolean("polaczone", true).apply()
    fun wybrana(c: Context): Playlista? {
        val uri = p(c).getString("uri", null) ?: return null
        return Playlista(uri, p(c).getString("nazwa", "") ?: "", p(c).getInt("utwory", 0))
    }
    fun wybierz(c: Context, pl: Playlista) = p(c).edit().putString("uri", pl.uri)
        .putString("nazwa", pl.nazwa).putInt("utwory", pl.utwory).apply()

    fun zapiszLiczbe(c: Context, n: Int) = p(c).edit().putInt("utwory", n).apply()

    /** Aktualna liczba utworów w playliście (Web API); null, gdy się nie udało. */
    fun liczbaUtworow(token: String, uri: String): Int? = runCatching {
        val id = uri.substringAfterLast(":")
        val pol = URL("https://api.spotify.com/v1/playlists/$id?fields=tracks.total")
            .openConnection() as HttpURLConnection
        pol.setRequestProperty("Authorization", "Bearer $token")
        pol.connectTimeout = 8_000
        pol.readTimeout = 8_000
        if (pol.responseCode != 200) return@runCatching null
        JSONObject(pol.inputStream.bufferedReader().readText())
            .optJSONObject("tracks")?.optInt("total")
    }.getOrNull()

    /** Lista playlist z Twojego konta (Web API, wymaga tokenu). Wywoływać poza wątkiem głównym. */
    fun pobierzPlaylisty(token: String): List<Playlista> {
        val wynik = mutableListOf<Playlista>()
        var adres: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        while (adres != null && wynik.size < 500) {
            val pol = URL(adres).openConnection() as HttpURLConnection
            pol.setRequestProperty("Authorization", "Bearer $token")
            pol.connectTimeout = 15_000
            pol.readTimeout = 15_000
            val kod = pol.responseCode
            if (kod != 200) throw IllegalStateException("Spotify odpowiedziało kodem $kod")
            val j = JSONObject(pol.inputStream.bufferedReader().readText())
            val a = j.getJSONArray("items")
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val utwory = o.optJSONObject("tracks")?.optInt("total")
                    ?: o.optJSONObject("items")?.optInt("total") ?: 0
                wynik.add(Playlista(o.getString("uri"), o.optString("name"), utwory))
            }
            adres = j.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }
        return wynik
    }
}

/** Plik z ciszą (1 s, WAV): odtwarzany w pętli, gdy gra Spotify, żeby usługa i powiadomienie żyły. */
fun plikCiszy(c: Context): File {
    val f = File(c.cacheDir, "cisza.wav")
    if (f.exists()) return f
    val probki = 8000
    val dane = probki * 2
    val b = java.nio.ByteBuffer.allocate(44 + dane).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt(36 + dane).put("WAVE".toByteArray())
    b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(probki).putInt(probki * 2)
        .putShort(2).putShort(16)
    b.put("data".toByteArray()).putInt(dane)
    f.writeBytes(b.array())
    return f
}
