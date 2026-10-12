package com.cashflow.app.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class UpcomingFilter { ALL, EXPENSES, INCOME }

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
)

data class UpcomingGroup(val key: UpcomingGroupKey, val rows: List<UpcomingRow>)

data class UpcomingModel(
    /** Total of the enabled DAILY income sources, pinned on top; null when there is none to show. */
    val dailyIncome: Long?,
    val groups: List<UpcomingGroup>,
) {
    val isEmpty get() = groups.isEmpty() && dailyIncome == null
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

    fun build(
        engine: BalanceEngine,
        rules: List<RecurringRule>,
        filter: UpcomingFilter,
        firstDayOfWeek: DayOfWeek,
    ): UpcomingModel {
        val today = engine.today
        val end = today.plusDays(HORIZON_DAYS)
        val days = engine.forecast(engine.settings.balanceStartDate, end)
        val closing = days.associate { it.date to it.closing }
        val dailyRuleIds = rules.filter { it.pattern == RecurrencePattern.Daily && it.kind == TxKind.INCOME }.map { it.id }.toSet()

        val rows = days.asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.status == TxStatus.PENDING }
            .filter { filter == UpcomingFilter.ALL || (filter == UpcomingFilter.EXPENSES) == (it.kind == TxKind.EXPENSE) }
            // Future occurrences of a daily income source are summarized in the pinned row, not listed.
            .filter { !(it.date.isAfter(today) && it.source == TxSource.RECURRING && it.kind == TxKind.INCOME && it.recurringRuleId in dailyRuleIds) }
            .map { tx ->
                val close = closing[tx.date] ?: 0L
                UpcomingRow(
                    tx = tx,
                    daysAway = ChronoUnit.DAYS.between(today, tx.date).toInt(),
                    shortBy = if (close < 0) -close else null,
                    repeating = tx.recurringRuleId != null,
                    waiting = !tx.date.isAfter(today),
                )
            }
            .toList()

        val groups = rows
            .groupBy { groupOf(it.tx.date, today, firstDayOfWeek) }
            .map { (key, list) -> UpcomingGroup(key, list.sortedWith(compareBy<UpcomingRow> { it.tx.date }.thenBy { it.tx.id })) }
            .sortedBy { order(it.key) }

        val dailyTotal = if (engine.settings.walletMode == WalletMode.DYNAMIC && filter != UpcomingFilter.EXPENSES) {
            rules.filter {
                it.enabled && it.kind == TxKind.INCOME && it.pattern == RecurrencePattern.Daily &&
                    (it.endDate == null || !it.endDate.isBefore(today))
            }.sumOf { it.amount }.takeIf { it > 0 }
        } else {
            null
        }
        return UpcomingModel(dailyTotal, groups)
    }
}
