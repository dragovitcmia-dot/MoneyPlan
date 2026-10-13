package com.cashflow.app.engine

import java.time.LocalDate

/**
 * Permanent history: a row that was confirmed, or whose date is before today, is never deleted or rewritten by a delete,
 * end, edit or extend of a series, nor by deleting an item. (Past rows that are still waiting stay until the user answers
 * them; answering is the user's own action on that one item.)
 */
object HistoryGuard {
    fun isHistory(tx: Transaction, today: LocalDate): Boolean = tx.status == TxStatus.CONFIRMED || tx.date.isBefore(today)

    /** The ids among [ids] that may be deleted: only rows that are not history. */
    fun deletable(rows: List<Transaction>, ids: Collection<Long>, today: LocalDate): List<Long> {
        val wanted = ids.toSet()
        return rows.filter { it.id in wanted && !isHistory(it, today) }.map { it.id }
    }

    /** [change] with every deletion that would touch history taken out. */
    fun guarded(change: RuleChange, rows: List<Transaction>, today: LocalDate): RuleChange =
        change.copy(deleteIds = deletable(rows, change.deleteIds, today))

    /** The rule's rows that a change may insert: duplicates of an existing occurrence are never inserted. */
    fun freshInserts(change: RuleChange, rows: List<Transaction>): List<Transaction> {
        val taken = rows.mapNotNull { it.occurrenceDate }.toSet()
        return change.inserts.filter { it.occurrenceDate !in taken }
    }
}

/** Deleting a whole series: its waiting future rows go, every other row stays as a standalone item. */
data class DeleteSeriesPlan(val removeIds: List<Long>, val detachIds: List<Long>)

object SeriesOps {
    fun planDelete(rule: RecurringRule, rows: List<Transaction>, today: LocalDate): DeleteSeriesPlan {
        val remove = HistoryGuard.deletable(rows, RuleMaintenance.disable(rule, rows, today).deleteIds, today)
        return DeleteSeriesPlan(remove, rows.filter { it.id !in remove }.map { it.id })
    }

    /** The rows after the plan: removed ones gone, the rest detached from the rule with amounts, statuses, dates and names untouched. */
    fun applyDelete(rows: List<Transaction>, plan: DeleteSeriesPlan): List<Transaction> =
        rows.filter { it.id !in plan.removeIds }.map { if (it.id in plan.detachIds) it.copy(recurringRuleId = null) else it }

    /**
     * Undo: the detached rows link back to [ruleId] and the removed waiting rows return. Rows are NOT rewritten from an old copy:
     * whatever happened to them since (accepted, edited) is kept; only the link is restored.
     */
    fun undoDelete(rows: List<Transaction>, plan: DeleteSeriesPlan, removed: List<Transaction>, ruleId: Long): List<Transaction> {
        val relinked = rows.map { if (it.id in plan.detachIds && it.recurringRuleId == null) it.copy(recurringRuleId = ruleId) else it }
        val have = relinked.map { it.id }.toSet()
        return relinked + removed.filter { it.id !in have }
    }
}

/** What "Undo" after an accept restores, and when it still may. */
data class AcceptToken(val before: Transaction, val after: Transaction, val acceptedAtMillis: Long)

object AcceptUndo {
    /** About six seconds on screen, plus a little grace for the tap. */
    const val WINDOW_MILLIS = 8_000L

    /** [before] is the row as it was; the accept changes status to CONFIRMED and optionally amount, description and date. */
    fun token(before: Transaction, amount: Long = before.amount, description: String = before.description, date: LocalDate = before.date, nowMillis: Long): AcceptToken =
        AcceptToken(before, before.copy(status = TxStatus.CONFIRMED, amount = amount, description = description, date = date), nowMillis)

    /**
     * The row to put back, or null when Undo no longer applies: too late, or the row is not exactly what that accept produced
     * any more (it was changed again since), so another action is never undone by mistake.
     */
    fun restore(current: Transaction?, token: AcceptToken, nowMillis: Long): Transaction? {
        if (current == null || nowMillis - token.acceptedAtMillis > WINDOW_MILLIS) return null
        val a = token.after
        val same = current.status == TxStatus.CONFIRMED && current.amount == a.amount && current.description == a.description && current.date == a.date
        return if (same) token.before.copy(status = TxStatus.PENDING) else null
    }
}
