package com.heat.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.heat.app.core.HeatEngine
import com.heat.app.ui.HeatRoot

class MainActivity : ComponentActivity() {
    private val engine: HeatEngine get() = (application as HeatApp).engine
    private var granted by mutableStateOf(false)

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissionsChanged()
    }
    private val enableBt = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        engine.start()
    }

    private fun wanted(): Array<String> {
        val l = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        if (Build.VERSION.SDK_INT >= 33) {
            l += Manifest.permission.NEARBY_WIFI_DEVICES
            l += Manifest.permission.POST_NOTIFICATIONS
        } else {
            l += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return l.toTypedArray()
    }

    private fun onPermissionsChanged() {
        granted = engine.hasCorePerms()
        if (granted && engine.termsOk.value) {
            engine.start()
            runCatching { ContextCompat.startForegroundService(this, Intent(this, HeatService::class.java)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            HeatRoot(
                engine = engine,
                permsGranted = granted,
                onRequestPerms = {
                    askPerms.launch(wanted())
                },
                onOpenSettings = {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                },
                onEnableBt = { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            )
        }
        onPermissionsChanged()
        if (!granted && engine.termsOk.value) askPerms.launch(wanted())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i?.getStringExtra("peer")?.let { engine.openPeer.value = it }
    }

    override fun onStart() {
        super.onStart()
        engine.visible = true
        if (!granted) onPermissionsChanged()
    }

    override fun onStop() {
        engine.visible = false
        super.onStop()
    }
}
