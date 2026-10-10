package com.cashflow.app.data

import androidx.room.withTransaction
import com.cashflow.app.engine.AppSettings
import com.cashflow.app.engine.DailyIncome
import com.cashflow.app.engine.Recurrence
import com.cashflow.app.engine.RecurringRule
import com.cashflow.app.engine.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Single access point over Room. All decisions (what to materialize, what to auto-apply) come from
 * the pure engine; this class only loads rows, asks the engine, and writes the result.
 */
class LedgerRepository(private val db: AppDatabase) {
    private val txDao = db.transactionDao()
    private val ruleDao = db.recurringRuleDao()
    private val settingsDao = db.settingsDao()

    /** Null until the first [runMaintenance] has created the settings row. */
    val settings: Flow<AppSettings?> = settingsDao.observe().map { it?.toDomain() }
    val transactions: Flow<List<Transaction>> = txDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** Run on app start, on resume, and when the day changes. Idempotent. */
    suspend fun runMaintenance(today: LocalDate) {
        db.withTransaction { maintain(today) }
    }

    suspend fun updateSettings(settings: AppSettings, today: LocalDate) {
        db.withTransaction {
            settingsDao.upsert(settings.toEntity())
            if (settings.dailyIncomeAmount > 0) {
                txDao.updateOpenDailyIncomeAmount(settings.dailyIncomeAmount)
            } else {
                txDao.deleteOpenDailyIncome()
            }
            maintain(today)
        }
    }

    suspend fun insertTransactions(rows: List<Transaction>) {
        txDao.insertAll(rows.map { it.toEntity() })
    }

    /** Returns the stored rule with its generated id. Occurrences appear on the next maintenance. */
    suspend fun insertRule(rule: RecurringRule): RecurringRule =
        rule.copy(id = ruleDao.insert(rule.toEntity()))

    /** Wipes everything and re-creates default settings. */
    suspend fun clearAll(today: LocalDate) {
        db.withTransaction {
            txDao.deleteAll()
            ruleDao.deleteAll()
            settingsDao.deleteAll()
            maintain(today)
        }
    }

    private suspend fun maintain(today: LocalDate) {
        val settings = settingsDao.get()?.toDomain()
            ?: AppSettings.default(today).also { settingsDao.upsert(it.toEntity()) }
        materializeRules(today)
        catchUpDailyIncome(settings, today)
    }

    private suspend fun materializeRules(today: LocalDate) {
        for (ruleEntity in ruleDao.getAll()) {
            val rule = ruleEntity.toDomain()
            val existing = txDao.occurrenceDates(rule.id).toSet()
            val missing = Recurrence.missingOccurrences(rule, existing, today)
            if (missing.isNotEmpty()) {
                txDao.insertAll(missing.map { rule.occurrenceFor(it).toEntity() })
            }
        }
    }

    private suspend fun catchUpDailyIncome(settings: AppSettings, today: LocalDate) {
        val rows = txDao.dailyIncomeRows().map { it.toDomain() }
        val plan = DailyIncome.catchUp(settings, rows, today)
        if (plan.inserts.isNotEmpty()) txDao.insertAll(plan.inserts.map { it.toEntity() })
        if (plan.updates.isNotEmpty()) txDao.updateAll(plan.updates.map { it.toEntity() })
    }
}
