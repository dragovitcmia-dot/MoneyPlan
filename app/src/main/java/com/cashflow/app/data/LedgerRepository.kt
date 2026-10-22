package com.cashflow.app.data

import androidx.room.withTransaction
import com.cashflow.app.engine.AcceptToken
import com.cashflow.app.engine.AcceptUndo
import com.cashflow.app.engine.AppDates
import com.cashflow.app.engine.DeleteSeriesPlan
import com.cashflow.app.engine.HistoryGuard
import com.cashflow.app.engine.SeriesOps
import com.cashflow.app.engine.AppSettings
import com.cashflow.app.engine.ExtendChoice
import com.cashflow.app.engine.SeriesExtend
import com.cashflow.app.engine.DeleteOutcome
import com.cashflow.app.engine.DeletePlan
import com.cashflow.app.engine.Deletions
import com.cashflow.app.engine.ItemEdits
import com.cashflow.app.engine.Postpone
import com.cashflow.app.engine.applyAutoIncome
import com.cashflow.app.engine.RecurringRule
import com.cashflow.app.engine.RuleChange
import com.cashflow.app.engine.RuleMaintenance
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Single access point over Room. All decisions (what to materialize, what to delete on a rule
 * change) come from the pure engine; this class loads rows, asks the engine, and writes the result.
 * Nothing here ever confirms a transaction on its own: only [confirm] / [skip] / [undo] change status.
 */
class LedgerRepository(private val db: AppDatabase, private val autoAccept: AutoAcceptStore, private val seriesFlags: SeriesFlagsStore) {
    private val txDao = db.transactionDao()
    private val ruleDao = db.recurringRuleDao()
    private val settingsDao = db.settingsDao()

    /** Null until the first [runMaintenance] has created the settings row. */
    val settings: Flow<AppSettings?> =
        combine(settingsDao.observe(), autoAccept.enabled) { entity, auto -> entity?.toDomain(auto) }

    // Row mapping can touch thousands of rows, so it runs off the main thread.
    val transactions: Flow<List<Transaction>> =
        txDao.observeAll().map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)
    /** History: confirmed rows from [from] on (a month window), newest first. */
    fun confirmedFrom(from: LocalDate): Flow<List<Transaction>> =
        txDao.observeConfirmedFrom(from).map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    /** History search: confirmed rows whose name contains [query], newest first. */
    fun confirmedMatching(query: String): Flow<List<Transaction>> =
        txDao.observeConfirmedMatching(query).map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    /** The earliest confirmed day, or null. */
    val earliestConfirmed: Flow<LocalDate?> = txDao.observeEarliestConfirmed().map { day -> day?.let(LocalDate::ofEpochDay) }

    val rules: Flow<List<RecurringRule>> =
        ruleDao.observeAll().map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    /** Which series ask for the amount each time and which the user ended explicitly. */
    val seriesContext: kotlinx.coroutines.flow.StateFlow<com.cashflow.app.engine.SeriesContext> = seriesFlags.flags

    /** Run on app start, on resume, and when the day changes. Idempotent; never confirms anything. */
    suspend fun runMaintenance(today: LocalDate) {
        db.withTransaction { maintain(today) }
    }

    suspend fun updateSettings(settings: AppSettings, today: LocalDate) {
        autoAccept.set(settings.autoAcceptIncome)
        db.withTransaction {
            settingsDao.upsert(settings.toEntity())
            maintain(today)
        }
        // Turning the switch on, or moving the "Remind me at" time, may make waiting income acceptable now.
        applyAutoAccept(com.cashflow.app.engine.AppClock.dateTime())
    }

    /**
     * Accepts the waiting income of repeating income series when "accept my income automatically" is on
     * (see [applyAutoIncome]). Run on app start, on every foreground and when the setting changes.
     */
    suspend fun applyAutoAccept(now: LocalDateTime) {
        db.withTransaction {
            val settings = settingsDao.get()?.toDomain(autoAccept.enabled.value) ?: return@withTransaction
            if (!settings.autoAcceptIncome) return@withTransaction
            val changed = applyAutoIncome(now, txDao.pendingRecurringIncome().map { it.toDomain() }, settings)
            if (changed.isNotEmpty()) txDao.setStatusForIds(changed.map { it.id }, TxStatus.CONFIRMED)
        }
    }

    // ---- answering pending rows --------------------------------------------------------------

    /** Accepts a PENDING row dated today or earlier; anything else (a future row, an answered one) is left alone. */
    suspend fun confirm(id: Long, today: LocalDate) {
        if (answerable(id, today)) txDao.setStatus(id, TxStatus.CONFIRMED)
    }

    /** Rejects a PENDING row dated today or earlier. */
    suspend fun skip(id: Long, today: LocalDate) {
        if (answerable(id, today)) txDao.setStatus(id, TxStatus.SKIPPED)
    }

    /** Accepts with a different amount: only this row changes (recurring rules are untouched). */
    suspend fun confirmWithAmount(id: Long, amount: Long, today: LocalDate) {
        db.withTransaction {
            if (!answerable(id, today)) return@withTransaction
            txDao.setAmount(id, amount)
            txDao.setStatus(id, TxStatus.CONFIRMED)
        }
    }

    private suspend fun answerable(id: Long, today: LocalDate): Boolean =
        txDao.get(id)?.toDomain()?.let { ItemEdits.canAnswer(it, today) } ?: false

    /** Back to PENDING; [restoreAmount] puts back the amount an edited confirm had replaced. */
    suspend fun undo(id: Long, restoreAmount: Long? = null) {
        db.withTransaction {
            if (restoreAmount != null) txDao.setAmount(id, restoreAmount)
            txDao.setStatus(id, TxStatus.PENDING)
        }
    }

    // ---- single items (calendar day sheet, Upcoming) ----------------------------------------------

    /** Changes only this row; a generated occurrence keeps its occurrenceDate, so it is never regenerated. */
    suspend fun updateItem(
        id: Long,
        amount: Long,
        description: String,
        date: LocalDate,
        status: TxStatus? = null,
        today: LocalDate = com.cashflow.app.engine.AppClock.today(),
    ) {
        db.withTransaction {
            // Permanent history: only a changeable row (waiting, or rejected from today on) is rewritten, and a changed date is today or later.
            val stored = txDao.get(id)?.toDomain() ?: return@withTransaction
            if (!com.cashflow.app.engine.ItemRules.canSaveChange(stored, date, today)) return@withTransaction
            txDao.updateItem(id, amount, description.trim(), date)
            // An item dated in the future cannot be accepted or rejected yet.
            if (status != null && !date.isAfter(today)) txDao.setStatus(id, status)
        }
    }

    /**
     * Moves one waiting item to today or a later day (an overdue one too). Only the date changes; a generated occurrence keeps its
     * occurrenceDate, so it is not regenerated and the rest of its series is untouched. A move that is not
     * allowed (not waiting, a day before today, the item's own day) does nothing; returns whether the row moved.
     */
    suspend fun postpone(id: Long, newDate: LocalDate, today: LocalDate = com.cashflow.app.engine.AppClock.today()): Boolean = db.withTransaction {
        val tx = txDao.get(id)?.toDomain() ?: return@withTransaction false
        val moved = Postpone.apply(tx, newDate, today) ?: return@withTransaction false
        txDao.updateItem(id, moved.amount, moved.description, moved.date)
        true
    }

    /** Puts a row back on [date] (Undo of a postpone). */
    suspend fun moveTo(id: Long, date: LocalDate) {
        db.withTransaction {
            val tx = txDao.get(id)?.toDomain() ?: return@withTransaction
            txDao.updateItem(id, tx.amount, tx.description, date)
        }
    }

    /**
     * Deletes one item. A manual row is removed; a row generated by a repeating payment is marked SKIPPED
     * instead (removing it would let the next maintenance create it again). Returns what happened.
     */
    suspend fun deleteItem(tx: Transaction, today: LocalDate = com.cashflow.app.engine.AppClock.today()): DeleteOutcome? {
        // Permanent history: a confirmed row, or one dated before today, is never deleted. The stored row decides, not the caller's copy.
        val stored = txDao.get(tx.id)?.toDomain() ?: return null
        if (HistoryGuard.isHistory(stored, today)) return null
        val outcome = ItemEdits.delete(stored)
        when (outcome) {
            is DeleteOutcome.Remove -> txDao.deleteByIds(listOf(outcome.id))
            is DeleteOutcome.MarkSkipped -> txDao.setStatus(outcome.id, TxStatus.SKIPPED)
        }
        return outcome
    }

    /**
     * "This and all future" for a repeating item: the series ends the day before the item and its waiting occurrences
     * from then on are removed (accepted and rejected rows stay). Returns what is needed to undo it, or null when the
     * item has no series any more.
     */
    suspend fun deleteThisAndFuture(tx: Transaction, today: LocalDate): DeletePlan.EndSeries? = db.withTransaction {
        val ruleId = tx.recurringRuleId ?: return@withTransaction null
        val rule = ruleDao.get(ruleId)?.toDomain() ?: return@withTransaction null
        val plan = Deletions.planThisAndFuture(tx, rule, rowsOf(ruleId), today)
        apply(plan.change)
        seriesFlags.markEnded(ruleId)
        plan
    }

    /** Undo of [deleteThisAndFuture]: the old series and every removed row come back. */
    suspend fun undoDeleteThisAndFuture(plan: DeletePlan.EndSeries) {
        db.withTransaction {
            ruleDao.update(plan.oldRule.toEntity())
            if (plan.removed.isNotEmpty()) txDao.insertAll(plan.removed.map { it.toEntity() })
        }
        seriesFlags.unmarkEnded(plan.oldRule.id)
    }

    /** Undo of a manual delete: puts the row back (it gets a new id). */
    suspend fun restoreItem(tx: Transaction) {
        txDao.insertAll(listOf(tx.copy(id = 0).toEntity()))
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
                val change = RuleMaintenance.edit(old, rule, rowsOf(old.id), today)
                apply(change)
            }
        }
    }

    suspend fun setRuleEnabled(id: Long, enabled: Boolean, today: LocalDate) {
        db.withTransaction {
            val rule = ruleDao.get(id)?.toDomain() ?: return@withTransaction
            if (rule.enabled == enabled) return@withTransaction
            val rows = rowsOf(id)
            apply(if (enabled) RuleMaintenance.enable(rule, rows, today) else RuleMaintenance.disable(rule, rows, today))
        }
    }

    /** "Stop repeating": the series ends today and its waiting future rows are removed. */
    suspend fun stopSeries(id: Long, today: LocalDate) {
        db.withTransaction {
            val rule = ruleDao.get(id)?.toDomain() ?: return@withTransaction
            apply(RuleMaintenance.stop(rule, rowsOf(id), today))
        }
        seriesFlags.markEnded(id)
    }

    /** "End series" from [from]: no occurrence from that day on; waiting future ones are removed, the past stays. */
    suspend fun endSeriesFrom(id: Long, from: LocalDate, today: LocalDate) {
        db.withTransaction {
            val rule = ruleDao.get(id)?.toDomain() ?: return@withTransaction
            apply(RuleMaintenance.endFrom(rule, rowsOf(id), from, today))
        }
        seriesFlags.markEnded(id)
    }

    /** Removes the rule and its PENDING future rows; rows dated today or earlier are kept. */
    suspend fun deleteRule(id: Long, today: LocalDate) {
        db.withTransaction {
            val rule = ruleDao.get(id)?.toDomain() ?: return@withTransaction
            val plan = SeriesOps.planDelete(rule, rowsOf(id), today)
            if (plan.detachIds.isNotEmpty()) txDao.detach(plan.detachIds) // the past stays as standalone items
            if (plan.removeIds.isNotEmpty()) txDao.deleteByIds(plan.removeIds)
            ruleDao.deleteById(id)
        }
        pruneFlags()
    }

    /** Undo of "End series": the old end date and the removed waiting occurrences come back. */
    suspend fun undoEndSeries(oldRule: RecurringRule, removed: List<Transaction>) {
        db.withTransaction {
            ruleDao.update(oldRule.toEntity())
            if (removed.isNotEmpty()) txDao.insertAll(removed.map { it.toEntity() })
        }
        seriesFlags.unmarkEnded(oldRule.id)
    }

    /** The series and the waiting occurrences "End series" from [from] would remove (for its Undo). */
    suspend fun endSeriesPlan(id: Long, from: LocalDate, today: LocalDate): Pair<RecurringRule, List<Transaction>>? {
        val rule = ruleDao.get(id)?.toDomain() ?: return null
        val rows = rowsOf(id)
        val removedIds = RuleMaintenance.endFrom(rule, rows, from, today).deleteIds.toSet()
        return rule to rows.filter { it.id in removedIds }
    }

    /**
     * Extends a series (also an ended one) and generates its waiting occurrences up to the new end. Returns the new end
     * date as the text to show ("No end" for none), or null when the choice would not move the end.
     */
    suspend fun extendSeries(id: Long, choice: ExtendChoice, today: LocalDate): String? = db.withTransaction {
        val rule = ruleDao.get(id)?.toDomain() ?: return@withTransaction null
        val change = SeriesExtend.apply(rule, rowsOf(id), choice, today) ?: return@withTransaction null
        apply(change)
        seriesFlags.unmarkEnded(id)
        change.rule.endDate?.let { AppDates.format(it) } ?: "No end"
    }

    /** Saves an edited series (waiting future occurrences regenerate) together with its "ask me the amount" switch. */
    suspend fun saveSeries(rule: RecurringRule, askAmount: Boolean, today: LocalDate) {
        val old = ruleDao.get(rule.id)?.toDomain()
        saveRule(rule, today)
        seriesFlags.setAskAmount(rule.id, askAmount)
        // A later end date makes an explicitly ended series run again.
        if (old != null && (rule.endDate == null || (old.endDate != null && rule.endDate.isAfter(old.endDate)))) seriesFlags.unmarkEnded(rule.id)
    }

    suspend fun setAskAmount(ruleId: Long, on: Boolean) = seriesFlags.setAskAmount(ruleId, on)

    /** Everything needed to take a deleted series back: the series, the plan that was executed and the waiting rows that were removed. */
    class DeletedSeries(val rule: RecurringRule, val plan: DeleteSeriesPlan, val removed: List<Transaction>, val ask: Boolean, val ended: Boolean)

    /** Deletes the series like [deleteRule] and returns what [undoDeleteSeries] needs. */
    suspend fun deleteSeriesWithUndo(id: Long, today: LocalDate): DeletedSeries? {
        val rule = ruleDao.get(id)?.toDomain() ?: return null
        val rows = rowsOf(id)
        val plan = SeriesOps.planDelete(rule, rows, today)
        val flags = seriesFlags.flags.value
        deleteRule(id, today)
        return DeletedSeries(rule, plan, rows.filter { it.id in plan.removeIds }, id in flags.askAmountRuleIds, id in flags.endedRuleIds)
    }

    /** The series comes back with its id: detached rows are linked to it again (only the link, never an old copy) and the waiting rows return. */
    suspend fun undoDeleteSeries(d: DeletedSeries) {
        db.withTransaction {
            ruleDao.insert(d.rule.toEntity())
            if (d.plan.detachIds.isNotEmpty()) txDao.relink(d.plan.detachIds, d.rule.id)
            if (d.removed.isNotEmpty()) txDao.insertAll(d.removed.map { it.toEntity() })
        }
        seriesFlags.setAskAmount(d.rule.id, d.ask)
        if (d.ended) seriesFlags.markEnded(d.rule.id)
    }

    /** Undo of an accept: only while the row is still exactly what that accept produced and the few seconds are not over. */
    suspend fun undoAccept(token: AcceptToken, nowMillis: Long = com.cashflow.app.engine.AppClock.nowMillis()) {
        db.withTransaction {
            val restored = AcceptUndo.restore(txDao.get(token.before.id)?.toDomain(), token, nowMillis) ?: return@withTransaction
            txDao.updateItem(restored.id, restored.amount, restored.description, restored.date)
            txDao.setStatus(restored.id, TxStatus.PENDING)
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
        autoAccept.set(false)
        db.withTransaction {
            txDao.deleteAll()
            ruleDao.deleteAll()
            settingsDao.deleteAll()
            maintain(today)
        }
        pruneFlags()
    }

    // ---- internals ---------------------------------------------------------------------------

    /** Forgets per-series flags of series that no longer exist. */
    private suspend fun pruneFlags() = seriesFlags.prune(ruleDao.getAll().map { it.id }.toSet())

    private suspend fun rowsOf(ruleId: Long) = txDao.rowsForRule(ruleId).map { it.toDomain() }

    /**
     * Every rule change goes through here. Deletions are filtered by the history guard (confirmed rows and rows before today are
     * never deleted, whatever the caller asked) and an occurrence that already exists is never inserted again.
     */
    private suspend fun apply(change: RuleChange, today: LocalDate = com.cashflow.app.engine.AppClock.today()) {
        val rows = rowsOf(change.rule.id)
        val safe = HistoryGuard.guarded(change, rows, today)
        ruleDao.update(change.rule.toEntity())
        if (safe.deleteIds.isNotEmpty()) txDao.deleteByIds(safe.deleteIds)
        val fresh = HistoryGuard.freshInserts(safe, rows)
        if (fresh.isNotEmpty()) txDao.insertAll(fresh.map { it.toEntity() })
    }

    private suspend fun maintain(today: LocalDate) {
        settingsDao.get() ?: settingsDao.upsert(AppSettings.default(today).toEntity())
        for (entity in ruleDao.getAll()) {
            val rule = entity.toDomain()
            if (!RuleMaintenance.isActive(rule)) continue
            val missing = RuleMaintenance.materialize(rule, rowsOf(rule.id), today)
            if (missing.isNotEmpty()) txDao.insertAll(missing.map { it.toEntity() })
        }
    }
}
