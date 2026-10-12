package com.cashflow.app.engine

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object Recurrence {
    const val HORIZON_DAYS = 400L

    /** All dates in [from, to] on which [rule] occurs, bounded by the rule's start/end dates. */
    fun occurrences(rule: RecurringRule, from: LocalDate, to: LocalDate): List<LocalDate> {
        val lo = maxOf(from, rule.startDate)
        val hi = rule.endDate?.let { minOf(to, it) } ?: to
        if (lo.isAfter(hi)) return emptyList()
        return when (val p = rule.pattern) {
            RecurrencePattern.Daily -> stepped(lo, 1, hi)
            is RecurrencePattern.Weekly -> stepped(lo.with(TemporalAdjusters.nextOrSame(p.dayOfWeek)), 7, hi)
            is RecurrencePattern.EveryNDays -> {
                val offset = Math.floorMod(ChronoUnit.DAYS.between(p.anchorDate, lo), p.n.toLong())
                val first = if (offset == 0L) lo else lo.plusDays(p.n - offset)
                stepped(first, p.n.toLong(), hi)
            }
            RecurrencePattern.MonthFirst -> monthly(lo, hi) { it.atDay(1) }
            RecurrencePattern.MonthLast -> monthly(lo, hi) { it.atEndOfMonth() }
            is RecurrencePattern.DayOfMonth -> monthly(lo, hi) { it.atDay(minOf(p.day, it.lengthOfMonth())) }
            is RecurrencePattern.Yearly -> yearly(lo, hi, p)
            is RecurrencePattern.WeeklyDays -> generateSequence(lo) { it.plusDays(1) }.takeWhile { !it.isAfter(hi) }.filter { it.dayOfWeek in p.days }.toList()
        }
    }

    /**
     * Occurrences within the rolling horizon (rule start .. today + [HORIZON_DAYS]) that are not
     * in [existingOccurrenceDates] yet. Applying the result and calling again returns nothing, so
     * materialization is idempotent.
     */
    fun missingOccurrences(
        rule: RecurringRule,
        existingOccurrenceDates: Set<LocalDate>,
        today: LocalDate,
        horizonDays: Long = HORIZON_DAYS,
    ): List<LocalDate> =
        occurrences(rule, rule.startDate, today.plusDays(horizonDays)).filter { it !in existingOccurrenceDates }

    private fun stepped(first: LocalDate, stepDays: Long, hi: LocalDate): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = first
        while (!d.isAfter(hi)) {
            out += d
            d = d.plusDays(stepDays)
        }
        return out
    }

    private fun monthly(lo: LocalDate, hi: LocalDate, pick: (YearMonth) -> LocalDate): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var ym = YearMonth.from(lo)
        val last = YearMonth.from(hi)
        while (!ym.isAfter(last)) {
            val d = pick(ym)
            if (!d.isBefore(lo) && !d.isAfter(hi)) out += d
            ym = ym.plusMonths(1)
        }
        return out
    }

    private fun yearly(lo: LocalDate, hi: LocalDate, p: RecurrencePattern.Yearly): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        for (year in lo.year..hi.year) {
            val ym = YearMonth.of(year, p.month)
            val d = ym.atDay(minOf(p.day, ym.lengthOfMonth()))
            if (!d.isBefore(lo) && !d.isAfter(hi)) out += d
        }
        return out
    }
}
