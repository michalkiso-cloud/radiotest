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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.compose.runtime.mutableStateListOf
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.delay
import java.util.Locale

private val ZIELONY = Color(0xFF2E7D32)
private const val KOD_SPOTIFY = 1337

private fun procentTekst(p: Double) = String.format(Locale("pl"), "%.1f%%", p)

class MainActivity : ComponentActivity() {
    private var kontroler: MediaController? = null
    private var stacja by mutableStateOf("")
    private var utwor by mutableStateOf("")
    private var status by mutableStateOf("Wybierz stację.")
    private var graTeraz by mutableStateOf(false)
    private var kluczDi by mutableStateOf("")
    private var bateriaOk by mutableStateOf(false)
    private var motyw by mutableIntStateOf(0)        // 0 system, 1 jasny, 2 ciemny
    private var paleta by mutableIntStateOf(0)       // Motywy.nazwy
    private var trybAuto by mutableStateOf(false)
    private var autoMin by mutableIntStateOf(0)
    private var autoLimit by mutableIntStateOf(60)
    private var wzgledny by mutableStateOf(true)
    private var losujeTeraz by mutableStateOf(false)
    private var spotifyPolaczone by mutableStateOf(false)
    private var playlista by mutableStateOf<Playlista?>(null)
    private val playlisty = mutableStateListOf<Playlista>()
    private var pobieranie by mutableStateOf(false)
    private var pobierzPoZgodzie = false
    private var autostart by mutableStateOf(false)
    private var autostartWykonany = false
    private val zgodaPowiadomienia =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val prefs by lazy { getSharedPreferences("ustawienia", MODE_PRIVATE) }
    private val magazyn by lazy { Magazyn(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            zgodaPowiadomienia.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Synchro.start(this)
        Synchro.poPrzyjeciu = { if (!isFinishing) recreate() }
        kluczDi = prefs.getString("klucz_di", "") ?: ""
        motyw = prefs.getInt("motyw", 0)
        paleta = prefs.getInt("paleta", 0)
        trybAuto = StanLosowania.trybAuto(this)
        autoLimit = StanLosowania.autoMin(this)
        wzgledny = StanLosowania.wzgledny(this)
        spotifyPolaczone = SpotifyUst.polaczone(this)
        autostart = prefs.getBoolean("autostart", false)
        autostartWykonany = savedInstanceState != null
        playlista = SpotifyUst.wybrana(this)
        setContent {
            val ciemny = when (motyw) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            val schemat = Motywy.schemat(paleta, ciemny)
            SideEffect {
                // tryb jasny: ciemne ikony i napisy na pasku stanu
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !ciemny
                    isAppearanceLightNavigationBars = !ciemny
                }
            }
            MaterialTheme(colorScheme = schemat) {
                Surface(Modifier.fillMaxSize()) { Ekran() }
            }
        }
    }

    // ---------- Ekran główny ----------

    private var pokazLog by mutableStateOf(false)

    @Composable
    private fun Ekran() {
        BackHandler(enabled = pokazLog) { pokazLog = false }
        var zakladka by remember { mutableIntStateOf(0) }
        var edycja by remember { mutableStateOf<Stacja?>(null) }
        var nowa by remember { mutableStateOf(false) }
        var wybor by remember { mutableStateOf(false) }
        var szansa by remember { mutableStateOf<Stacja?>(null) }
        var doUsuniecia by remember { mutableStateOf<Stacja?>(null) }

        Scaffold { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                // u góry zakładki (albo log), na dole panel ze stacją i sterowaniem – pod kciukiem
                if (!pokazLog) TabRow(selectedTabIndex = zakladka) {
                    Tab(zakladka == 0, { zakladka = 0 }, text = { Text("Stacje") })
                    Tab(zakladka == 1, { zakladka = 1 }, text = { Text("Losowanie") })
                    Tab(zakladka == 2, { zakladka = 2 }, text = { Text("Mowa") })
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (pokazLog) EkranLogu()
                    else when (zakladka) {
                        0 -> ListaStacji(onEdytuj = { edycja = it }, onUsun = { doUsuniecia = it })
                        1 -> ZakladkaLosowania(onSzansa = { szansa = it }, onDodaj = { wybor = true })
                        else -> ZakladkaMowy()
                    }
                    if (zakladka == 0 && !pokazLog) FloatingActionButton(
                        onClick = { nowa = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                    ) { Icon(Icons.Filled.Add, contentDescription = "Dodaj stację") }
                }
                HorizontalDivider()
                Naglowek()
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
        szansa?.let { s -> OknoSzansy(s) { szansa = null } }
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
                HorizontalDivider()
                Motywy.nazwy.forEachIndexed { i, n ->
                    DropdownMenuItem(
                        text = { Text((if (paleta == i) "✓ " else "    ") + n) },
                        onClick = {
                            paleta = i
                            prefs.edit().putInt("paleta", i).apply()
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
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (stacja.isEmpty()) "Nic nie gra" else stacja,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                        fontWeight = FontWeight.Bold)
                    if (utwor.isNotEmpty() && !LogMowy.graRmf.value) Text(utwor, fontSize = 17.sp,
                        fontWeight = if (ciemny) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (ciemny) Color(0xFFFFEB3B) else MaterialTheme.colorScheme.primary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                WyborMotywu()
            }
            if (LogMowy.graRmf.value) Text("Teraz gra RMF24 (mowa)",
                color = if (ciemny) Color(0xFF81C784) else Color(0xFF2E7D32),
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    if (trybAuto && losujeTeraz && stacja.isNotEmpty()) Text(
                        "Auto: wykorzystane $autoMin min z $autoLimit",
                        color = if (ciemny) Color(0xFFF48FB1) else Color(0xFFAD1457),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!pokazLog) Text("Log", style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { pokazLog = true }
                        .padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 2.dp))
            }
            if (stacja.isNotEmpty() || pokazLog) HorizontalDivider(
                Modifier.padding(end = 12.dp, top = 6.dp),
                thickness = 2.dp, color = MaterialTheme.colorScheme.primary
            )
            Column(
                Modifier.fillMaxWidth().padding(end = 12.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(onClick = { losuj() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Graj losowo")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pauza() }, enabled = stacja.isNotEmpty(),
                        modifier = Modifier.weight(1f)) {
                        if (graTeraz) {
                            val k = LocalContentColor.current
                            Box(Modifier.size(width = 4.dp, height = 14.dp).background(k))
                            Spacer(Modifier.width(4.dp))
                            Box(Modifier.size(width = 4.dp, height = 14.dp).background(k))
                            Spacer(Modifier.width(10.dp))
                            Text("Pauza")
                        } else {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Wznów")
                        }
                    }
                    OutlinedButton(onClick = { stop() }, enabled = stacja.isNotEmpty(),
                        modifier = Modifier.weight(1f)) {
                        Box(Modifier.size(14.dp).background(LocalContentColor.current))
                        Spacer(Modifier.width(10.dp))
                        Text("Stop")
                    }
                }
            }
        }
    }

    // ---------- Zakładka 1: stacje ----------

    @Composable
    private fun ListaStacji(onEdytuj: (Stacja) -> Unit, onUsun: (Stacja) -> Unit) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(magazyn.stacje, key = { it.id }) { s ->
                val gra = s.nazwa == stacja
                Card(
                    colors = if (gra) CardDefaults.cardColors(containerColor = ZIELONY, contentColor = Color.White)
                        else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                    modifier = Modifier.fillMaxWidth().clickable { graj(s) }
                ) {
                    Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.nazwa, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            val opis = if (magazyn.wLosowaniu(s.id)) "w losowaniu" else "poza losowaniem"
                            Text(if (s.adres == SPOTIFY_ADRES)
                                "${playlista?.nazwa ?: "playlista niewybrana"} (shuffle), $opis" else opis,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (gra) IconButton(onClick = { stop() }) {
                            Box(Modifier.size(16.dp).background(Color.White))
                        } else IconButton(onClick = { graj(s) }) { Icon(Icons.Filled.PlayArrow, "Start") }
                        if (s.adres != SPOTIFY_ADRES)
                            IconButton(onClick = { onEdytuj(s) }) { Icon(Icons.Filled.Edit, "Edytuj") }
                        IconButton(onClick = { onUsun(s) }) { Icon(Icons.Filled.Delete, "Usuń") }
                    }
                }
            }
            item { UstawieniaOgolne() }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    @Composable
    private fun UstawieniaOgolne() {
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider()
            Text("Ustawienia", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            UstawieniaSpotify()
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
            SekcjaSynchro()
            if (bateriaOk) Text("Bateria: bez ograniczeń ✓", color = Color(0xFF81C784))
            else OutlinedButton(onClick = { bateria() }) { Text("Ustaw baterię: bez ograniczeń") }
            Text("Wersja ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun SekcjaSynchro() {
        var kod by remember { mutableStateOf(Synchro.kod(this@MainActivity)) }
        Text("Synchronizacja ze stroną", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = kod, onValueChange = { kod = it.trim() },
            label = { Text("Kod synchronizacji") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Synchro.ustawKod(this@MainActivity, kod) }) { Text("Zapisz i synchronizuj") }
            Text(Synchro.stan.value, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun UstawieniaSpotify() {
        var otwarte by remember { mutableStateOf(false) }
        Text("Spotify", style = MaterialTheme.typography.titleSmall)
        if (!spotifyPolaczone) {
            OutlinedButton(onClick = { pobierzPoZgodzie = true; zgodaSpotify() }) { Text("Połącz ze Spotify") }
            return
        }
        Box {
            OutlinedButton(
                onClick = {
                    otwarte = true
                    if (playlisty.isEmpty()) pobierzPlaylisty()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Playlista: ${playlista?.nazwa ?: "wybierz"}  ▾",
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = otwarte, onDismissRequest = { otwarte = false }) {
                if (pobieranie) DropdownMenuItem(text = { Text("Pobieram listę...") }, onClick = {})
                playlisty.forEach { pl ->
                    DropdownMenuItem(
                        text = { Text((if (pl.uri == playlista?.uri) "✓ " else "") + pl.nazwa) },
                        onClick = {
                            SpotifyUst.wybierz(this@MainActivity, pl)
                            playlista = pl
                            otwarte = false
                        }
                    )
                }
            }
        }
        TextButton(onClick = { playlisty.clear(); pobierzPlaylisty() }) { Text("Odśwież listę playlist") }
    }

    private fun zgodaSpotify() {
        val req = AuthorizationRequest.Builder(CLIENT_ID, AuthorizationResponse.Type.TOKEN, REDIRECT_URI)
            .setScopes(SPOTIFY_ZAKRESY).build()
        AuthorizationClient.openLoginActivity(this, KOD_SPOTIFY, req)
    }

    @Deprecated("Spotify SDK zwraca wynik przez onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != KOD_SPOTIFY) return
        val r = AuthorizationClient.getResponse(resultCode, data)
        when (r.type) {
            AuthorizationResponse.Type.TOKEN -> {
                SpotifyUst.zapiszToken(this, r.accessToken, r.expiresIn)
                SpotifyUst.ustawPolaczone(this)
                spotifyPolaczone = true
                status = "Połączono ze Spotify."
                if (pobierzPoZgodzie) { pobierzPoZgodzie = false; pobierzPlaylisty() }
            }
            AuthorizationResponse.Type.ERROR -> status = "Spotify: błąd zgody (${r.error})"
            else -> status = "Spotify: zgoda przerwana."
        }
    }

    private fun pobierzPlaylisty() {
        val token = SpotifyUst.token(this) ?: run { pobierzPoZgodzie = true; zgodaSpotify(); return }
        pobieranie = true
        Thread {
            val wynik = runCatching { SpotifyUst.pobierzPlaylisty(token) }
            runOnUiThread {
                pobieranie = false
                wynik.onSuccess { playlisty.clear(); playlisty.addAll(it); status = "Playlist: ${it.size}" }
                    .onFailure { status = "Spotify: ${it.message}" }
            }
        }.start()
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
    private fun Pigulka(tekst: String, wyrozniona: Boolean = false, onClick: () -> Unit) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = if (wyrozniona) ZIELONY else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (wyrozniona) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Text(tekst, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ZakladkaLosowania(onSzansa: (Stacja) -> Unit, onDodaj: () -> Unit) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Stacje w losowaniu (dotknij nazwy, żeby zmienić szansę):",
                style = MaterialTheme.typography.bodySmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                magazyn.losowanie.forEach { los ->
                    val s = magazyn.stacja(los.id) ?: return@forEach
                    Pigulka("${s.nazwa} · ${procentTekst(los.waga)}", wyrozniona = s.nazwa == stacja) { onSzansa(s) }
                }
                Pigulka("  +  ", onClick = onDodaj)
            }
            Text(opisSumy(), style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Text("Ustawienia", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().clickable { ustawAutostart(!autostart) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = autostart, onCheckedChange = { ustawAutostart(it) })
                Text("Graj losowo po uruchomieniu aplikacji", fontSize = 14.sp)
            }
            Opcja("Losowanie raz dziennie", !trybAuto) { ustawTryb(false) }
            Opcja("Losowanie co X minut", trybAuto) { ustawTryb(true) }
            if (trybAuto) Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var tekst by remember { mutableStateOf(autoLimit.toString()) }
                OutlinedTextField(
                    value = tekst,
                    onValueChange = { t ->
                        tekst = t.filter { it.isDigit() }.take(4)
                        tekst.toIntOrNull()?.takeIf { it > 0 }?.let {
                            autoLimit = it
                            StanLosowania.ustawAutoMin(this@MainActivity, it)
                        }
                    },
                    label = { Text("Liczba minut") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(160.dp)
                )
                Opcja("Czas względny (minuty RMF24 się nie liczą)", wzgledny) { ustawWzgledny(true) }
                Opcja("Czas bezwzględny (liczy się też RMF24)", !wzgledny) { ustawWzgledny(false) }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    @Composable
    private fun ZakladkaMowy() {
        var wlaczone by remember { mutableStateOf(UstMowy.wlaczone(this@MainActivity)) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable {
                    wlaczone = !wlaczone; UstMowy.ustaw(this@MainActivity, wlaczone)
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = wlaczone, onCheckedChange = {
                    wlaczone = it; UstMowy.ustaw(this@MainActivity, it)
                })
                Text("Wykrywanie mowy w RMF24 (od następnego włączenia stacji)")
            }
            Text(
                (if (LogMowy.aktywny.value) "Nasłuch RMF24 działa" else "Nasłuch wyłączony") +
                    ", werdykt: ${LogMowy.werdykt.value}",
                style = MaterialTheme.typography.titleSmall
            )
            Text("Próg $PROG_YAMNET, blok $BLOK_S s, potwierdzenie: mowa $POTW_MOWA, muzyka $POTW_MUZYKA. " +
                "Przy werdykcie MOWA gra RMF24 od początku mowy, potem płynny powrót.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PoleSekund("Margines początku (s)", UstMowy.margPocz(this@MainActivity), Modifier.weight(1f)) {
                    UstMowy.ustawMargPocz(this@MainActivity, it)
                }
                PoleSekund("Margines końca (s)", UstMowy.margKon(this@MainActivity), Modifier.weight(1f)) {
                    UstMowy.ustawMargKon(this@MainActivity, it)
                }
            }
            PoleSekund("Czas przejścia (s): RMF24 i zmiana stacji", UstMowy.czasPrzejscia(this@MainActivity),
                Modifier.fillMaxWidth()) { UstMowy.ustawCzasPrzejscia(this@MainActivity, it) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SekcjaGlosnosci()
        }
    }

    @Composable
    private fun EkranLogu() {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { pokazLog = false }) { Text("← Wróć") }
                Spacer(Modifier.weight(1f))
                Text("Log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 8.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(LogMowy.wpisy) { w ->
                    Text(w, fontSize = 12.sp, lineHeight = 15.sp,
                        color = if (w.contains("| mowa |")) Color(0xFF43A047) else Color.Unspecified)
                }
            }
        }
    }

    @Composable
    private fun SekcjaGlosnosci() {
        val c = this@MainActivity
        var wlaczone by remember { mutableStateOf(UstGlosnosci.wlaczone(c)) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Głośność", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().clickable { wlaczone = !wlaczone; UstGlosnosci.ustawWlaczone(c, wlaczone) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = wlaczone, onCheckedChange = { wlaczone = it; UstGlosnosci.ustawWlaczone(c, it) })
                Text("Wyrównywanie głośności")
            }
            Text(StanGlosnosci.opis.value, style = MaterialTheme.typography.titleSmall)
            Text("Pomiar w LUFS (0 = maksimum, mowa RMF24 ok. −8,8). Zmiany wchodzą w ciągu kilku sekund. " +
                "Limiter zawsze pilnuje, żeby nie było przesteru.", style = MaterialTheme.typography.bodySmall)
            Krokomierz("Cel (LUFS)", UstGlosnosci.cel(c), 0.5f, -30f, -3f) { UstGlosnosci.ustawCel(c, it) }
            Krokomierz("Maks. podgłośnienie (dB)", UstGlosnosci.maks(c), 0.5f, 0f, 6f) { UstGlosnosci.ustawMaks(c, it) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Korekty ręczne (dB), dodawane do automatu", style = MaterialTheme.typography.titleSmall)
            magazyn.stacje.filter { it.adres != SPOTIFY_ADRES }.forEach { st ->
                Krokomierz(st.nazwa, UstGlosnosci.korekta(c, st.id), 0.5f, -20f, 6f) {
                    UstGlosnosci.ustawKorekte(c, st.id, it)
                }
            }
            Krokomierz("RMF24 (mowa)", UstGlosnosci.korekta(c, UstGlosnosci.ID_RMF), 0.5f, -20f, 6f) {
                UstGlosnosci.ustawKorekte(c, UstGlosnosci.ID_RMF, it)
            }
            Text("Spotify: włącz „Normalizuj głośność” w ustawieniach aplikacji Spotify.",
                style = MaterialTheme.typography.bodySmall)
        }
    }

    /** Wartość zmieniana przyciskami − / + (wygodne kciukiem, działa też dla liczb ujemnych). */
    @Composable
    private fun Krokomierz(etykieta: String, wartosc: Float, krok: Float, min: Float, maks: Float, zapisz: (Float) -> Unit) {
        var v by remember(etykieta) { mutableStateOf(wartosc) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(etykieta, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutlinedButton(onClick = { v = (v - krok).coerceIn(min, maks); zapisz(v) },
                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp, 36.dp)) { Text("−") }
            Text(String.format(java.util.Locale.US, "%+.1f", v), Modifier.width(64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Medium)
            OutlinedButton(onClick = { v = (v + krok).coerceIn(min, maks); zapisz(v) },
                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp, 36.dp)) { Text("+") }
        }
    }

    @Composable
    private fun PoleSekund(etykieta: String, wartosc: Float, modifier: Modifier, zapisz: (Float) -> Unit) {
        var tekst by remember {
            mutableStateOf(if (wartosc % 1f == 0f) wartosc.toInt().toString() else wartosc.toString())
        }
        OutlinedTextField(
            value = tekst,
            onValueChange = { t ->
                tekst = t.replace(',', '.').filter { it.isDigit() || it == '.' }.take(4)
                tekst.toFloatOrNull()?.let(zapisz)
            },
            label = { Text(etykieta, fontSize = 12.sp) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = modifier
        )
    }

    @Composable
    private fun Opcja(tekst: String, wybrana: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = wybrana, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = wybrana, onClick = onClick)
            Text(tekst, fontSize = 14.sp)
        }
    }

    /** Ile procent zostało do 100 (albo ile jest ponad). */
    private fun opisSumy(): String {
        val r = magazyn.niewykorzystane()
        return when {
            kotlin.math.abs(r) < 0.05 -> "Suma: 100% ✓"
            r > 0 -> "Niewykorzystane: ${procentTekst(r)}"
            else -> "Ponad 100% o ${procentTekst(-r)} (losowanie liczy proporcjonalnie)"
        }
    }

    @Composable
    private fun OknoSzansy(s: Stacja, zamknij: () -> Unit) {
        val p = magazyn.procent(s.id)
        var tekst by remember { mutableStateOf("") }
        val jedyna = false
        AlertDialog(
            onDismissRequest = zamknij,
            title = { Text(s.nazwa) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Szansa wylosowania: ${procentTekst(p)}", style = MaterialTheme.typography.titleMedium)
                    if (jedyna) Text("To jedyna stacja w losowaniu.")
                    else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { magazyn.ustawProcent(s.id, p - 1) }) { Text("− 1%") }
                            OutlinedButton(onClick = { magazyn.ustawProcent(s.id, p + 1) }) { Text("+ 1%") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = tekst, onValueChange = { tekst = it.replace(',', '.') },
                                label = { Text("Wpisz %") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.width(120.dp)
                            )
                            TextButton(onClick = {
                                tekst.toDoubleOrNull()?.let { magazyn.ustawProcent(s.id, it) }
                                tekst = ""
                            }) { Text("Ustaw") }
                        }
                        Text("Pozostałe stacje się nie zmieniają.",
                            style = MaterialTheme.typography.bodySmall)
                        Text(opisSumy(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = { TextButton(onClick = zamknij) { Text("Gotowe") } },
            dismissButton = {
                TextButton(onClick = { magazyn.usunZLosowania(s.id); zamknij() }) { Text("Usuń z losowania") }
            }
        )
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
            // autostart: tylko przy pierwszym otwarciu, gdy nic jeszcze nie gra
            if (autostart && !autostartWykonany && !c.playWhenReady) losuj()
            autostartWykonany = true
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        Synchro.teraz()
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
        utwor = p.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty()
        graTeraz = p.playWhenReady
        if (stacja.isNotEmpty()) status = when (p.playbackState) {
            Player.STATE_BUFFERING -> "Łączę..."
            Player.STATE_READY -> if (p.isPlaying) "Gra." else "Pauza."
            else -> status
        }
    }

    private fun graj(s: Stacja) {
        if (s.adres == SPOTIFY_ADRES && playlista == null) {
            status = "Najpierw wybierz playlistę Spotify (na dole zakładki Stacje)."
            return
        }
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

    private fun pauza() {
        val c = kontroler ?: return
        if (c.playWhenReady) c.pause() else c.play()
    }

    private fun ustawAutostart(a: Boolean) {
        autostart = a
        prefs.edit().putBoolean("autostart", a).apply()
    }

    private fun ustawWzgledny(w: Boolean) {
        wzgledny = w
        StanLosowania.ustawWzgledny(this, w)
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
