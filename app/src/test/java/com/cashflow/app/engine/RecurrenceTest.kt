package com.cashflow.app.engine

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceTest {
    private fun occ(rule: RecurringRule, from: java.time.LocalDate, to: java.time.LocalDate) =
        Recurrence.occurrences(rule, from, to)

    @Test fun weekly_hits_every_matching_weekday() {
        val r = rule(RecurrencePattern.Weekly(DayOfWeek.SUNDAY), d(2026, 10, 1))
        assertEquals(
            listOf(d(2026, 10, 4), d(2026, 10, 11), d(2026, 10, 18), d(2026, 10, 25)),
            occ(r, d(2026, 10, 1), d(2026, 10, 31)),
        )
    }

    @Test fun weekly_start_date_on_the_weekday_is_included_and_end_date_is_inclusive() {
        val r = rule(RecurrencePattern.Weekly(DayOfWeek.SUNDAY), d(2026, 10, 11), d(2026, 12, 27))
        val all = occ(r, d(2026, 1, 1), d(2027, 1, 1))
        assertEquals(d(2026, 10, 11), all.first())
        assertEquals(d(2026, 12, 27), all.last())
        assertEquals(12, all.size)
    }

    @Test fun window_does_not_change_which_dates_occur() {
        val r = rule(RecurrencePattern.Weekly(DayOfWeek.MONDAY), d(2026, 1, 1))
        assertEquals(listOf(d(2026, 10, 5), d(2026, 10, 12)), occ(r, d(2026, 10, 5), d(2026, 10, 12)))
        assertEquals(listOf(d(2026, 10, 12)), occ(r, d(2026, 10, 6), d(2026, 10, 18)))
    }

    @Test fun every_n_days_counts_from_anchor() {
        val r = rule(RecurrencePattern.EveryNDays(10, d(2026, 10, 10)), d(2026, 10, 10))
        assertEquals(
            listOf(d(2026, 10, 10), d(2026, 10, 20), d(2026, 10, 30), d(2026, 11, 9)),
            occ(r, d(2026, 10, 1), d(2026, 11, 15)),
        )
    }

    @Test fun every_n_days_stays_in_phase_when_window_starts_mid_cycle() {
        val r = rule(RecurrencePattern.EveryNDays(10, d(2026, 10, 10)), d(2026, 10, 10))
        assertEquals(listOf(d(2026, 10, 30), d(2026, 11, 9)), occ(r, d(2026, 10, 22), d(2026, 11, 15)))
    }

    @Test fun every_n_days_anchor_after_start_still_phases_backwards() {
        val r = rule(RecurrencePattern.EveryNDays(7, d(2026, 10, 14)), d(2026, 10, 1))
        assertEquals(d(2026, 10, 7), occ(r, d(2026, 10, 1), d(2026, 10, 31)).first())
    }

    @Test fun every_one_day_is_daily() {
        val r = rule(RecurrencePattern.EveryNDays(1, d(2026, 10, 10)), d(2026, 10, 10), d(2026, 10, 13))
        assertEquals(4, occ(r, d(2026, 10, 1), d(2026, 12, 1)).size)
    }

    @Test fun month_first() {
        val r = rule(RecurrencePattern.MonthFirst, d(2026, 10, 15))
        assertEquals(
            listOf(d(2026, 11, 1), d(2026, 12, 1), d(2027, 1, 1)),
            occ(r, d(2026, 10, 1), d(2027, 1, 31)),
        )
    }

    @Test fun month_first_includes_start_date_when_it_is_the_first() {
        val r = rule(RecurrencePattern.MonthFirst, d(2026, 10, 1))
        assertEquals(d(2026, 10, 1), occ(r, d(2026, 10, 1), d(2026, 12, 31)).first())
    }

    @Test fun month_last_handles_every_month_length_and_leap_years() {
        val r = rule(RecurrencePattern.MonthLast, d(2027, 1, 1))
        val dates = occ(r, d(2027, 1, 1), d(2028, 3, 31))
        assertEquals(15, dates.size)
        assertTrue(d(2027, 2, 28) in dates)
        assertTrue(d(2027, 4, 30) in dates)
        assertTrue(d(2027, 12, 31) in dates)
        assertTrue(d(2028, 2, 29) in dates)
    }

    @Test fun month_last_is_skipped_when_it_precedes_the_start_date() {
        val r = rule(RecurrencePattern.MonthLast, d(2026, 10, 31).plusDays(1))
        assertEquals(d(2026, 11, 30), occ(r, d(2026, 10, 1), d(2026, 12, 31)).first())
    }

    @Test fun day_of_month_regular_day() {
        val r = rule(RecurrencePattern.DayOfMonth(15), d(2026, 10, 1))
        assertEquals(listOf(d(2026, 10, 15), d(2026, 11, 15)), occ(r, d(2026, 10, 1), d(2026, 11, 30)))
    }

    @Test fun day_31_clamps_to_month_end() {
        val r = rule(RecurrencePattern.DayOfMonth(31), d(2027, 1, 1))
        val dates = occ(r, d(2027, 1, 1), d(2027, 6, 30))
        assertEquals(
            listOf(d(2027, 1, 31), d(2027, 2, 28), d(2027, 3, 31), d(2027, 4, 30), d(2027, 5, 31), d(2027, 6, 30)),
            dates,
        )
    }

    @Test fun day_31_in_leap_february_is_the_29th() {
        val r = rule(RecurrencePattern.DayOfMonth(31), d(2028, 2, 1))
        assertEquals(listOf(d(2028, 2, 29)), occ(r, d(2028, 2, 1), d(2028, 2, 29)))
    }

    @Test fun day_30_clamps_in_february_only() {
        val r = rule(RecurrencePattern.DayOfMonth(30), d(2027, 1, 1))
        assertEquals(
            listOf(d(2027, 1, 30), d(2027, 2, 28), d(2027, 3, 30)),
            occ(r, d(2027, 1, 1), d(2027, 3, 31)),
        )
    }

    @Test fun clamped_date_before_start_date_is_dropped() {
        val r = rule(RecurrencePattern.DayOfMonth(31), d(2027, 2, 28).plusDays(1))
        assertEquals(d(2027, 3, 31), occ(r, d(2027, 1, 1), d(2027, 4, 30)).first())
    }

    @Test fun end_before_start_yields_nothing() {
        val r = rule(RecurrencePattern.MonthFirst, d(2026, 10, 1), d(2026, 9, 1))
        assertTrue(occ(r, d(2026, 1, 1), d(2027, 1, 1)).isEmpty())
    }

    @Test fun invalid_patterns_are_rejected() {
        assertTrue(runCatching { RecurrencePattern.DayOfMonth(0) }.isFailure)
        assertTrue(runCatching { RecurrencePattern.DayOfMonth(32) }.isFailure)
        assertTrue(runCatching { RecurrencePattern.EveryNDays(0, d(2026, 1, 1)) }.isFailure)
    }
}

class IncomePatternTest {
    private fun src(p: RecurrencePattern, start: java.time.LocalDate) = incomeSource(p, start)
    private fun occ(r: RecurringRule, from: java.time.LocalDate, to: java.time.LocalDate) = Recurrence.occurrences(r, from, to)

    @Test fun daily_hits_every_day_from_its_start_date() {
        val dates = occ(src(RecurrencePattern.Daily, d(2026, 10, 11)), d(2026, 10, 1), d(2026, 10, 14))
        assertEquals(listOf(11, 12, 13, 14).map { d(2026, 10, it) }, dates)
    }

    @Test fun weekly_income() {
        val dates = occ(src(RecurrencePattern.Weekly(DayOfWeek.FRIDAY), d(2026, 10, 1)), d(2026, 10, 1), d(2026, 10, 31))
        assertEquals(listOf(2, 9, 16, 23, 30).map { d(2026, 10, it) }, dates)
    }

    @Test fun month_first_and_last_income() {
        val first = occ(src(RecurrencePattern.MonthFirst, d(2026, 10, 1)), d(2026, 10, 1), d(2026, 12, 31))
        assertEquals(listOf(d(2026, 10, 1), d(2026, 11, 1), d(2026, 12, 1)), first)
        val last = occ(src(RecurrencePattern.MonthLast, d(2027, 1, 1)), d(2027, 1, 1), d(2027, 3, 31))
        assertEquals(listOf(d(2027, 1, 31), d(2027, 2, 28), d(2027, 3, 31)), last)
    }

    @Test fun day_of_month_income_clamps_to_month_end() {
        val dates = occ(src(RecurrencePattern.DayOfMonth(31), d(2028, 1, 1)), d(2028, 1, 1), d(2028, 4, 30))
        assertEquals(listOf(d(2028, 1, 31), d(2028, 2, 29), d(2028, 3, 31), d(2028, 4, 30)), dates)
        val feb = occ(src(RecurrencePattern.DayOfMonth(30), d(2027, 2, 1)), d(2027, 2, 1), d(2027, 2, 28))
        assertEquals(listOf(d(2027, 2, 28)), feb)
    }
}
