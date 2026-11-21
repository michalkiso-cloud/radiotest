package com.michal.radiotest

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stacja radiowa. W adresie znacznik {KLUCZ_DI} zastępowany jest kluczem DI.FM. */
data class Stacja(val id: String, val nazwa: String, val adres: String)

/** Stacja biorąca udział w losowaniu, z wagą (domyślnie 4). */
data class Los(val id: String, val waga: Double)

const val ZNACZNIK_DI = "{KLUCZ_DI}"
// MP3 128 kb/s (wcześniej AAC+ 64 kb/s: https://rs201-krk.rmfstream.pl/RMF24)
const val RMF24 = "https://rs201-krk.rmfstream.pl/rmf_24"
/** Adres specjalnej stacji Spotify: gra playlistę wybraną w Ustawieniach (shuffle). */
const val SPOTIFY_ADRES = "spotify:wybrana"

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
                .map { Los(it.getString("id"), it.optDouble("waga", 4.0)) })
        }
        // 4.4: wagi stają się procentami – jednorazowo przeliczamy dotychczasowe proporcje na sumę 100
        if (!prefs.getBoolean("wagi_procent", false)) {
            val suma = losowanie.sumOf { it.waga }
            if (suma > 0) for (i in losowanie.indices) losowanie[i] = losowanie[i].let { Los(it.id, Math.round(it.waga / suma * 1000) / 10.0) }
            prefs.edit().putBoolean("wagi_procent", true).apply()
            zapisz()
        }
        if (!prefs.getBoolean("spotify_dodany", false)) {
            if (stacje.none { it.adres == SPOTIFY_ADRES }) stacje.add(Stacja(noweId(), "Spotify", SPOTIFY_ADRES))
            prefs.edit().putBoolean("spotify_dodany", true).apply()
            zapisz()
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
            if (w > 0) losowanie.add(Los(st.id, w.toDouble()))
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
        if (!wLosowaniu(id)) {
            // nowa stacja dostaje niewykorzystane procenty (albo 1%, gdy nic nie zostało)
            val w = niewykorzystane().takeIf { it >= 0.1 }?.let { Math.round(it * 10) / 10.0 } ?: 1.0
            losowanie.add(Los(id, w))
        }
        zapisz()
    }

    fun usunZLosowania(id: String) {
        losowanie.removeAll { it.id == id }; zapisz()
    }

    /** Ustawia szansę stacji w procentach (waga = procent). Pozostałe stacje się nie zmieniają;
     *  sumę do 100 pilnuje użytkownik (podgląd „niewykorzystane”). */
    fun ustawProcent(id: String, procent: Double) {
        val i = losowanie.indexOfFirst { it.id == id }
        if (i < 0) return
        losowanie[i] = Los(id, Math.round(procent.coerceIn(0.1, 100.0) * 10) / 10.0)
        zapisz()
    }

    /** Szansa w procentach = waga (od 4.4 wagi są procentami). */
    fun procent(id: String): Double = losowanie.firstOrNull { it.id == id }?.waga ?: 0.0

    /** 100 minus suma wag (ujemne = ponad 100). */
    fun niewykorzystane(): Double = 100.0 - losowanie.sumOf { it.waga }

    /** Lista ze strony (synchronizacja): stacje rozpoznawane po adresie, waga 0 = poza losowaniem. */
    /** Przyjęcie listy ze strony. Lokalne stacje, których adresu nie ma na liście (np. Spotify), zostają.
     *  Zwraca true, gdy coś zostało zachowane (trzeba odesłać scaloną listę). */
    fun zastap(lista: List<Triple<String, String, Double>>): Boolean {
        val stare = stacje.associateBy { it.adres }
        val adresy = lista.map { it.second }.toSet()
        val wagi = losowanie.associate { it.id to it.waga }
        val zostaja = stacje.filter { it.adres !in adresy }
        val nowe = lista.map { (n, a, _) -> Stacja(stare[a]?.id ?: noweId(), n, a) }
        stacje.clear(); stacje.addAll(nowe); stacje.addAll(zostaja)
        losowanie.clear()
        lista.forEachIndexed { i, (_, _, w) -> if (w > 0) losowanie.add(Los(nowe[i].id, w)) }
        zostaja.forEach { s -> wagi[s.id]?.let { if (it > 0) losowanie.add(Los(s.id, it)) } }
        zapisz()
        return zostaja.isNotEmpty()
    }

    private fun zapisz() {
        val a = JSONArray()
        stacje.forEach { a.put(JSONObject().put("id", it.id).put("nazwa", it.nazwa).put("adres", it.adres)) }
        val l = JSONArray()
        losowanie.forEach { l.put(JSONObject().put("id", it.id).put("waga", it.waga)) }
        prefs.edit().putString("stacje", a.toString()).putString("losowanie", l.toString()).apply()
    }
}
