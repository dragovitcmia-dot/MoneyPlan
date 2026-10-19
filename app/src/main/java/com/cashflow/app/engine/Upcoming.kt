package com.cashflow.app.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** The three tabs of Upcoming. */
enum class UpcomingType { ALL, EXPENSES, INCOME }

/**
 * What Upcoming shows: the tab (All, Expenses, Income) and, independent of it, whether the repeating SERIES list replaces the
 * occurrence list (the toggle at the end of the tab row). The tab filters both views.
 */
data class UpcomingView(val type: UpcomingType = UpcomingType.ALL, val showSeries: Boolean = false) {
    fun withType(next: UpcomingType) = copy(type = next)
    fun toggleSeries() = copy(showSeries = !showSeries)
    fun withSeries(on: Boolean) = copy(showSeries = on)
}

/** What a tap on something in the Upcoming list does. */
sealed interface UpcomingTap {
    /** Opens the details sheet of the item (with the series actions for a series item). */
    data class OpenDetails(val txId: Long) : UpcomingTap

    /** Opens the sheet of one series. */
    data class OpenSeries(val ruleId: Long) : UpcomingTap

}

/** Headers of the Upcoming list, in display order: Waiting, Tomorrow, This week, then one per month. */
sealed interface UpcomingGroupKey {
    /** PENDING items dated today or earlier: the same ones Home lists under "Waiting for you". */
    data object Waiting : UpcomingGroupKey
    data object Tomorrow : UpcomingGroupKey
    data object ThisWeek : UpcomingGroupKey
    data class Month(val month: YearMonth) : UpcomingGroupKey
}

data class UpcomingRow(
    val tx: Transaction,
    /** Days from today (0 or negative for waiting items). */
    val daysAway: Int,
    /** How far below zero the day closes, or null when it closes at zero or above. */
    val shortBy: Long?,
    /** Comes from a repeating payment or income source. */
    val repeating: Boolean,
    /** Dated today or earlier: can be answered with a swipe. */
    val waiting: Boolean,
    /** The final occurrence of a series that has an end date. */
    val last: Boolean = false,
)

data class UpcomingGroup(val key: UpcomingGroupKey, val rows: List<UpcomingRow>)

data class UpcomingModel(val groups: List<UpcomingGroup>) {
    val isEmpty get() = groups.isEmpty()
}

/** A series that repeats more often than weekly: daily, every 2..6 days, or two or more weekdays a week. */
fun RecurrencePattern.repeatsMoreOftenThanWeekly(): Boolean = when (this) {
    RecurrencePattern.Daily -> true
    is RecurrencePattern.EveryNDays -> n < 7
    is RecurrencePattern.WeeklyDays -> days.size >= 2
    else -> false
}

object Upcoming {
    const val HORIZON_DAYS = 120L

    /**
     * Group of a date. Today and anything earlier is "Waiting" (so a PENDING item dated today sits with the
     * ones Home asks about); tomorrow; the rest of this week (the week of [today] in the locale's week
     * layout); otherwise its month.
     */
    fun groupOf(date: LocalDate, today: LocalDate, firstDayOfWeek: DayOfWeek): UpcomingGroupKey {
        if (!date.isAfter(today)) return UpcomingGroupKey.Waiting
        if (date == today.plusDays(1)) return UpcomingGroupKey.Tomorrow
        val lastDayOfWeek = firstDayOfWeek.minus(1)
        val endOfWeek = today.with(TemporalAdjusters.nextOrSame(lastDayOfWeek))
        if (!date.isAfter(endOfWeek)) return UpcomingGroupKey.ThisWeek
        return UpcomingGroupKey.Month(YearMonth.from(date))
    }

    private fun order(key: UpcomingGroupKey): Long = when (key) {
        UpcomingGroupKey.Waiting -> 0
        UpcomingGroupKey.Tomorrow -> 1
        UpcomingGroupKey.ThisWeek -> 2
        is UpcomingGroupKey.Month -> 3 + key.month.year * 12L + key.month.monthValue
    }

    /** Every row of the list opens its details. */
    fun tapFor(row: UpcomingRow): UpcomingTap = UpcomingTap.OpenDetails(row.tx.id)

    /**
     * The list does not flood: a series that repeats more often than weekly contributes its waiting rows (today or earlier, they
     * wait for an answer) and only its NEXT future occurrence. Everything else is kept as is.
     */
    fun collapseFrequent(rows: List<Transaction>, rules: List<RecurringRule>, today: LocalDate): List<Transaction> {
        val frequent = rules.filter { it.pattern.repeatsMoreOftenThanWeekly() }.map { it.id }.toSet()
        val seen = HashSet<Long>()
        val keepIndex = HashSet<Int>()
        rows.withIndex().sortedWith(compareBy<IndexedValue<Transaction>>({ it.value.date }, { it.value.id }, { it.index })).forEach { (i, tx) ->
            val rule = tx.recurringRuleId
            if (rule == null || rule !in frequent || !tx.date.isAfter(today) || seen.add(rule)) keepIndex += i
        }
        return rows.filterIndexed { i, _ -> i in keepIndex }
    }

    fun build(
        engine: BalanceEngine,
        rules: List<RecurringRule>,
        type: UpcomingType,
        firstDayOfWeek: DayOfWeek,
    ): UpcomingModel {
        val today = engine.today
        val end = today.plusDays(HORIZON_DAYS)
        val days = engine.forecast(engine.settings.balanceStartDate, end)
        val closing = days.associate { it.date to it.closing }
        val lastDates = SeriesLast.lastDates(rules, engine.transactions)

        val pending = days.asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.status == TxStatus.PENDING }
            .filter { type == UpcomingType.ALL || (type == UpcomingType.EXPENSES) == (it.kind == TxKind.EXPENSE) }
            .toList()
        val rows = collapseFrequent(pending, rules, today).asSequence()
            .map { tx ->
                val close = closing[tx.date] ?: 0L
                UpcomingRow(
                    tx = tx,
                    daysAway = ChronoUnit.DAYS.between(today, tx.date).toInt(),
                    shortBy = if (close < 0) -close else null,
                    repeating = tx.recurringRuleId != null,
                    waiting = !tx.date.isAfter(today),
                    last = tx.recurringRuleId?.let { lastDates[it] } == tx.date && tx.status != TxStatus.SKIPPED,
                )
            }
            .toList()

        val groups = rows
            .groupBy { groupOf(it.tx.date, today, firstDayOfWeek) }
            .map { (key, list) -> UpcomingGroup(key, list.sortedWith(compareBy<UpcomingRow> { it.tx.date }.thenBy { it.tx.id })) }
            .sortedBy { order(it.key) }

        return UpcomingModel(groups)
    }
}
