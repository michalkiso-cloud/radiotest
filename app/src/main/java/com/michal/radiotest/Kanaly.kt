package com.michal.radiotest

/** Kanał radia głównego. `diFm` = adres wymaga klucza premium DI.FM (dopisywany na końcu). */
data class Kanal(val nazwa: String, val adres: String, val diFm: Boolean = false)

object Kanaly {
    val lista = listOf(
        Kanal("Disco Radio", "https://n44a-eu.rcs.revma.com/cvswvmyewzzuv"),
        Kanal("EDM", "http://prem2.di.fm:80/mainstage?", diFm = true),
        Kanal("FTB Club", "https://play.radioftb.net:8000/stream"),
        Kanal("Eska2", "https://waw.ic.smcdn.pl/1380-1.mp3"),
        Kanal("Radio Cmp3", "https://s.radiocmp3.eu/stream256"),
        Kanal("Club Sounds", "http://prem2.di.fm:80/club?", diFm = true),
    )

    const val RMF24 = "https://rs201-krk.rmfstream.pl/RMF24"

    fun adres(k: Kanal, kluczDi: String): String =
        if (k.diFm) k.adres + kluczDi.trim() else k.adres
}
