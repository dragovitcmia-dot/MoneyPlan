package com.cashflow.app.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** What the rest of the app knows about series that the rules themselves do not say (kept in the settings store). */
data class SeriesContext(
    /** Series the user ended explicitly: they leave the list once their end date has passed and never get a last-payment reminder. */
    val endedRuleIds: Set<Long> = emptySet(),
    /** Series whose accepted amount is asked each time. */
    val askAmountRuleIds: Set<Long> = emptySet(),
)

/** The short texts that describe a series (always English, dates like "05 OCT"). */
object SeriesText {
    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    /** "Every day", "Mon, Thu", "Monthly on the 5th", "Yearly 05 OCT". */
    fun schedule(p: RecurrencePattern): String = when (p) {
        RecurrencePattern.Daily -> "Every day"
        is RecurrencePattern.Weekly -> "Every ${AppDates.weekdayShort(p.dayOfWeek)}"
        is RecurrencePattern.WeeklyDays -> AppDates.weekdaysText(p.days)
        is RecurrencePattern.EveryNDays -> when (p.n) {
            7 -> "Every week"
            14 -> "Every 2 weeks"
            else -> "Every ${p.n} days"
        }
        RecurrencePattern.MonthFirst -> "Monthly on the 1st"
        RecurrencePattern.MonthLast -> "Monthly on the last day"
        is RecurrencePattern.DayOfMonth -> "Monthly on the ${ordinal(p.day)}"
        is RecurrencePattern.Yearly -> "Yearly " + AppDates.dayMonth(LocalDate.of(2000, p.month, minOf(p.day, YearMonth.of(2000, p.month).lengthOfMonth())))
    }

    /** "Ends 05 DEC 2026" or "No end". */
    fun endLine(rule: RecurringRule): String = rule.endDate?.let { "Ends ${AppDates.format(it)}" } ?: "No end"
}

/** One row of the Repeating list in Upcoming. */
data class SeriesRow(
    val rule: RecurringRule,
    /** The next waiting occurrence (today or later), or null. */
    val nextDate: LocalDate?,
    val schedule: String,
    val endLine: String,
    /** The end date passed without the user ending the series. */
    val ended: Boolean,
)

object SeriesList {
    /**
     * Active series, soonest next occurrence first. A series whose end date passed on its own gets the "Ended" tag;
     * one the user ended explicitly leaves the list once its end date has passed (until then it still runs).
     * A deleted series is simply not in [rules].
     */
    fun build(rules: List<RecurringRule>, rows: List<Transaction>, series: SeriesContext, today: LocalDate): List<SeriesRow> =
        rules.filter { it.enabled }
            .filterNot { it.id in series.endedRuleIds && it.endDate != null && it.endDate.isBefore(today) }
            .map { rule ->
                val next = rows.filter { it.recurringRuleId == rule.id && it.status == TxStatus.PENDING && !it.date.isBefore(today) }.minOfOrNull { it.date }
                SeriesRow(
                    rule = rule,
                    nextDate = next,
                    schedule = SeriesText.schedule(rule.pattern),
                    endLine = SeriesText.endLine(rule),
                    ended = rule.endDate != null && rule.endDate.isBefore(today),
                )
            }
            .sortedWith(compareBy<SeriesRow>({ it.nextDate == null }, { it.nextDate }, { it.rule.id }))
}

/** The last payment of a series that has an end date. */
object SeriesLast {
    /**
     * The date of the last occurrence: the last scheduled date up to the end date that was not skipped. A postponed
     * occurrence counts on the day it was moved to; a date that has no stored row yet counts when it is today or later.
     * Null for a series without an end date or without any such occurrence.
     */
    fun lastDate(rule: RecurringRule, rows: List<Transaction>): LocalDate? {
        val end = rule.endDate ?: return null
        val mine = rows.filter { it.recurringRuleId == rule.id }
        val byOccurrence = mine.mapNotNull { r -> r.occurrenceDate?.let { it to r } }.toMap()
        val from = maxOf(rule.startDate, end.minusDays(Recurrence.HORIZON_DAYS))
        val candidates = Recurrence.occurrences(rule, from, end).mapNotNull { o ->
            val row = byOccurrence[o]
            when {
                row != null -> row.takeIf { it.status != TxStatus.SKIPPED }?.date
                else -> o
            }
        }
        // rows that were moved away from their occurrence date still count where they are
        val moved = mine.filter { it.status != TxStatus.SKIPPED && it.occurrenceDate != null && it.date != it.occurrenceDate }.map { it.date }
        return (candidates + moved).maxOrNull()
    }

    fun lastDates(rules: List<RecurringRule>, rows: List<Transaction>): Map<Long, LocalDate> =
        rules.filter { it.endDate != null }.mapNotNull { r -> lastDate(r, rows)?.let { r.id to it } }.toMap()
}

/** The "Extend" choices of a series. */
sealed interface ExtendChoice {
    data class Months(val n: Int) : ExtendChoice
    data object NoEnd : ExtendChoice
    data class Until(val date: LocalDate) : ExtendChoice

    companion object {
        val QUICK: List<ExtendChoice> = listOf(Months(1), Months(3), Months(12), NoEnd)
    }
}

object SeriesExtend {
    /**
     * The new end date for [choice]: [Months] counts from the current end (or from today when that has passed) and a
     * series without an end stays as it is; [ExtendChoice.NoEnd] removes the end; [ExtendChoice.Until] must lie after
     * the current end. Returns [Unchanged] when nothing would move: the end is never moved earlier.
     */
    sealed interface Result {
        data class End(val date: LocalDate?) : Result
        data object Unchanged : Result
    }

    fun newEnd(rule: RecurringRule, choice: ExtendChoice, today: LocalDate): Result {
        val current = rule.endDate
        return when (choice) {
            is ExtendChoice.Months -> if (current == null) Result.Unchanged else Result.End(maxOf(current, today).plusMonths(choice.n.toLong()))
            ExtendChoice.NoEnd -> if (current == null) Result.Unchanged else Result.End(null)
            is ExtendChoice.Until -> if (current != null && !choice.date.isAfter(current)) Result.Unchanged else Result.End(choice.date)
        }
    }

    /**
     * Applies [choice]: the new end date, and the waiting occurrences up to it are generated (the ones that exist are not
     * duplicated). The start moves to today so an ended series never backfills the days it was off. Null when unchanged.
     */
    fun apply(rule: RecurringRule, rows: List<Transaction>, choice: ExtendChoice, today: LocalDate): RuleChange? {
        val result = newEnd(rule, choice, today) as? Result.End ?: return null
        return extendTo(rule, rows, result.date, today)
    }

    fun extendTo(rule: RecurringRule, rows: List<Transaction>, end: LocalDate?, today: LocalDate): RuleChange {
        val regenerated = rule.copy(endDate = end, startDate = maxOf(rule.startDate, today), enabled = true)
        return RuleChange(regenerated, emptyList(), RuleMaintenance.materialize(regenerated, rows, today))
    }
}

/** What was really paid for a series, from its last accepted occurrences. */
data class PaidSummary(val average: Long, val planned: Long, val count: Int) {
    val text get() = "Last $count average ${Money.formatDisplay(average)}, planned ${Money.formatDisplay(planned)}"
    val updateLabel get() = "Update planned amount to ${Money.formatDisplay(average, withCurrency = false)}"
}

object PaidAverage {
    const val MIN_COUNT = 3
    const val MAX_COUNT = 4

    /**
     * [confirmedNewestFirst] are the amounts of the series' accepted occurrences, newest first. Needs at least
     * [MIN_COUNT], uses up to [MAX_COUNT], and is null when every one equals the plan or the average (to 0.01 TND) is the plan.
     */
    fun of(planned: Long, confirmedNewestFirst: List<Long>): PaidSummary? {
        val used = confirmedNewestFirst.take(MAX_COUNT)
        if (used.size < MIN_COUNT) return null
        if (used.all { it == planned }) return null
        val average = Math.floorDiv(used.sum() * 2 + used.size * 10L, used.size * 20L) * 10L // half up to 0.01 TND
        if (average == Money.roundToCents(planned)) return null
        return PaidSummary(average, planned, used.size)
    }
}

/** What can be done with an item, by its status and whether it belongs to a series. */
data class ItemActionSet(
    val accept: Boolean,
    val reject: Boolean,
    val postpone: Boolean,
    val editItem: Boolean,
    val markWaiting: Boolean,
    val seriesEdit: Boolean,
    val seriesExtend: Boolean,
    val seriesEnd: Boolean,
    val seriesDelete: Boolean,
)

object ItemActionMap {
    /**
     * Every item opens its details. Accept, reject and postpone belong to waiting items (accept and reject once they
     * are due); an answered item can be put back to waiting; the series actions are there for ANY item of a series,
     * whatever its status, because they only affect today and later.
     */
    fun forItem(status: TxStatus, date: LocalDate, today: LocalDate, belongsToSeries: Boolean): ItemActionSet {
        val pending = status == TxStatus.PENDING
        val due = pending && !date.isAfter(today)
        return ItemActionSet(
            accept = due,
            reject = due,
            postpone = pending,
            editItem = true,
            markWaiting = !pending,
            seriesEdit = belongsToSeries,
            seriesExtend = belongsToSeries,
            seriesEnd = belongsToSeries,
            seriesDelete = belongsToSeries,
        )
    }
}

/** The series Edit form: the wizard's own form (When controls) prefilled from the series, and back. */
object SeriesForm {
    fun formFor(rule: RecurringRule, today: LocalDate): EntryFormState {
        val start = Recurrence.occurrences(rule.copy(endDate = null), today, today.plusDays(Recurrence.HORIZON_DAYS * 2)).firstOrNull() ?: today
        val base = EntryFormState.initial(rule.kind, today).copy(
            amountText = Money.formatInput(rule.amount),
            description = rule.description,
            timing = Timing.REPEATS,
            startDate = start,
            hasEnd = rule.endDate != null,
            endDate = rule.endDate ?: start.plusMonths(1),
            weekdays = setOf(start.dayOfWeek),
            dayOfMonth = start.dayOfMonth.toString(),
        )
        return when (val p = rule.pattern) {
            RecurrencePattern.Daily -> base.copy(choice = PatternChoice.DAILY)
            is RecurrencePattern.Weekly -> base.copy(choice = PatternChoice.WEEKLY, weekdays = setOf(p.dayOfWeek))
            is RecurrencePattern.WeeklyDays -> base.copy(choice = PatternChoice.WEEKLY, weekdays = p.days)
            is RecurrencePattern.EveryNDays ->
                if (p.n == 14) base.copy(choice = PatternChoice.EVERY_2_WEEKS) else base.copy(choice = PatternChoice.EVERY_N, everyN = p.n.toString())
            RecurrencePattern.MonthFirst -> base.copy(choice = PatternChoice.MONTH_FIRST)
            RecurrencePattern.MonthLast -> base.copy(choice = PatternChoice.MONTH_LAST)
            is RecurrencePattern.DayOfMonth -> base.copy(choice = PatternChoice.MONTH_DAY, dayOfMonth = p.day.toString())
            is RecurrencePattern.Yearly -> base.copy(choice = PatternChoice.YEARLY)
        }
    }

    /** The edited series, or null while the form has problems. Everything else of [old] (id, start, enabled) is kept. */
    fun ruleFrom(old: RecurringRule, form: EntryFormState): RecurringRule? {
        val entry = (EntryForm.build(form, old.description.ifBlank { EntryPlanner.defaultDescription(old.kind) }) as? FormResult.Valid)?.entry as? NewEntry.Recurring
            ?: return null
        return old.copy(amount = entry.amount, description = entry.description, pattern = entry.pattern, endDate = entry.endDate)
    }
}
