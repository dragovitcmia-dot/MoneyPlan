package com.cashflow.app.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cashflow.app.MainActivity
import com.cashflow.app.R
import com.cashflow.app.engine.NotificationDecision
import com.cashflow.app.engine.NotificationType

/** Channels, the status-bar notification itself and its tap target. Executes decisions; decides nothing. */
class NotificationPoster(context: Context) {
    private val context = context.applicationContext

    fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PAYMENTS, context.getString(R.string.channel_payments), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_HEADS_UP, context.getString(R.string.channel_heads_up), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** False when the permission is missing or the user blocked the app's notifications. The bell works regardless. */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission") // canPost() checks it
    fun post(decision: NotificationDecision): Boolean {
        if (!canPost()) return false
        val id = idOf(decision.type)
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, decision.route.name)
            .putExtra(MainActivity.EXTRA_DATE, decision.relatedDate.toString())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val channel = if (decision.type == NotificationType.HEADS_UP_WEEK || decision.type == NotificationType.HEADS_UP_DAY) CHANNEL_HEADS_UP else CHANNEL_PAYMENTS
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_cashflow)
            .setContentTitle(decision.title)
            .setContentText(decision.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(decision.body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
        return true
    }

    fun cancel(type: NotificationType) {
        NotificationManagerCompat.from(context).cancel(idOf(type))
    }

    companion object {
        const val CHANNEL_PAYMENTS = "payments"
        const val CHANNEL_HEADS_UP = "heads_up"

        /** One id per type: a newer notification of the same type (a nag, the next day's summary) replaces the old one. */
        fun idOf(type: NotificationType): Int = 1000 + type.ordinal
    }
}
