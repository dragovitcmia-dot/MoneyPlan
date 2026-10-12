package com.cashflow.app.ui.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.cashflow.app.notify.NotificationPoster
import com.cashflow.app.notify.NotificationPrefs

/**
 * The POST_NOTIFICATIONS permission, asked in context only: once after the user adds their first payment, and when
 * they turn the master switch on. The first launch never asks. If it is denied or blocked the bell keeps working and
 * Settings offers a button to the system's notification settings.
 */
class NotificationPermission(
    private val poster: NotificationPoster,
    private val prefs: NotificationPrefs,
    private val launch: () -> Unit,
) {
    /** Whether the system lets the app post right now. */
    var allowed by mutableStateOf(poster.canPost())
        private set

    fun refresh() {
        allowed = poster.canPost()
    }

    /** Shows the system dialog when the app may still ask; otherwise nothing happens (Settings explains). */
    fun request() {
        refresh()
        if (!allowed && Build.VERSION.SDK_INT >= 33) launch()
    }

    /** Called right after a payment was saved. Asks automatically only the first time. */
    fun onPaymentAdded() {
        if (prefs.permissionAsked || !prefs.toggles.value.enabled) return
        prefs.permissionAsked = true
        request()
    }

    companion object {
        fun openNotificationSettings(context: Context) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        /** The app's system info page, where "background battery use" can be allowed. */
        fun openAppInfo(context: Context) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

val LocalNotificationPermission = staticCompositionLocalOf<NotificationPermission?> { null }
