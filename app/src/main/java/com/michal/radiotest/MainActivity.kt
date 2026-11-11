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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.delay
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
    private var motyw by mutableIntStateOf(0)        // 0 system, 1 jasny, 2 ciemny
    private var trybAuto by mutableStateOf(false)
    private var autoMin by mutableIntStateOf(0)
    private var losujeTeraz by mutableStateOf(false)
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
        motyw = prefs.getInt("motyw", 0)
        trybAuto = StanLosowania.trybAuto(this)
        setContent {
            val ciemny = when (motyw) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            MaterialTheme(colorScheme = if (ciemny) darkColorScheme() else lightColorScheme()) {
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
    private fun WyborMotywu() {
        var otwarte by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { otwarte = true }) {
                Text("◐", style = MaterialTheme.typography.headlineSmall)
            }
            DropdownMenu(expanded = otwarte, onDismissRequest = { otwarte = false }) {
                listOf("Systemowy", "Jasny", "Ciemny").forEachIndexed { i, n ->
                    DropdownMenuItem(
                        text = { Text((if (motyw == i) "✓ " else "    ") + n) },
                        onClick = {
                            motyw = i
                            prefs.edit().putInt("motyw", i).apply()
                            otwarte = false
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun Naglowek() {
        LaunchedEffect(Unit) {
            while (true) {
                autoMin = StanLosowania.autoSek(this@MainActivity) / 60
                losujeTeraz = StanLosowania.losujeTeraz(this@MainActivity)
                delay(5_000)
            }
        }
        val ciemny = MaterialTheme.colorScheme.background.red < 0.5f
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Radio", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                WyborMotywu()
            }
            Text(if (stacja.isEmpty()) "Nic nie gra" else "Gra: $stacja",
                style = MaterialTheme.typography.titleMedium)
            if (utwor.isNotEmpty()) Text(utwor, color = if (ciemny) Color(0xFFFFEB3B) else Color(0xFF8D6E00),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (stacja.isNotEmpty()) TextButton(onClick = { stop() }) { Text("Stop") }
            }
            if (trybAuto && losujeTeraz && stacja.isNotEmpty()) Text(
                "Auto: wykorzystane $autoMin min z ${AUTO_CZAS_S / 60}",
                color = if (ciemny) Color(0xFFF48FB1) else Color(0xFFAD1457),
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { losuj() }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp)) {
                Text(if (trybAuto) "Graj losowo (tryb auto)" else "Graj losowo (stacja dnia)")
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
                    StartStop(s)
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
    private fun StartStop(s: Stacja) {
        if (s.nazwa == stacja) {
            IconButton(onClick = { stop() }) {
                Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.onSurface))
            }
        } else {
            IconButton(onClick = { graj(s) }) { Icon(Icons.Filled.PlayArrow, "Start") }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !trybAuto, onClick = { ustawTryb(false) },
                        label = { Text("Raz dziennie") })
                    FilterChip(selected = trybAuto, onClick = { ustawTryb(true) },
                        label = { Text("Auto: zmiana co ${AUTO_CZAS_S / 60} min") })
                }
            }
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
        c.sendCustomCommand(SessionCommand(KOMENDA_GRAJ, Bundle.EMPTY), Bundle().apply { putString("id", s.id) })
        losujeTeraz = false
        stacja = s.nazwa
        utwor = ""
        status = "Łączę..."
    }

    private fun losuj() {
        if (magazyn.losowanie.isEmpty()) { status = "Dodaj stacje w zakładce Losowanie."; return }
        val c = kontroler ?: run { status = "Odtwarzacz jeszcze się uruchamia..."; return }
        c.sendCustomCommand(SessionCommand(KOMENDA_LOSUJ, Bundle.EMPTY), Bundle.EMPTY)
        losujeTeraz = true
        utwor = ""
        status = "Łączę..."
    }

    private fun ustawTryb(auto: Boolean) {
        trybAuto = auto
        StanLosowania.ustawAuto(this, auto)
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
