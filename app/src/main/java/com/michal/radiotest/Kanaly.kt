package com.michal.radiotest

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stacja radiowa. W adresie znacznik {KLUCZ_DI} zastępowany jest kluczem DI.FM. */
data class Stacja(val id: String, val nazwa: String, val adres: String)

/** Stacja biorąca udział w losowaniu, z wagą (domyślnie 4). */
data class Los(val id: String, val waga: Int)

const val ZNACZNIK_DI = "{KLUCZ_DI}"
const val RMF24 = "https://rs201-krk.rmfstream.pl/RMF24"

/** Lista stacji i losowania zapisywana w telefonie. */
class Magazyn(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("stacje", Context.MODE_PRIVATE)
    val stacje = mutableStateListOf<Stacja>()
    val losowanie = mutableStateListOf<Los>()

    init {
        val s = prefs.getString("stacje", null)
        if (s == null) domyslne() else {
            stacje.addAll(JSONArray(s).let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }
                    .map { Stacja(it.getString("id"), it.getString("nazwa"), it.getString("adres")) }
            })
            val l = JSONArray(prefs.getString("losowanie", "[]"))
            losowanie.addAll((0 until l.length()).map { l.getJSONObject(it) }
                .map { Los(it.getString("id"), it.optInt("waga", 4)) })
        }
    }

    private fun domyslne() {
        val lista = listOf(
            Triple("Disco Radio", "https://n44a-eu.rcs.revma.com/cvswvmyewzzuv", 4),
            Triple("EDM", "http://prem2.di.fm:80/mainstage?$ZNACZNIK_DI", 3),
            Triple("FTB Club", "https://play.radioftb.net:8000/stream", 3),
            Triple("Eska2", "https://waw.ic.smcdn.pl/1380-1.mp3", 4),
            Triple("Radio Cmp3", "https://s.radiocmp3.eu/stream256", 3),
            Triple("Club Sounds", "http://prem2.di.fm:80/club?$ZNACZNIK_DI", 3),
            Triple("RMF24", RMF24, 0),
        )
        lista.forEach { (n, a, w) ->
            val st = Stacja(noweId(), n, a)
            stacje.add(st)
            if (w > 0) losowanie.add(Los(st.id, w))
        }
        zapisz()
    }

    private fun noweId() = UUID.randomUUID().toString()

    fun stacja(id: String) = stacje.firstOrNull { it.id == id }
    fun wLosowaniu(id: String) = losowanie.any { it.id == id }

    fun dodaj(nazwa: String, adres: String) {
        stacje.add(Stacja(noweId(), nazwa.trim(), adres.trim())); zapisz()
    }

    fun zmien(id: String, nazwa: String, adres: String) {
        val i = stacje.indexOfFirst { it.id == id }
        if (i >= 0) stacje[i] = Stacja(id, nazwa.trim(), adres.trim())
        zapisz()
    }

    fun usun(id: String) {
        stacje.removeAll { it.id == id }
        losowanie.removeAll { it.id == id }
        zapisz()
    }

    fun dodajDoLosowania(id: String) {
        if (!wLosowaniu(id)) losowanie.add(Los(id, 4)); zapisz()
    }

    fun usunZLosowania(id: String) {
        losowanie.removeAll { it.id == id }; zapisz()
    }

    fun ustawWage(id: String, waga: Int) {
        val i = losowanie.indexOfFirst { it.id == id }
        if (i >= 0) losowanie[i] = Los(id, waga.coerceIn(1, 20))
        zapisz()
    }

    fun procent(los: Los): Float {
        val suma = losowanie.sumOf { it.waga }
        return if (suma == 0) 0f else 100f * los.waga / suma
    }

    private fun zapisz() {
        val a = JSONArray()
        stacje.forEach { a.put(JSONObject().put("id", it.id).put("nazwa", it.nazwa).put("adres", it.adres)) }
        val l = JSONArray()
        losowanie.forEach { l.put(JSONObject().put("id", it.id).put("waga", it.waga)) }
        prefs.edit().putString("stacje", a.toString()).putString("losowanie", l.toString()).apply()
    }
}
