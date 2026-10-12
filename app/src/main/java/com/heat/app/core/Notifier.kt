package com.heat.app.core

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.heat.app.HeatService
import com.heat.app.MainActivity
import com.heat.app.R

@SuppressLint("MissingPermission")
class Notifier(private val ctx: Context) {
    companion object {
        const val CH_MSG = "heat_msg"
        const val CH_XFER = "heat_xfer"
        const val CH_SVC = "heat_svc"
        const val SVC_ID = 1
    }

    private val nm = NotificationManagerCompat.from(ctx)

    init {
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_XFER, "File transfers", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SVC, "Heat is running", NotificationManager.IMPORTANCE_LOW))
    }

    private fun open(peerId: String?, code: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (peerId != null) putExtra("peer", peerId)
        }
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun serviceNotification(name: String): Notification {
        val stop = PendingIntent.getService(
            ctx, 99,
            Intent(ctx, HeatService::class.java).setAction(HeatService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, CH_SVC)
            .setSmallIcon(R.drawable.ic_stat_heat)
            .setContentTitle("Heat is on")
            .setContentText("Visible as $name")
            .setContentIntent(open(null, 1))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stop)
            .build()
    }

    fun updateService(name: String) = post(SVC_ID, serviceNotification(name))

    fun message(peerId: String, from: String, text: String) = post(
        peerId.hashCode(),
        NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat_heat)
            .setContentTitle("New message from $from")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open(peerId, peerId.hashCode()))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
    )

    fun incoming(from: String, label: String) = post(
        2001,
        NotificationCompat.Builder(ctx, CH_XFER)
            .setSmallIcon(R.drawable.ic_stat_heat)
            .setContentTitle("$from wants to send you a file")
            .setContentText(label)
            .setContentIntent(open(null, 2))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build()
    )

    fun done(title: String, text: String) = post(
        2002,
        NotificationCompat.Builder(ctx, CH_XFER)
            .setSmallIcon(R.drawable.ic_stat_heat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open(null, 3))
            .setAutoCancel(true)
            .build()
    )

    fun failed(title: String, text: String) = post(
        2003,
        NotificationCompat.Builder(ctx, CH_XFER)
            .setSmallIcon(R.drawable.ic_stat_heat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open(null, 4))
            .setAutoCancel(true)
            .build()
    )

    fun info(title: String, text: String) = post(
        2004,
        NotificationCompat.Builder(ctx, CH_XFER)
            .setSmallIcon(R.drawable.ic_stat_heat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open(null, 5))
            .setAutoCancel(true)
            .build()
    )

    private fun post(id: Int, n: Notification) {
        try {
            if (nm.areNotificationsEnabled()) nm.notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
