package com.michal.radiotest

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote

const val CLIENT_ID = "b9612d932c4540dab2a687aa1a7849b1"
const val REDIRECT_URI = "radiotest://callback"
const val TEST_URI = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"

class MainActivity : ComponentActivity() {
    private var remote: SpotifyAppRemote? = null
    private var status by mutableStateOf("Gotowe.")
    private var wynik by mutableStateOf("brak")
    private var ostatniaProba = 0L
    private val zgodaPowiadomienia =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            zgodaPowiadomienia.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Test Spotify App Remote", style = MaterialTheme.typography.headlineSmall)
                        Button(onClick = { polaczIZagraj() }) { Text("1. Połącz i zagraj") }
                        Button(onClick = { startTestu() }) { Text("2. Test w tle: zagraj za 60 s") }
                        Button(onClick = { remote?.playerApi?.pause() }) { Text("Pauza") }
                        Text("Status: $status")
                        Text("Wynik testu w tle: $wynik")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        wynik = getSharedPreferences("test", MODE_PRIVATE).getString("wynik", "brak") ?: "brak"
    }

    private fun polaczIZagraj() {
        if (!SpotifyAppRemote.isSpotifyInstalled(this)) {
            status = "Spotify nie jest zainstalowane (albo niewidoczne dla aplikacji)."
            return
        }
        status = "Łączę..."
        val proba = System.currentTimeMillis()
        ostatniaProba = proba
        window.decorView.postDelayed({
            if (ostatniaProba == proba && status == "Łączę...") {
                status = "Brak odpowiedzi Spotify po 20 s. Otwórz Spotify i spróbuj ponownie."
            }
        }, 20_000)
        val params = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(this, params, object : Connector.ConnectionListener {
            override fun onConnected(r: SpotifyAppRemote) {
                remote = r
                status = "Połączono. Gram."
                r.playerApi.play(TEST_URI)
            }

            override fun onFailure(t: Throwable) {
                Log.e("RadioTest", "Błąd połączenia", t)
                status = "Błąd: ${t.javaClass.simpleName}: ${t.message}"
            }
        })
    }

    private fun startTestu() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        startForegroundService(Intent(this, TestService::class.java))
        status = "Test uruchomiony. Teraz zamknij Spotify i zablokuj ekran."
    }

    override fun onDestroy() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        super.onDestroy()
    }
}
