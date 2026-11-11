package com.michal.radiotest

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.Locale

private val ZIELONY = Color(0xFF2E7D32)

class MainActivity : ComponentActivity() {
    private var kontroler: MediaController? = null
    private var stacja by mutableStateOf("")
    private var utwor by mutableStateOf("")
    private var status by mutableStateOf("Wybierz stację.")
    private var kluczDi by mutableStateOf("")
    private var bateriaOk by mutableStateOf(false)
    private val zgodaPowiadomienia =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val prefs by lazy { getSharedPreferences("ustawienia", MODE_PRIVATE) }
    private val magazyn by lazy { Magazyn(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            zgodaPowiadomienia.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        kluczDi = prefs.getString("klucz_di", "") ?: ""
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Ekran() }
            }
        }
    }

    // ---------- Ekran główny ----------

    @Composable
    private fun Ekran() {
        var zakladka by remember { mutableIntStateOf(0) }
        var edycja by remember { mutableStateOf<Stacja?>(null) }
        var nowa by remember { mutableStateOf(false) }
        var wybor by remember { mutableStateOf(false) }
        var doUsuniecia by remember { mutableStateOf<Stacja?>(null) }

        Scaffold(
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    if (zakladka == 0) nowa = true else wybor = true
                }) { Icon(Icons.Filled.Add, contentDescription = "Dodaj") }
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                Naglowek()
                TabRow(selectedTabIndex = zakladka) {
                    Tab(zakladka == 0, { zakladka = 0 }, text = { Text("Stacje") })
                    Tab(zakladka == 1, { zakladka = 1 }, text = { Text("Losowanie") })
                }
                if (zakladka == 0) ListaStacji(
                    onEdytuj = { edycja = it },
                    onUsun = { doUsuniecia = it }
                ) else ListaLosowania()
            }
        }

        if (nowa) OknoStacji(null, { nowa = false }) { n, a -> magazyn.dodaj(n, a) }
        edycja?.let { s ->
            OknoStacji(s, { edycja = null }) { n, a -> magazyn.zmien(s.id, n, a) }
        }
        doUsuniecia?.let { s ->
            AlertDialog(
                onDismissRequest = { doUsuniecia = null },
                title = { Text("Usunąć stację?") },
                text = { Text(s.nazwa) },
                confirmButton = {
                    TextButton(onClick = { magazyn.usun(s.id); doUsuniecia = null }) { Text("Usuń") }
                },
                dismissButton = { TextButton(onClick = { doUsuniecia = null }) { Text("Anuluj") } }
            )
        }
        if (wybor) OknoWyboru { wybor = false }
    }

    @Composable
    private fun Naglowek() {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(if (stacja.isEmpty()) "Nic nie gra" else "Gra: $stacja",
                style = MaterialTheme.typography.titleMedium)
            if (utwor.isNotEmpty()) Text(utwor, color = Color(0xFFFFEB3B),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (stacja.isNotEmpty()) TextButton(onClick = { stop() }) { Text("Stop") }
            }
        }
    }

    // ---------- Zakładka 1: stacje ----------

    @Composable
    private fun ListaStacji(onEdytuj: (Stacja) -> Unit, onUsun: (Stacja) -> Unit) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(magazyn.stacje, key = { it.id }) { s ->
                WierszStacji(
                    s,
                    opis = if (magazyn.wLosowaniu(s.id)) "w losowaniu" else "poza losowaniem"
                ) {
                    IconButton(onClick = { onEdytuj(s) }) { Icon(Icons.Filled.Edit, "Edytuj") }
                    IconButton(onClick = { onUsun(s) }) { Icon(Icons.Filled.Delete, "Usuń") }
                }
            }
            item { Ustawienia() }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    @Composable
    private fun WierszStacji(s: Stacja, opis: String, akcje: @Composable () -> Unit) {
        val gra = s.nazwa == stacja
        Card(
            colors = if (gra) CardDefaults.cardColors(containerColor = ZIELONY) else CardDefaults.cardColors(),
            modifier = Modifier.fillMaxWidth().clickable { graj(s) }
        ) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.nazwa, style = MaterialTheme.typography.titleMedium)
                    Text(opis, style = MaterialTheme.typography.bodySmall)
                }
                akcje()
            }
        }
    }

    @Composable
    private fun Ustawienia() {
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider()
            Text("Ustawienia", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = kluczDi, onValueChange = { kluczDi = it },
                label = { Text("Klucz DI.FM premium") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = {
                prefs.edit().putString("klucz_di", kluczDi.trim()).apply()
                status = "Klucz DI.FM zapisany."
            }) { Text("Zapisz klucz") }
            if (bateriaOk) Text("Bateria: bez ograniczeń ✓", color = Color(0xFF81C784))
            else OutlinedButton(onClick = { bateria() }) { Text("Ustaw baterię: bez ograniczeń") }
            Text("Wersja ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun OknoStacji(s: Stacja?, zamknij: () -> Unit, zapisz: (String, String) -> Unit) {
        var nazwa by remember { mutableStateOf(s?.nazwa ?: "") }
        var adres by remember { mutableStateOf(s?.adres ?: "") }
        val ok = nazwa.isNotBlank() && (adres.startsWith("http://") || adres.startsWith("https://"))
        AlertDialog(
            onDismissRequest = zamknij,
            title = { Text(if (s == null) "Nowa stacja" else "Edycja stacji") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(nazwa, { nazwa = it }, label = { Text("Nazwa") }, singleLine = true)
                    OutlinedTextField(adres, { adres = it.trim() }, label = { Text("Adres URL strumienia") })
                    Text("Adres zaczyna się od http:// albo https://. W adresach DI.FM zamiast klucza " +
                        "możesz wpisać $ZNACZNIK_DI.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = { zapisz(nazwa, adres); zamknij() }) { Text("Zapisz") }
            },
            dismissButton = { TextButton(onClick = zamknij) { Text("Anuluj") } }
        )
    }

    // ---------- Zakładka 2: losowanie ----------

    @Composable
    private fun ListaLosowania() {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Stacje, z których losowany jest kanał. Waga zmienia szansę wylosowania. " +
                    "Przycisk + dodaje stację z zakładki Stacje.", style = MaterialTheme.typography.bodySmall)
            }
            if (magazyn.losowanie.isEmpty()) item { Text("Brak stacji w losowaniu.") }
            items(magazyn.losowanie, key = { it.id }) { los ->
                val s = magazyn.stacja(los.id) ?: return@items
                WierszStacji(s, opis = "waga ${los.waga}, szansa " +
                    String.format(Locale("pl"), "%.1f%%", magazyn.procent(los))) {
                    TextButton(onClick = { magazyn.ustawWage(los.id, los.waga - 1) }) { Text("−") }
                    TextButton(onClick = { magazyn.ustawWage(los.id, los.waga + 1) }) { Text("+") }
                    IconButton(onClick = { magazyn.usunZLosowania(los.id) }) {
                        Icon(Icons.Filled.Close, "Usuń z losowania")
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    @Composable
    private fun OknoWyboru(zamknij: () -> Unit) {
        val wolne = magazyn.stacje.filter { !magazyn.wLosowaniu(it.id) }
        AlertDialog(
            onDismissRequest = zamknij,
            title = { Text("Dodaj do losowania") },
            text = {
                if (wolne.isEmpty()) Text("Wszystkie stacje już biorą udział w losowaniu.")
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    wolne.forEach { s ->
                        Text(s.nazwa, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { magazyn.dodajDoLosowania(s.id); zamknij() }
                                .padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = zamknij) { Text("Zamknij") } }
        )
    }

    // ---------- Odtwarzanie ----------

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        f.addListener({
            val c = f.get()
            kontroler = c
            c.addListener(sluchacz)
            odswiez(c)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        bateriaOk = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
    }

    override fun onStop() {
        kontroler?.removeListener(sluchacz)
        kontroler?.release()
        kontroler = null
        super.onStop()
    }

    private val sluchacz = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = odswiez(player)
        override fun onPlayerError(error: PlaybackException) {
            status = "Błąd: ${error.errorCodeName}"
        }
    }

    private fun odswiez(p: Player) {
        stacja = if (p.mediaItemCount > 0 && p.playbackState != Player.STATE_IDLE)
            p.currentMediaItem?.mediaMetadata?.station?.toString() ?: "" else ""
        utwor = p.mediaMetadata.title?.toString().orEmpty()
        if (stacja.isNotEmpty()) status = when (p.playbackState) {
            Player.STATE_BUFFERING -> "Łączę..."
            Player.STATE_READY -> if (p.isPlaying) "Gra." else "Pauza."
            else -> status
        }
    }

    private fun graj(s: Stacja) {
        if (s.adres.contains(ZNACZNIK_DI) && kluczDi.isBlank()) {
            status = "Najpierw wpisz i zapisz klucz DI.FM (na dole zakładki Stacje)."
            return
        }
        val c = kontroler ?: run { status = "Odtwarzacz jeszcze się uruchamia..."; return }
        val item = MediaItem.Builder()
            .setUri(s.adres.replace(ZNACZNIK_DI, kluczDi.trim()))
            .setMediaId(s.id)
            .setMediaMetadata(MediaMetadata.Builder().setStation(s.nazwa).setArtist(s.nazwa).build())
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
        stacja = s.nazwa
        utwor = ""
        status = "Łączę..."
    }

    private fun stop() {
        kontroler?.run { stop(); clearMediaItems() }
        stacja = ""
        utwor = ""
        status = "Zatrzymane."
    }

    private fun bateria() {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try { startActivity(i) } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
