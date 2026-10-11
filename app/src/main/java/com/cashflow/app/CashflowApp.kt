package com.cashflow.app

import android.app.Application
import com.cashflow.app.data.AppDatabase
import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.engine.BalanceEngine
import com.cashflow.app.ui.UiPrefs
import com.cashflow.app.update.UpdateManager
import java.time.LocalDate

/** Poor man's DI: one database, one repository, created lazily. */
class CashflowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateManager.deleteCachedApk(this) // never keep a downloaded update across launches
    }

    val updateManager: UpdateManager by lazy { UpdateManager(this) }

    val uiPrefs: UiPrefs by lazy { UiPrefs(this) }

    val repository: LedgerRepository by lazy { LedgerRepository(AppDatabase.create(this)) }

    /** The balance engine is injected as a factory so view models never construct it directly. */
    val engineFactory: EngineFactory = EngineFactory(::BalanceEngine)

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
