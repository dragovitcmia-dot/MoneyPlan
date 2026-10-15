package com.cashflow.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.cashflow.app.CashflowApp

/**
 * Receives the PackageInstaller session result. When the system needs the user to confirm
 * (STATUS_PENDING_USER_ACTION: older Android, or this app is not yet the installer of record) it
 * opens the system confirmation screen; failures are reported to the update screen.
 */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            return
        }
        (context.applicationContext as CashflowApp).updateManager.onInstallStatus(status, message)
    }

    companion object {
        const val ACTION = com.cashflow.app.AppIds.UPDATE_STATUS_ACTION
    }
}
