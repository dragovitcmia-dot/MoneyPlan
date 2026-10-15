package com.cashflow.app

import android.app.Application
import com.cashflow.app.data.AppDatabase
import com.cashflow.app.data.backup.BackupManager
import com.cashflow.app.data.backup.BackupStartup
import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.data.SharedPrefsAutoAccept
import com.cashflow.app.data.SharedPrefsSeriesFlags
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Poor man's DI: one database, one repository, created lazily. */
class CashflowApp : Application() {
    override fun onCreate() {
        if (isRestartProcess()) {
            super.onCreate()
            return // the tiny process that restarts the app after a restore does nothing else
        }
        // The week starts where the phone's region says; everything else is English whatever the phone's language.
        AppDates.firstDayOfWeek = java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek
        java.util.Locale.setDefault(AppDates.LOCALE)
        super.onCreate()
        com.cashflow.app.lab.LabHooks.onAppCreate(this) // no-op outside the Lab build; the Lab restores its clock first
        // BEFORE anything can open the database: an update with a different schema gets a "Before update" snapshot first.
        runCatching { BackupStartup.beforeRoomOpens(this, versionName()) }
        UpdateManager.deleteCachedApk(this) // never keep a downloaded update across launches
        notificationPoster.createChannels()
        Reminders.schedule(this) // unique periodic worker, every 15 minutes
        startReminderChecks()
        startBackups()
    }

    private fun versionName(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    private fun isRestartProcess(): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000') }.getOrNull()
        }
        return name?.endsWith(":restart") == true
    }

    /** Snapshots: one about 60 seconds after the last data change, and one at start if the last is older than the last change. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startBackups() {
        appScope.launch {
            backupManager.onStart()
            combine(repository.transactions, repository.rules, repository.settings, repository.seriesContext) { _, _, _, _ -> }
                .drop(1) // the first emission is the current state, not a change
                .collect { backupManager.noteChange(appScope) }
        }
    }

    /** Runs the reminder check at start and after every data or notification-setting change (debounced, off the main thread). */
    @OptIn(FlowPreview::class)
    private fun startReminderChecks() {
        appScope.launch {
            combine(repository.transactions, repository.settings, notificationPrefs.toggles, repository.rules, repository.seriesContext) { _, _, _, _, _ -> }
                .debounce(400)
                .collect { runCatching { reminderRunner.run() } }
        }
    }

    val updateManager: UpdateManager by lazy { UpdateManager(this) }

    val uiPrefs: UiPrefs by lazy { UiPrefs(this) }

    val seriesFlags: SharedPrefsSeriesFlags by lazy { SharedPrefsSeriesFlags(this) }

    /** The one main database; the backup code closes it before a restore swaps the file. */
    val database: AppDatabase by lazy { AppDatabase.create(this) }

    val repository: LedgerRepository by lazy { LedgerRepository(database, SharedPrefsAutoAccept(this), seriesFlags) }

    val backupManager: BackupManager by lazy { BackupManager(this) }

    /** The balance engine is injected as a factory so view models never construct it directly. */
    val engineFactory: EngineFactory = EngineFactory(::BalanceEngine)

    // A failing background job (for example one still running while a restore closes the database) must not take the app down.
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e("CashflowApp", "background job failed", e) },
    )

    val notificationPrefs: NotificationPrefs by lazy { NotificationPrefs(this) }
    val notificationPoster: NotificationPoster by lazy { NotificationPoster(this) }

    /** The bell's history: its own database file, "notifications.db". */
    val notificationCenter: NotificationCenterRepository by lazy { NotificationCenterRepository(NotificationDatabase.create(this)) }
    val reminderRunner: ReminderRunner by lazy { ReminderRunner(this) }

    /** Shared engine snapshots for the Calendar and Upcoming tabs. */
    val snapshots: LedgerSnapshots by lazy { LedgerSnapshots(repository, engineFactory, clock, appScope) }

    /** Device-local date; re-read on every call so a time zone change is picked up. */
    val clock: () -> LocalDate = { com.cashflow.app.engine.AppClock.today() }
}

fun interface EngineFactory {
    fun create(
        settings: com.cashflow.app.engine.AppSettings,
        transactions: List<com.cashflow.app.engine.Transaction>,
        today: LocalDate,
    ): BalanceEngine
}
