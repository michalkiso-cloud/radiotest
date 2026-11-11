package com.michal.radiotest

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse

const val CLIENT_ID = "b9612d932c4540dab2a687aa1a7849b1"
const val REDIRECT_URI = "radiotest://callback"
const val TEST_URI = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
const val TAG = "RadioTest"
private const val KOD_AUTORYZACJI = 1337

class MainActivity : ComponentActivity() {
    private var remote: SpotifyAppRemote? = null
    private var status by mutableStateOf("Gotowe.")
    private var wynik by mutableStateOf("brak")
    private var log by mutableStateOf("")
    private var ostatniaProba = 0L
    private val zgodaPowiadomienia =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpotifyAppRemote.setDebugMode(true)
        if (Build.VERSION.SDK_INT >= 33) {
            zgodaPowiadomienia.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Test Spotify App Remote", style = MaterialTheme.typography.headlineSmall)
                        Button(onClick = { autoryzacja() }) { Text("0. Zgoda Spotify (autoryzacja)") }
                        Button(onClick = { polaczIZagraj() }) { Text("1. Połącz i zagraj") }
                        Button(onClick = { startTestu() }) { Text("2. Test w tle: zagraj za 60 s") }
                        Button(onClick = { remote?.playerApi?.pause() }) { Text("Pauza") }
                        Text("Status: $status")
                        Text("Wynik testu w tle: $wynik")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { log = czytajLog() }) { Text("Pokaż log") }
                            OutlinedButton(onClick = { kopiujLog() }) { Text("Kopiuj log") }
                        }
                        if (log.isNotEmpty()) {
                            SelectionContainer {
                                Text(log, fontSize = 11.sp, lineHeight = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        wynik = getSharedPreferences("test", MODE_PRIVATE).getString("wynik", "brak") ?: "brak"
    }

    private fun autoryzacja() {
        status = "Otwieram zgodę Spotify..."
        Log.i(TAG, "Start autoryzacji")
        val request = AuthorizationRequest.Builder(
            CLIENT_ID, AuthorizationResponse.Type.TOKEN, REDIRECT_URI
        ).setScopes(arrayOf("app-remote-control", "user-read-playback-state")).build()
        AuthorizationClient.openLoginActivity(this, KOD_AUTORYZACJI, request)
    }

    @Deprecated("Prosty test")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != KOD_AUTORYZACJI) return
        val r = AuthorizationClient.getResponse(resultCode, data)
        status = when (r.type) {
            AuthorizationResponse.Type.TOKEN -> "Autoryzacja OK. Teraz przycisk 1."
            AuthorizationResponse.Type.ERROR -> "Błąd autoryzacji: ${r.error}"
            else -> "Autoryzacja: ${r.type} (resultCode $resultCode)"
        }
        Log.i(TAG, "Wynik autoryzacji: $status")
    }

    private fun polaczIZagraj() {
        if (!SpotifyAppRemote.isSpotifyInstalled(this)) {
            status = "Spotify nie jest zainstalowane (albo niewidoczne dla aplikacji)."
            return
        }
        status = "Łączę..."
        Log.i(TAG, "Start połączenia App Remote")
        val proba = System.currentTimeMillis()
        ostatniaProba = proba
        window.decorView.postDelayed({
            if (ostatniaProba == proba && status == "Łączę...") {
                status = "Brak odpowiedzi Spotify po 20 s."
                Log.w(TAG, status)
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
                Log.i(TAG, status)
                r.playerApi.play(TEST_URI)
            }

            override fun onFailure(t: Throwable) {
                Log.e(TAG, "Błąd połączenia", t)
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

    private fun czytajLog(): String = try {
        val p = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-t", "400", "-v", "time", "--pid=${Process.myPid()}")
        )
        val linie = p.inputStream.bufferedReader().readLines()
            .filter { l ->
                listOf("RadioTest", "Spotify", "spotify", "AppRemote", "Exception", "Error", "auth")
                    .any { l.contains(it) } && !l.contains("annotation class")
            }
        if (linie.isEmpty()) "(brak wpisów)" else linie.takeLast(80).joinToString("\n")
    } catch (e: Exception) {
        "Nie udało się odczytać logu: ${e.message}"
    }

    private fun kopiujLog() {
        val tekst = czytajLog()
        log = tekst
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("log", tekst))
        status = "Log skopiowany. Wklej go w czacie."
    }

    override fun onDestroy() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        super.onDestroy()
    }
}
