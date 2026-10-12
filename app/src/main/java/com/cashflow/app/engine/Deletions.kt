package com.cashflow.app.engine

import java.time.LocalDate

/** Stored rows and rules, as one value, so deleting and undoing can be checked without a database. */
data class LedgerState(val rows: List<Transaction>, val rules: List<RecurringRule>)

/** What a confirmed delete does, with everything needed to undo it. */
sealed interface DeletePlan {
    val tx: Transaction

    /** A manual item: the row is removed. Undo puts it back. */
    data class RemoveOne(override val tx: Transaction) : DeletePlan

    /** One occurrence of a repeating payment: the row stays and becomes SKIPPED (never hard-deleted). Undo restores its status. */
    data class SkipOne(override val tx: Transaction) : DeletePlan

    /** "This and all future": the series ends the day before; waiting occurrences from [tx]'s day on are removed. */
    data class EndSeries(
        override val tx: Transaction,
        val oldRule: RecurringRule,
        val change: RuleChange,
        val removed: List<Transaction>,
    ) : DeletePlan
}

object Deletions {
    /** Deleting just this item: removed when manual, skipped when it is an occurrence of a series. */
    fun planOne(tx: Transaction): DeletePlan = when (ItemEdits.delete(tx)) {
        is DeleteOutcome.Remove -> DeletePlan.RemoveOne(tx)
        is DeleteOutcome.MarkSkipped -> DeletePlan.SkipOne(tx)
    }

    /**
     * Deleting this occurrence and every later one: the series ends the day before [tx]. Reuses the series end rules
     * ([RuleMaintenance.endFrom]); accepted and rejected rows, and rows dated today or earlier, stay.
     */
    fun planThisAndFuture(tx: Transaction, rule: RecurringRule, rowsOfRule: List<Transaction>, today: LocalDate): DeletePlan.EndSeries {
        val change = RuleMaintenance.endFrom(rule, rowsOfRule, tx.date, today)
        val removed = rowsOfRule.filter { it.id in change.deleteIds }
        return DeletePlan.EndSeries(tx, rule, change, removed)
    }

    fun apply(plan: DeletePlan, ledger: LedgerState): LedgerState = when (plan) {
        is DeletePlan.RemoveOne -> ledger.copy(rows = ledger.rows.filter { it.id != plan.tx.id })
        is DeletePlan.SkipOne -> ledger.copy(rows = ledger.rows.map { if (it.id == plan.tx.id) it.copy(status = TxStatus.SKIPPED) else it })
        is DeletePlan.EndSeries -> ledger.copy(
            rows = ledger.rows.filter { it.id !in plan.change.deleteIds },
            rules = ledger.rules.map { if (it.id == plan.oldRule.id) plan.change.rule else it },
        )
    }

    /** Puts everything back exactly as it was before [apply]. */
    fun undo(plan: DeletePlan, ledger: LedgerState): LedgerState = when (plan) {
        is DeletePlan.RemoveOne -> ledger.copy(rows = ledger.rows + plan.tx)
        is DeletePlan.SkipOne -> ledger.copy(rows = ledger.rows.map { if (it.id == plan.tx.id) plan.tx else it })
        is DeletePlan.EndSeries -> ledger.copy(
            rows = ledger.rows + plan.removed,
            rules = ledger.rules.map { if (it.id == plan.oldRule.id) plan.oldRule else it },
        )
    }
}
