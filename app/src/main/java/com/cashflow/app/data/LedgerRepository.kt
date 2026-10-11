package com.cashflow.app.data

import androidx.room.withTransaction
import com.cashflow.app.engine.AppSettings
import com.cashflow.app.engine.RecurringRule
import com.cashflow.app.engine.RuleChange
import com.cashflow.app.engine.RuleMaintenance
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Single access point over Room. All decisions (what to materialize, what to delete on a rule
 * change) come from the pure engine; this class loads rows, asks the engine, and writes the result.
 * Nothing here ever confirms a transaction on its own: only [confirm] / [skip] / [undo] change status.
 */
class LedgerRepository(private val db: AppDatabase) {
    private val txDao = db.transactionDao()
    private val ruleDao = db.recurringRuleDao()
    private val settingsDao = db.settingsDao()

    /** Null until the first [runMaintenance] has created the settings row. */
    val settings: Flow<AppSettings?> = settingsDao.observe().map { it?.toDomain() }
    val transactions: Flow<List<Transaction>> = txDao.observeAll().map { rows -> rows.map { it.toDomain() } }
    val rules: Flow<List<RecurringRule>> = ruleDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** Run on app start, on resume, and when the day changes. Idempotent; never confirms anything. */
    suspend fun runMaintenance(today: LocalDate) {
        db.withTransaction { maintain(today) }
    }

    suspend fun updateSettings(settings: AppSettings, today: LocalDate) {
        db.withTransaction {
            settingsDao.upsert(settings.toEntity())
            maintain(today)
        }
    }

    // ---- answering pending rows --------------------------------------------------------------

    suspend fun confirm(id: Long) = txDao.setStatus(id, TxStatus.CONFIRMED)
    suspend fun skip(id: Long) = txDao.setStatus(id, TxStatus.SKIPPED)

    /** Confirms with a different amount: only this row changes (recurring rules are untouched). */
    suspend fun confirmWithAmount(id: Long, amount: Long) {
        db.withTransaction {
            txDao.setAmount(id, amount)
            txDao.setStatus(id, TxStatus.CONFIRMED)
        }
    }

    /** Back to PENDING; [restoreAmount] puts back the amount an edited confirm had replaced. */
    suspend fun undo(id: Long, restoreAmount: Long? = null) {
        db.withTransaction {
            if (restoreAmount != null) txDao.setAmount(id, restoreAmount)
            txDao.setStatus(id, TxStatus.PENDING)
        }
    }

    // ---- rules / income sources --------------------------------------------------------------

    /** Inserts (id == 0) or edits a rule. Edits replace only PENDING future occurrences. */
    suspend fun saveRule(rule: RecurringRule, today: LocalDate) {
        db.withTransaction {
            if (rule.id == 0L) {
                ruleDao.insert(rule.toEntity())
                maintain(today)
            } else {
                val old = ruleDao.get(rule.id)?.toDomain() ?: return@withTransaction
                val change = RuleMaintenance.edit(old, rule, rowsOf(old.id), mode(), today)
                apply(change)
            }
        }
    }

    suspend fun setRuleEnabled(id: Long, enabled: Boolean, today: LocalDate) {
        db.withTransaction {
            val rule = ruleDao.get(id)?.toDomain() ?: return@withTransaction
            if (rule.enabled == enabled) return@withTransaction
            val rows = rowsOf(id)
            apply(if (enabled) RuleMaintenance.enable(rule, rows, mode(), today) else RuleMaintenance.disable(rule, rows, today))
        }
    }

    /** Removes the rule and its PENDING future rows; rows dated today or earlier are kept. */
    suspend fun deleteRule(id: Long, today: LocalDate) {
        db.withTransaction {
            val rule = ruleDao.get(id)?.toDomain() ?: return@withTransaction
            val ids = RuleMaintenance.disable(rule, rowsOf(id), today).deleteIds
            if (ids.isNotEmpty()) txDao.deleteByIds(ids)
            ruleDao.deleteById(id)
        }
    }

    // ---- seeding / debug ---------------------------------------------------------------------

    suspend fun insertTransactions(rows: List<Transaction>) {
        txDao.insertAll(rows.map { it.toEntity() })
    }

    /** Raw insert (keeps the rule's dates as given). Occurrences appear on the next maintenance. */
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

    // ---- internals ---------------------------------------------------------------------------

    private suspend fun mode() = (settingsDao.get() ?: error("settings missing")).walletMode

    private suspend fun rowsOf(ruleId: Long) = txDao.rowsForRule(ruleId).map { it.toDomain() }

    private suspend fun apply(change: RuleChange) {
        ruleDao.update(change.rule.toEntity())
        if (change.deleteIds.isNotEmpty()) txDao.deleteByIds(change.deleteIds)
        if (change.inserts.isNotEmpty()) txDao.insertAll(change.inserts.map { it.toEntity() })
    }

    private suspend fun maintain(today: LocalDate) {
        val settings = settingsDao.get()?.toDomain()
            ?: AppSettings.default(today).also { settingsDao.upsert(it.toEntity()) }
        for (entity in ruleDao.getAll()) {
            val rule = entity.toDomain()
            if (!RuleMaintenance.isActive(rule, settings.walletMode)) continue
            val missing = RuleMaintenance.materialize(rule, rowsOf(rule.id), today)
            if (missing.isNotEmpty()) txDao.insertAll(missing.map { it.toEntity() })
        }
    }
}
