package com.cashflow.app.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cashflow.app.CashflowApp
import java.util.concurrent.TimeUnit

/** The 15-minute reminder check. Unique and KEEP: scheduling it again on every start never doubles it. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CashflowApp
        return runCatching { app.reminderRunner.run() }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}

object Reminders {
    const val WORK_NAME = "reminders"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
