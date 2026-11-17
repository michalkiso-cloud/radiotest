package com.michal.radiotest

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken

/**
 * Start radia po wejściu do auta – jak auto_radio.sh w skrypcie Termuxa:
 * telefon łączy się z Wi-Fi auta (adres 192.168.43.x) → radio rusza.
 * System sam budzi aplikację (PendingIntent), także gdy jest zamknięta.
 */
object WifiAuta {
    const val PREFIKS = "192.168.43."
    private const val AKCJA = "com.michal.radiotest.WIFI_AUTA"

    fun wlaczone(c: Context) = c.getSharedPreferences("ustawienia", Context.MODE_PRIVATE).getBoolean("start_wifi", false)
    fun ustaw(c: Context, v: Boolean) {
        c.getSharedPreferences("ustawienia", Context.MODE_PRIVATE).edit().putBoolean("start_wifi", v).apply()
        zarejestruj(c)
    }

    private fun intencja(c: Context) = PendingIntent.getBroadcast(
        c, 7, Intent(c, OdbiornikWifi::class.java).setAction(AKCJA),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    /** Rejestracja w systemie (wywoływana przy starcie aplikacji, usługi i po restarcie telefonu). */
    fun zarejestruj(c: Context) {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return
        val pi = intencja(c)
        runCatching { cm.unregisterNetworkCallback(pi) }
        if (!wlaczone(c)) return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)   // hotspot auta może być bez internetu
            .build()
        runCatching { cm.registerNetworkCallback(req, pi) }
    }

    /** Czy sieć ma adres z Wi-Fi auta. */
    fun toWifiAuta(c: Context, n: Network?): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        val lp = (n ?: return false).let { cm.getLinkProperties(it) } ?: return false
        return lp.linkAddresses.any { it.address.hostAddress?.startsWith(PREFIKS) == true }
    }
}

class OdbiornikWifi : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            WifiAuta.zarejestruj(c); return
        }
        if (!WifiAuta.wlaczone(c)) return
        @Suppress("DEPRECATION")
        val n: Network? = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK)
        val p = c.getSharedPreferences("ustawienia", Context.MODE_PRIVATE)
        val auto = WifiAuta.toWifiAuta(c, n)
        if (!auto) return
        // jak „byl” w skrypcie: start tylko raz na jedno połączenie (system może zgłosić sieć kilka razy)
        val teraz = System.currentTimeMillis()
        if (teraz - p.getLong("wifi_auta_ms", 0L) < 120_000) { p.edit().putLong("wifi_auta_ms", teraz).apply(); return }
        p.edit().putLong("wifi_auta_ms", teraz).apply()
        val wynik = goAsync()
        val token = SessionToken(c, ComponentName(c, RadioService::class.java))
        val f = MediaController.Builder(c, token).buildAsync()
        f.addListener({
            runCatching {
                val k = f.get()
                if (!k.isPlaying && !k.playWhenReady) {
                    LogMowy.dodaj("Wi-Fi auta: start radia")
                    if (k.mediaItemCount > 0) k.play()
                    else k.sendCustomCommand(SessionCommand(KOMENDA_LOSUJ, Bundle.EMPTY), Bundle.EMPTY)
                }
                android.os.Handler(c.mainLooper).postDelayed({ k.release(); wynik.finish() }, 3000)
            }.onFailure { wynik.finish() }
        }, c.mainExecutor)
    }
}
