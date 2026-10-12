package com.cashflow.app

import android.app.Application
import com.cashflow.app.data.AppDatabase
import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.data.SharedPrefsAutoAccept
import com.cashflow.app.engine.AppDates
import com.cashflow.app.engine.BalanceEngine
import com.cashflow.app.notify.NotificationCenterRepository
import com.cashflow.app.notify.NotificationDatabase
import com.cashflow.app.notify.NotificationPoster
import com.cashflow.app.notify.NotificationPrefs
import com.cashflow.app.notify.ReminderRunner
import com.cashflow.app.notify.Reminders
import com.cashflow.app.ui.UiPrefs
import com.cashflow.app.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Poor man's DI: one database, one repository, created lazily. */
class CashflowApp : Application() {
    override fun onCreate() {
        // The week starts where the phone's region says; everything else is English whatever the phone's language.
        AppDates.firstDayOfWeek = java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek
        java.util.Locale.setDefault(AppDates.LOCALE)
        super.onCreate()
        UpdateManager.deleteCachedApk(this) // never keep a downloaded update across launches
        notificationPoster.createChannels()
        Reminders.schedule(this) // unique periodic worker, every 15 minutes
        startReminderChecks()
    }

    /** Runs the reminder check at start and after every data or notification-setting change (debounced, off the main thread). */
    @OptIn(FlowPreview::class)
    private fun startReminderChecks() {
        appScope.launch {
            combine(repository.transactions, repository.settings, notificationPrefs.toggles) { _, _, _ -> }
                .debounce(1_500)
                .collect { runCatching { reminderRunner.run() } }
        }
    }

    val updateManager: UpdateManager by lazy { UpdateManager(this) }

    val uiPrefs: UiPrefs by lazy { UiPrefs(this) }

    val repository: LedgerRepository by lazy { LedgerRepository(AppDatabase.create(this), SharedPrefsAutoAccept(this)) }

    /** The balance engine is injected as a factory so view models never construct it directly. */
    val engineFactory: EngineFactory = EngineFactory(::BalanceEngine)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val notificationPrefs: NotificationPrefs by lazy { NotificationPrefs(this) }
    val notificationPoster: NotificationPoster by lazy { NotificationPoster(this) }

    /** The bell's history: its own database file, "notifications.db". */
    val notificationCenter: NotificationCenterRepository by lazy { NotificationCenterRepository(NotificationDatabase.create(this)) }
    val reminderRunner: ReminderRunner by lazy { ReminderRunner(this) }

    /** Shared engine snapshots for the Calendar and Upcoming tabs. */
    val snapshots: LedgerSnapshots by lazy { LedgerSnapshots(repository, engineFactory, clock, appScope) }

    /** Device-local date; re-read on every call so a time zone change is picked up. */
    val clock: () -> LocalDate = { LocalDate.now() }
}

fun interface EngineFactory {
    fun create(
        settings: com.cashflow.app.engine.AppSettings,
        transactions: List<com.cashflow.app.engine.Transaction>,
        today: LocalDate,
    ): BalanceEngine
}
