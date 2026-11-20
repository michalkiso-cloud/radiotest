package com.michal.radiotest

import android.content.Context
import java.time.LocalDate

/**
 * Statystyki słuchania: czas grania każdej stacji (osobno RMF24 w czasie mowy),
 * liczba losowań i przełączeń na wiadomości, liczone dziennie.
 * Klucze w prefs „statystyki”: t|data|nazwa = sekundy, l|data|nazwa = losowania, r|data = przełączenia na RMF24.
 */
object Statystyki {
    const val RMF = "RMF24 (mowa)"
    private const val DNI_PAMIECI = 400L

    private fun p(c: Context) = c.getSharedPreferences("statystyki", Context.MODE_PRIVATE)
    private fun dzis() = LocalDate.now().toString()

    private fun dodaj(c: Context, klucz: String, ile: Long) {
        val pr = p(c)
        pr.edit().putLong(klucz, pr.getLong(klucz, 0) + ile).apply()
    }

    fun czas(c: Context, nazwa: String, sek: Long) = dodaj(c, "t|${dzis()}|$nazwa", sek)
    fun losowanie(c: Context, nazwa: String) = dodaj(c, "l|${dzis()}|$nazwa", 1)
    fun rmf(c: Context) = dodaj(c, "r|${dzis()}", 1)

    data class Wiersz(val nazwa: String, val sek: Long, val losowania: Long)
    data class Zestaw(val wiersze: List<Wiersz>, val razem: Long, val rmf: Long, val losowania: Long, val dniZDanymi: Int)

    /** dni = 1 (dziś), 7, 30 albo null (wszystko). */
    fun zestaw(c: Context, dni: Int?): Zestaw {
        val od = dni?.let { LocalDate.now().minusDays((it - 1).toLong()).toString() }
        val czasy = HashMap<String, Long>()
        val losy = HashMap<String, Long>()
        var rmf = 0L
        val daty = HashSet<String>()
        val stare = ArrayList<String>()
        val granica = LocalDate.now().minusDays(DNI_PAMIECI).toString()
        for ((k, v) in p(c).all) {
            val cz = k.split("|", limit = 3)
            if (cz.size < 2) continue
            val data = cz[1]
            if (data < granica) { stare.add(k); continue }
            if (od != null && data < od) continue
            val n = (v as? Long) ?: continue
            when (cz[0]) {
                "t" -> if (cz.size == 3) { czasy[cz[2]] = (czasy[cz[2]] ?: 0) + n; daty.add(data) }
                "l" -> if (cz.size == 3) losy[cz[2]] = (losy[cz[2]] ?: 0) + n
                "r" -> rmf += n
            }
        }
        if (stare.isNotEmpty()) p(c).edit().apply { stare.forEach { remove(it) } }.apply()
        val nazwy = czasy.keys + losy.keys
        val wiersze = nazwy.map { Wiersz(it, czasy[it] ?: 0, losy[it] ?: 0) }
            .sortedByDescending { it.sek }
        return Zestaw(wiersze, czasy.values.sum(), rmf, losy.values.sum(), daty.size)
    }

    fun czytelnie(sek: Long): String {
        val h = sek / 3600
        val m = (sek % 3600) / 60
        return when {
            h > 0 -> "$h h $m min"
            m > 0 -> "$m min"
            else -> "$sek s"
        }
    }
}
