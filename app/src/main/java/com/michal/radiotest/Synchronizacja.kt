package com.michal.radiotest

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Synchronizacja ustawień telefon ↔ strona przez schowek w workerze Cloudflare.
 * Format: /mnt/project-files/radio-web/synchronizacja.md (wygrywa nowszy „zmiana”, stacje po adresie).
 */
object Synchro {
    private const val ADRES = "https://radio.michalkiso.workers.dev/ust"
    private val glowny = Handler(Looper.getMainLooper())
    val stan = mutableStateOf("")
    /** Wywoływane po przyjęciu ustawień ze strony (np. odświeżenie ekranu). */
    var poPrzyjeciu: (() -> Unit)? = null

    private lateinit var app: Context
    private val watek = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var uruchomione = false
    @Volatile private var przyjmuje = false

    private fun p() = app.getSharedPreferences("synchro", Context.MODE_PRIVATE)
    fun kod(c: Context) = c.getSharedPreferences("synchro", Context.MODE_PRIVATE).getString("kod", "") ?: ""
    fun ustawKod(c: Context, k: String) {
        c.getSharedPreferences("synchro", Context.MODE_PRIVATE).edit().putString("kod", k.trim()).apply()
        teraz()
    }

    // klucze, których zmiana jest „zmianą ustawień” (nie liczniki odtwarzania)
    private val sledzone = mapOf(
        "stacje" to null,
        "mowa" to null,
        "glosnosc" to null,
        "ustawienia" to setOf("klucz_di"),
        "spotify" to setOf("uri", "nazwa"),
        "losowanie_stan" to setOf("auto", "auto_min", "wzgledny", "dzien_data", "dzien_id"),
    )
    private val sluchacz = SharedPreferences.OnSharedPreferenceChangeListener { sp, klucz ->
        if (przyjmuje) return@OnSharedPreferenceChangeListener
        val plik = sledzone.entries.firstOrNull { app.getSharedPreferences(it.key, Context.MODE_PRIVATE) === sp } ?: return@OnSharedPreferenceChangeListener
        if (plik.value != null && klucz !in plik.value!!) return@OnSharedPreferenceChangeListener
        p().edit().putLong("zmiana", System.currentTimeMillis()).apply()
        glowny.removeCallbacks(wyslijPozniej)
        glowny.postDelayed(wyslijPozniej, 3000)
    }
    private val wyslijPozniej = Runnable { teraz() }
    private val cykl = object : Runnable {
        override fun run() { teraz(); glowny.postDelayed(this, 5 * 60_000L) }
    }

    /** Start (wielokrotne wywołanie nic nie psuje). */
    fun start(c: Context) {
        if (uruchomione) return
        uruchomione = true
        app = c.applicationContext
        sledzone.keys.forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(sluchacz) }
        glowny.postDelayed(cykl, 5 * 60_000L)
    }

    /** Pobranie i porównanie: nowsze wygrywa. */
    fun teraz() {
        if (!uruchomione) return
        val kod = kod(app)
        if (kod.isBlank()) { stan.value = "wpisz kod synchronizacji"; return }
        watek.execute {
            val wynik = runCatching {
                val zdalne = pobierz(kod)
                zdalne?.let { przyjmijAuto(it) }
                val lokalnaZmiana = p().getLong("zmiana", 0L)
                val zdalnaZmiana = zdalne?.optLong("zmiana", 0L) ?: 0L
                when {
                    zdalne == null || zdalnaZmiana < lokalnaZmiana || (zdalnaZmiana == 0L) -> {
                        val o = zbierz()
                        // pola, których telefon nie zna, zostają w schowku
                        zdalne?.keys()?.forEach { k -> if (!o.has(k)) o.put(k, zdalne.get(k)) }
                        wyslij(kod, o); "wysłano"
                    }
                    zdalnaZmiana > lokalnaZmiana -> {
                        glowny.post {
                            val scalono = zastosuj(zdalne)
                            // zachowane lokalne stacje (np. Spotify): odeślij scaloną listę
                            p().edit().putLong("zmiana", if (scalono) System.currentTimeMillis() else zdalnaZmiana).apply()
                            if (scalono) glowny.postDelayed({ teraz() }, 1500)
                            poPrzyjeciu?.invoke()
                        }
                        "pobrano ze strony"
                    }
                    else -> "zgodne"
                }
            }
            val g = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            stan.value = wynik.fold({ "$g $it ✓" }, { "$g błąd: ${it.message}" })
        }
    }

    // ---------- Tryb auto: wspólna stacja i wykorzystany czas (pole „auto”, własny czas „kiedy”) ----------

    /** Przyjęcie „auto” ze schowka, gdy nowsze niż lokalne. Zwraca true, gdy przyjęto. */
    private fun przyjmijAuto(o: JSONObject): Boolean {
        val a = o.optJSONObject("auto") ?: return false
        val kiedy = a.optLong("kiedy", 0L)
        if (kiedy <= p().getLong("auto_kiedy", 0L)) return false
        val s = Magazyn(app).stacje.firstOrNull { it.adres == a.optString("adres") } ?: return false
        StanLosowania.prefs(app).edit().putString("auto_id", s.id).putInt("auto_sek", a.optInt("sek", 0)).commit()
        p().edit().putLong("auto_kiedy", kiedy).commit()
        return true
    }

    /** Wysłanie bieżącego stanu auto (kiedy = teraz). Najpierw GET i przyjęcie nowszych danych. */
    fun wyslijAuto() {
        if (!uruchomione || kod(app).isBlank() || !StanLosowania.trybAuto(app)) return
        val kod = kod(app)
        watek.execute {
            runCatching {
                val zdalne = pobierz(kod)
                val lokalnaZmiana = p().getLong("zmiana", 0L)
                val zdalnaZmiana = zdalne?.optLong("zmiana", 0L) ?: 0L
                if (zdalne != null && zdalnaZmiana > lokalnaZmiana) {
                    val gotowe = java.util.concurrent.CountDownLatch(1)
                    glowny.post {
                        val scalono = zastosuj(zdalne)
                        p().edit().putLong("zmiana", if (scalono) System.currentTimeMillis() else zdalnaZmiana).commit()
                        poPrzyjeciu?.invoke()
                        gotowe.countDown()
                    }
                    gotowe.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                // własny stan auto jest najnowszy (kiedy = teraz), więc zdalnego nie przyjmujemy
                p().edit().putLong("auto_kiedy", System.currentTimeMillis()).commit()
                val o = zbierz()
                zdalne?.keys()?.forEach { k -> if (!o.has(k)) o.put(k, zdalne.get(k)) }
                wyslij(kod, o)
            }
        }
    }

    /** „Graj losowo” w trybie auto: najpierw pobierz stan auto ze schowka (maks. ~3 s), potem [dalej] na wątku głównym. */
    fun pobierzAuto(dalej: () -> Unit) {
        if (!uruchomione || kod(app).isBlank() || !StanLosowania.trybAuto(app)) { dalej(); return }
        val kod = kod(app)
        var zrobione = false
        val raz = Runnable { if (!zrobione) { zrobione = true; dalej() } }
        glowny.postDelayed(raz, 3000)
        watek.execute {
            runCatching { pobierz(kod)?.let { przyjmijAuto(it) } }
            glowny.post { glowny.removeCallbacks(raz); raz.run() }
        }
    }

    private fun pobierz(kod: String): JSONObject? {
        val c = URL("$ADRES?t=$kod").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        if (c.responseCode == 501) throw Exception("schowek niepodpięty")
        if (c.responseCode != 200) throw Exception("serwer ${c.responseCode}")
        val t = c.inputStream.bufferedReader().use { it.readText() }
        val o = JSONObject(t.ifBlank { "{}" })
        return if (o.length() == 0) null else o
    }

    private fun wyslij(kod: String, o: JSONObject) {
        val c = URL("$ADRES?t=$kod").openConnection() as HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(o.toString().toByteArray()) }
        if (c.responseCode !in 200..299) throw Exception("zapis ${c.responseCode}")
    }

    /** Stan telefonu w formacie wspólnym ze stroną. */
    private fun zbierz(): JSONObject {
        val m = Magazyn(app)
        val los = StanLosowania.prefs(app)
        val stacje = JSONArray()
        m.stacje.forEach { s ->
            val w = m.losowanie.firstOrNull { it.id == s.id }?.waga ?: 0.0
            stacje.put(JSONObject().put("nazwa", s.nazwa).put("adres", s.adres).put("waga", w))
        }
        val kor = JSONObject()
        m.stacje.forEach { s -> UstGlosnosci.korekta(app, s.id).let { if (it != 0f) kor.put(s.adres, it.toDouble()) } }
        kor.put("rmf24", UstGlosnosci.korekta(app, UstGlosnosci.ID_RMF).toDouble())
        val o = JSONObject()
            .put("wersja", 1)
            .put("zmiana", p().getLong("zmiana", System.currentTimeMillis()).takeIf { it > 0 } ?: System.currentTimeMillis())
            .put("zrodlo", "telefon")
            .put("stacje", stacje)
            .put("kluczDi", app.getSharedPreferences("ustawienia", Context.MODE_PRIVATE).getString("klucz_di", "") ?: "")
            .put("trybAuto", StanLosowania.trybAuto(app))
            .put("autoMin", StanLosowania.autoMin(app))
            .put("wzgledny", StanLosowania.wzgledny(app))
            .put("mowa", JSONObject().put("wl", UstMowy.wlaczone(app)).put("margPocz", UstMowy.margPocz(app).toDouble())
                .put("margKon", UstMowy.margKon(app).toDouble()).put("przejscie", UstMowy.czasPrzejscia(app).toDouble()))
            .put("glos", JSONObject().put("wl", UstGlosnosci.wlaczone(app)).put("cel", UstGlosnosci.cel(app).toDouble())
                .put("maks", UstGlosnosci.maks(app).toDouble()).put("kor", kor))
        o.put("spotify", SpotifyUst.wybrana(app)?.let { JSONObject().put("uri", it.uri).put("nazwa", it.nazwa) } ?: JSONObject.NULL)
        val autoId = los.getString("auto_id", null)
        val autoAdres = autoId?.let { m.stacja(it)?.adres }
        val autoKiedy = p().getLong("auto_kiedy", 0L)
        if (autoAdres != null && autoKiedy > 0)
            o.put("auto", JSONObject().put("adres", autoAdres).put("sek", los.getInt("auto_sek", 0)).put("kiedy", autoKiedy))
        val dzienId = los.getString("dzien_id", null)
        val dzienAdres = dzienId?.let { m.stacja(it)?.adres }
        if (dzienAdres != null) o.put("dzien", JSONObject().put("data", los.getString("dzien_data", "")).put("adres", dzienAdres))
        return o
    }

    /** Przyjęcie ustawień ze strony (wątek główny). */
    private fun zastosuj(o: JSONObject): Boolean {
        przyjmuje = true
        var scalono = false
        try {
            val m = Magazyn(app)
            o.optJSONArray("stacje")?.let { a ->
                val lista = (0 until a.length()).map { a.getJSONObject(it) }
                    .map { Triple(it.optString("nazwa"), it.optString("adres"), it.optDouble("waga", 0.0)) }
                    .filter { it.second.isNotBlank() }
                if (lista.isNotEmpty()) scalono = m.zastap(lista)
            }
            if (o.has("kluczDi")) app.getSharedPreferences("ustawienia", Context.MODE_PRIVATE).edit()
                .putString("klucz_di", o.optString("kluczDi")).apply()
            if (o.has("trybAuto")) StanLosowania.ustawAuto(app, o.optBoolean("trybAuto"))
            if (o.has("autoMin")) StanLosowania.ustawAutoMin(app, o.optInt("autoMin", 60))
            if (o.has("wzgledny")) StanLosowania.ustawWzgledny(app, o.optBoolean("wzgledny", true))
            o.optJSONObject("mowa")?.let {
                if (it.has("wl")) UstMowy.ustaw(app, it.optBoolean("wl", true))
                if (it.has("margPocz")) UstMowy.ustawMargPocz(app, it.optDouble("margPocz").toFloat())
                if (it.has("margKon")) UstMowy.ustawMargKon(app, it.optDouble("margKon").toFloat())
                if (it.has("przejscie")) UstMowy.ustawCzasPrzejscia(app, it.optDouble("przejscie").toFloat())
            }
            val m2 = Magazyn(app)
            o.optJSONObject("glos")?.let { g ->
                if (g.has("wl")) UstGlosnosci.ustawWlaczone(app, g.optBoolean("wl", true))
                if (g.has("cel")) UstGlosnosci.ustawCel(app, g.optDouble("cel").toFloat())
                if (g.has("maks")) UstGlosnosci.ustawMaks(app, g.optDouble("maks").toFloat())
                g.optJSONObject("kor")?.let { k ->
                    val zdalneAdresy = o.optJSONArray("stacje")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("adres") }.toSet() }
                    m2.stacje.filter { zdalneAdresy == null || it.adres in zdalneAdresy || k.has(it.adres) }
                        .forEach { s -> UstGlosnosci.ustawKorekte(app, s.id, k.optDouble(s.adres, 0.0).toFloat()) }
                    UstGlosnosci.ustawKorekte(app, UstGlosnosci.ID_RMF, k.optDouble("rmf24", 0.0).toFloat())
                }
            }
            o.optJSONObject("spotify")?.let { sp ->
                val uri = sp.optString("uri")
                val obecna = SpotifyUst.wybrana(app)
                if (uri.isNotBlank() && uri != obecna?.uri)
                    SpotifyUst.wybierz(app, Playlista(uri, sp.optString("nazwa"), 0))   // liczbę utworów telefon odświeży
            }
            o.optJSONObject("dzien")?.let { d ->
                val s = m2.stacje.firstOrNull { it.adres == d.optString("adres") }
                if (s != null && d.optString("data").isNotBlank())
                    StanLosowania.prefs(app).edit().putString("dzien_data", d.optString("data")).putString("dzien_id", s.id).apply()
            }
        } finally {
            // słuchacz zmian odpala się asynchronicznie po apply(): odblokuj chwilę później
            glowny.postDelayed({ przyjmuje = false }, 500)
        }
        return scalono
    }
}
