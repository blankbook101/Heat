package com.heat.app.core

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Wi-Fi Direct without any pairing prompt: the receiver creates a group with a random
 * name/passphrase (sent to the sender over BLE), the sender joins it by name + passphrase.
 */
@SuppressLint("MissingPermission")
class WifiDirect(private val ctx: Context) {
    private val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private fun ch(): WifiP2pManager.Channel =
        channel ?: mgr.initialize(ctx, Looper.getMainLooper(), null).also { channel = it }

    fun wifiOn(): Boolean =
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled

    private class L(val done: (Boolean) -> Unit) : WifiP2pManager.ActionListener {
        override fun onSuccess() = done(true)
        override fun onFailure(reason: Int) = done(false)
    }

    /** Tear down any group / pending connection (safe to call when there is none). */
    suspend fun clear() {
        withTimeoutOrNull(3000) {
            suspendCancellableCoroutine<Unit> { c ->
                mgr.cancelConnect(ch(), L { if (c.isActive) c.resume(Unit) })
            }
        }
        withTimeoutOrNull(3000) {
            suspendCancellableCoroutine<Unit> { c ->
                mgr.removeGroup(ch(), L { if (c.isActive) c.resume(Unit) })
            }
        }
    }

    suspend fun createGroup(ssid: String, pass: String): Boolean {
        clear()
        val cfg = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(pass)
            .enablePersistentMode(false)
            .build()
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<Boolean> { c ->
                mgr.createGroup(ch(), cfg, L { ok -> if (c.isActive) c.resume(ok) })
            }
        } ?: false
    }

    /** Joins the receiver's group; returns the group owner's IP, or null on failure/timeout. */
    suspend fun join(ssid: String, pass: String, timeoutMs: Long): String? {
        clear()
        val result = CompletableDeferred<String?>()
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = query(result)
        }
        ContextCompat.registerReceiver(
            ctx, rx, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            val cfg = WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(pass)
                .enablePersistentMode(false)
                .build()
            mgr.connect(ch(), cfg, L { ok -> if (!ok) result.complete(null) })
            return coroutineScope {
                val poll = launch {
                    while (isActive) {
                        delay(800)
                        query(result)
                    }
                }
                val r = withTimeoutOrNull(timeoutMs) { result.await() }
                poll.cancel()
                r
            }
        } finally {
            runCatching { ctx.unregisterReceiver(rx) }
        }
    }

    private fun query(r: CompletableDeferred<String?>) {
        if (r.isCompleted) return
        mgr.requestConnectionInfo(ch()) { info ->
            if (info != null && info.groupFormed && !info.isGroupOwner) {
                r.complete(info.groupOwnerAddress?.hostAddress ?: "192.168.49.1")
            }
        }
    }
}
