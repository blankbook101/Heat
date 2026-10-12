package com.heat.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.heat.app.core.Notifier

/** Keeps Heat alive (low-power advertising + GATT server) so messages and offers arrive in the background. */
class HeatService : Service() {
    companion object {
        const val ACTION_STOP = "com.heat.app.STOP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as HeatApp).engine
        if (intent?.action == ACTION_STOP) {
            engine.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            ServiceCompat.startForeground(
                this, Notifier.SVC_ID, engine.notifier.serviceNotification(engine.name.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
            engine.start()
            START_STICKY
        } catch (_: Exception) {
            stopSelf()
            START_NOT_STICKY
        }
    }
}
