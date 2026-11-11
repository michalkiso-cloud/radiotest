package com.michal.radiotest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TestService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var remote: SpotifyAppRemote? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var zakonczony = false

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("test", "Test", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            1,
            powiadomienie("Za 60 s włączę Spotify. Zamknij Spotify i zablokuj ekran."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "radiotest:test")
            .apply { acquire(5 * 60 * 1000L) }
        zapisz("trwa...")
        handler.postDelayed({ polacz() }, 60_000)
        handler.postDelayed({ koniec("BŁĄD: brak odpowiedzi Spotify w 60 s") }, 120_000)
        return START_NOT_STICKY
    }

    private fun polacz() {
        val params = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(false)
            .build()
        SpotifyAppRemote.connect(this, params, object : Connector.ConnectionListener {
            override fun onConnected(r: SpotifyAppRemote) {
                remote = r
                r.playerApi.play(TEST_URI)
                    .setResultCallback { handler.postDelayed({ sprawdz(r) }, 8_000) }
                    .setErrorCallback { koniec("BŁĄD play: ${it.message}") }
            }

            override fun onFailure(t: Throwable) {
                koniec("BŁĄD połączenia: ${t.javaClass.simpleName}: ${t.message}")
            }
        })
    }

    private fun sprawdz(r: SpotifyAppRemote) {
        r.playerApi.playerState
            .setResultCallback { s ->
                if (!s.isPaused) koniec("SUKCES: gra \"${s.track?.name}\"")
                else koniec("NIE GRA: połączono, ale odtwarzanie wstrzymane")
            }
            .setErrorCallback { koniec("BŁĄD stanu: ${it.message}") }
    }

    private fun koniec(tekst: String) {
        if (zakonczony) return
        zakonczony = true
        handler.removeCallbacksAndMessages(null)
        val czas = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        zapisz("$czas $tekst")
        remote?.let { SpotifyAppRemote.disconnect(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        getSystemService(NotificationManager::class.java)
            .notify(2, powiadomienie("$czas $tekst"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun zapisz(tekst: String) {
        getSharedPreferences("test", MODE_PRIVATE).edit().putString("wynik", tekst).apply()
    }

    private fun powiadomienie(tekst: String): Notification =
        Notification.Builder(this, "test")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("RadioTest")
            .setContentText(tekst)
            .setStyle(Notification.BigTextStyle().bigText(tekst))
            .build()
}
