package com.cashflow.app.engine

import java.time.LocalDate

/** Outcome of an income-source / rule change: what to store, which rows to delete, which to add. */
data class RuleChange(
    val rule: RecurringRule,
    val deleteIds: List<Long>,
    val inserts: List<Transaction>,
)

/**
 * Pure rules for keeping a recurring rule's materialized rows in step with the rule.
 * [rows] is always the stored rows of that one rule. CONFIRMED and SKIPPED rows are never touched,
 * and PENDING rows dated today or earlier are left for the user to answer.
 */
object RuleMaintenance {
    /** A series generates entries while it is switched on. */
    fun isActive(rule: RecurringRule) = rule.enabled

    /** Start date for a freshly created source: never in the past, and after the balance start day. */
    fun newSourceStartDate(settings: AppSettings, today: LocalDate): LocalDate =
        maxOf(today, settings.balanceStartDate.plusDays(1))

    /** Rows the rule is missing within the horizon (idempotent). */
    fun materialize(rule: RecurringRule, rows: List<Transaction>, today: LocalDate): List<Transaction> {
        val existing = rows.mapNotNull { it.occurrenceDate }.toSet()
        return Recurrence.missingOccurrences(rule, existing, today).map { rule.occurrenceFor(it) }
    }

    /** Disabling removes PENDING occurrences dated after today. */
    fun disable(rule: RecurringRule, rows: List<Transaction>, today: LocalDate) =
        RuleChange(rule.copy(enabled = false), futurePending(rows, today), emptyList())

    /** Enabling generates from today forward; the start date moves up so later runs never backfill. */
    fun enable(rule: RecurringRule, rows: List<Transaction>, today: LocalDate): RuleChange {
        val enabled = rule.copy(enabled = true, startDate = maxOf(rule.startDate, today))
        val inserts = if (isActive(enabled)) materialize(enabled, rows, today) else emptyList()
        return RuleChange(enabled, emptyList(), inserts)
    }

    /**
     * Editing name/amount/schedule of an enabled rule replaces only its PENDING future occurrences,
     * generating from today forward. Other edits (confirm time, ...) touch no rows.
     */
    fun edit(
        old: RecurringRule,
        new: RecurringRule,
        rows: List<Transaction>,
        today: LocalDate,
    ): RuleChange {
        val affectsOccurrences = old.amount != new.amount || old.description != new.description ||
            old.pattern != new.pattern || old.endDate != new.endDate || old.startDate != new.startDate
        if (!new.enabled || !affectsOccurrences) return RuleChange(new, emptyList(), emptyList())
        val regenerated = new.copy(startDate = maxOf(new.startDate, today))
        val deleteIds = futurePending(rows, today)
        val remaining = rows.filter { it.id !in deleteIds }
        val inserts = if (isActive(regenerated)) materialize(regenerated, remaining, today) else emptyList()
        return RuleChange(regenerated, deleteIds, inserts)
    }

    /**
     * "Stop repeating": the series ends today. PENDING occurrences dated after today are removed;
     * everything dated today or earlier (waiting, done, skipped) stays.
     */
    fun stop(rule: RecurringRule, rows: List<Transaction>, today: LocalDate): RuleChange =
        RuleChange(rule.copy(endDate = minOf(today, rule.endDate ?: today)), futurePending(rows, today), emptyList())

    /**
     * "End series" from [from]: the last occurrence is the day before. PENDING occurrences dated [from] or later
     * (and after today) are removed; everything dated today or earlier, and everything accepted or rejected, stays.
     * A series that already ends earlier keeps its earlier end.
     */
    fun endFrom(rule: RecurringRule, rows: List<Transaction>, from: LocalDate, today: LocalDate): RuleChange {
        val last = from.minusDays(1)
        val removed = rows.filter { it.status == TxStatus.PENDING && it.date.isAfter(today) && !it.date.isBefore(from) }.map { it.id }
        return RuleChange(rule.copy(endDate = minOf(last, rule.endDate ?: last)), removed, emptyList())
    }

    /** A series that still produces (or may produce) occurrences: not ended before today. */
    fun isRunning(rule: RecurringRule, today: LocalDate): Boolean = rule.endDate == null || !rule.endDate.isBefore(today)

    private fun futurePending(rows: List<Transaction>, today: LocalDate): List<Long> =
        rows.filter { it.status == TxStatus.PENDING && it.date.isAfter(today) }.map { it.id }
}
