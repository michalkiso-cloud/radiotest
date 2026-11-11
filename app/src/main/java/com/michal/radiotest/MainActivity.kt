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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

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

    @androidx.compose.runtime.Composable
    private fun Ekran() {
        Column(
            Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Radio", style = MaterialTheme.typography.headlineMedium)
            Text(if (stacja.isEmpty()) "Nic nie gra" else "Gra: $stacja",
                style = MaterialTheme.typography.titleMedium)
            if (utwor.isNotEmpty()) Text(utwor, color = Color(0xFFFFEB3B))
            Text(status, style = MaterialTheme.typography.bodySmall)

            Kanaly.lista.forEach { k ->
                Button(
                    onClick = { graj(k) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (k.nazwa == stacja) ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32)) else ButtonDefaults.buttonColors()
                ) { Text(k.nazwa) }
            }
            Button(onClick = { grajAdres("RMF24", Kanaly.RMF24) }, Modifier.fillMaxWidth()) {
                Text("RMF24 (test)")
            }
            OutlinedButton(onClick = { stop() }, Modifier.fillMaxWidth()) { Text("Stop") }

            Text("Ustawienia", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = kluczDi,
                onValueChange = { kluczDi = it },
                label = { Text("Klucz DI.FM premium") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = {
                prefs.edit().putString("klucz_di", kluczDi.trim()).apply()
                status = "Klucz DI.FM zapisany."
            }) { Text("Zapisz klucz") }
            if (bateriaOk) {
                Text("Bateria: bez ograniczeń ✓", color = Color(0xFF81C784))
            } else {
                OutlinedButton(onClick = { bateria() }) { Text("Ustaw baterię: bez ograniczeń") }
            }
            Text("Wersja ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        }
    }

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

    private fun graj(k: Kanal) {
        if (k.diFm && kluczDi.isBlank()) {
            status = "Najpierw wpisz i zapisz klucz DI.FM (niżej)."
            return
        }
        grajAdres(k.nazwa, Kanaly.adres(k, kluczDi))
    }

    private fun grajAdres(nazwa: String, adres: String) {
        val c = kontroler ?: run { status = "Odtwarzacz jeszcze się uruchamia..."; return }
        val item = MediaItem.Builder()
            .setUri(adres)
            .setMediaId(nazwa)
            .setMediaMetadata(
                MediaMetadata.Builder().setStation(nazwa).setArtist(nazwa).build()
            )
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
        stacja = nazwa
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
