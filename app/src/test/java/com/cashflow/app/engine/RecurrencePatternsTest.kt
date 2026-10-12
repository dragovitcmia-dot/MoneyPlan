package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RecurrencePatternsTest {
    private fun occ(pattern: RecurrencePattern, start: LocalDate, from: LocalDate, to: LocalDate, end: LocalDate? = null) =
        Recurrence.occurrences(rule(pattern, start, end), from, to)

    private val monThu = RecurrencePattern.WeeklyDays(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))

    // ---- weekday sets --------------------------------------------------------------------------------

    @Test fun a_weekday_set_hits_each_chosen_day_every_week() {
        // Oct 2026: Mondays 5 12 19 26, Thursdays 1 8 15 22 29
        assertEquals(
            listOf(1, 5, 8, 12, 15, 19, 22, 26, 29).map { d(2026, 10, it) },
            occ(monThu, d(2026, 10, 1), d(2026, 10, 1), d(2026, 10, 31)),
        )
    }

    @Test fun a_weekday_set_starts_on_the_first_chosen_day_on_or_after_the_start() {
        // start on a Friday: next is Monday
        assertEquals(d(2026, 10, 12), occ(monThu, d(2026, 10, 9), d(2026, 10, 1), d(2026, 10, 31)).first())
        // start exactly on a chosen day: included
        assertEquals(d(2026, 10, 12), occ(monThu, d(2026, 10, 12), d(2026, 10, 1), d(2026, 10, 31)).first())
    }

    @Test fun a_weekday_set_end_date_is_inclusive_and_stops_everything_after() {
        val all = occ(monThu, d(2026, 10, 1), d(2026, 10, 1), d(2026, 12, 31), end = d(2026, 10, 15))
        assertEquals(d(2026, 10, 15), all.last()) // the end date is a chosen day: included
        val before = occ(monThu, d(2026, 10, 1), d(2026, 10, 1), d(2026, 12, 31), end = d(2026, 10, 14))
        assertEquals(d(2026, 10, 12), before.last()) // the day before: the Thursday is out
    }

    @Test fun all_seven_weekdays_make_a_daily_pattern() {
        val every = RecurrencePattern.WeeklyDays(DayOfWeek.entries.toSet())
        assertEquals(
            occ(RecurrencePattern.Daily, d(2026, 10, 1), d(2026, 10, 1), d(2026, 11, 30)),
            occ(every, d(2026, 10, 1), d(2026, 10, 1), d(2026, 11, 30)),
        )
    }

    @Test fun a_weekday_set_runs_across_a_year_boundary() {
        val sunSat = RecurrencePattern.WeeklyDays(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        assertEquals(
            listOf(d(2026, 12, 26), d(2026, 12, 27), d(2027, 1, 2), d(2027, 1, 3)),
            occ(sunSat, d(2026, 12, 20), d(2026, 12, 26), d(2027, 1, 3)),
        )
    }

    @Test fun the_window_does_not_change_which_dates_occur_for_a_weekday_set() {
        val all = occ(monThu, d(2026, 1, 1), d(2026, 1, 1), d(2026, 12, 31))
        assertEquals(all.filter { it >= d(2026, 6, 1) && it <= d(2026, 6, 30) }, occ(monThu, d(2026, 1, 1), d(2026, 6, 1), d(2026, 6, 30)))
    }

    @Test fun a_weekday_set_materializes_idempotently() {
        val r = rule(monThu, d(2026, 10, 1))
        val rows = RuleMaintenance.materialize(r, emptyList(), d(2026, 10, 1))
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.date.dayOfWeek in setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY) })
        assertTrue(RuleMaintenance.materialize(r, rows, d(2026, 10, 1)).isEmpty())
    }

    // ---- first and last day of the month ----------------------------------------------------------------

    @Test fun first_day_of_every_month_including_a_start_on_the_first_and_after_it() {
        assertEquals(
            listOf(d(2026, 10, 1), d(2026, 11, 1), d(2026, 12, 1)),
            occ(RecurrencePattern.MonthFirst, d(2026, 10, 1), d(2026, 10, 1), d(2026, 12, 31)),
        )
        // start on the 2nd: the first one is next month
        assertEquals(d(2026, 11, 1), occ(RecurrencePattern.MonthFirst, d(2026, 10, 2), d(2026, 10, 1), d(2026, 12, 31)).first())
    }

    @Test fun last_day_of_every_month_handles_month_ends_and_leap_years() {
        assertEquals(
            listOf(d(2027, 1, 31), d(2027, 2, 28), d(2027, 3, 31), d(2027, 4, 30)),
            occ(RecurrencePattern.MonthLast, d(2027, 1, 1), d(2027, 1, 1), d(2027, 4, 30)),
        )
        assertEquals(d(2028, 2, 29), occ(RecurrencePattern.MonthLast, d(2028, 2, 1), d(2028, 2, 1), d(2028, 2, 29)).single()) // leap year
        // start on the last day itself is included; a start after it moves to the next month
        assertEquals(d(2026, 10, 31), occ(RecurrencePattern.MonthLast, d(2026, 10, 31), d(2026, 10, 1), d(2026, 12, 31)).first())
        assertEquals(d(2026, 11, 30), occ(RecurrencePattern.MonthLast, d(2026, 11, 1), d(2026, 10, 1), d(2026, 12, 31)).first())
    }

    @Test fun first_and_last_respect_the_end_date_boundaries() {
        assertEquals(
            listOf(d(2026, 10, 31), d(2026, 11, 30)),
            occ(RecurrencePattern.MonthLast, d(2026, 10, 1), d(2026, 10, 1), d(2027, 1, 31), end = d(2026, 11, 30)),
        )
        assertEquals(
            listOf(d(2026, 11, 1)),
            occ(RecurrencePattern.MonthFirst, d(2026, 10, 2), d(2026, 10, 1), d(2027, 1, 31), end = d(2026, 11, 1)),
        )
        assertTrue(occ(RecurrencePattern.MonthFirst, d(2026, 10, 2), d(2026, 10, 1), d(2027, 1, 31), end = d(2026, 10, 31)).isEmpty())
    }

    // ---- day of month, 31st edge cases -------------------------------------------------------------------

    @Test fun the_31st_falls_on_the_last_day_of_shorter_months() {
        assertEquals(
            listOf(d(2026, 12, 31), d(2027, 1, 31), d(2027, 2, 28), d(2027, 3, 31), d(2027, 4, 30), d(2027, 5, 31)),
            occ(RecurrencePattern.DayOfMonth(31), d(2026, 12, 1), d(2026, 12, 1), d(2027, 5, 31)),
        )
        assertEquals(d(2028, 2, 29), occ(RecurrencePattern.DayOfMonth(31), d(2028, 2, 1), d(2028, 2, 1), d(2028, 2, 29)).single())
        assertEquals(d(2028, 2, 29), occ(RecurrencePattern.DayOfMonth(30), d(2028, 2, 1), d(2028, 2, 1), d(2028, 2, 29)).single())
    }

    @Test fun a_day_of_month_before_the_start_waits_for_next_month() {
        assertEquals(d(2026, 11, 10), occ(RecurrencePattern.DayOfMonth(10), d(2026, 10, 15), d(2026, 10, 1), d(2026, 12, 31)).first())
        assertEquals(d(2026, 10, 15), occ(RecurrencePattern.DayOfMonth(15), d(2026, 10, 15), d(2026, 10, 1), d(2026, 12, 31)).first())
    }

    // ---- every 2 weeks, yearly -----------------------------------------------------------------------------

    @Test fun every_2_weeks_counts_from_the_start_date() {
        val start = d(2026, 10, 15)
        assertEquals(
            listOf(d(2026, 10, 15), d(2026, 10, 29), d(2026, 11, 12), d(2026, 11, 26)),
            occ(RecurrencePattern.EveryNDays(14, start), start, start, d(2026, 11, 30)),
        )
    }

    @Test fun every_existing_pattern_still_works() {
        val from = d(2026, 10, 1)
        val to = d(2026, 12, 31)
        assertEquals(92, occ(RecurrencePattern.Daily, from, from, to).size)
        assertEquals(13, occ(RecurrencePattern.Weekly(DayOfWeek.SUNDAY), from, from, to).size)
        assertEquals(3, occ(RecurrencePattern.MonthFirst, from, from, to).size)
        assertEquals(3, occ(RecurrencePattern.MonthLast, from, from, to).size)
        assertEquals(3, occ(RecurrencePattern.DayOfMonth(5), from, from, to).size)
        assertEquals(1, occ(RecurrencePattern.Yearly(11, 3), from, from, to).size)
        assertEquals(10, occ(RecurrencePattern.EveryNDays(10, from), from, from, to).size)
    }

    @Test fun a_start_after_the_end_gives_nothing() {
        assertTrue(occ(monThu, d(2026, 10, 10), d(2026, 10, 1), d(2026, 12, 31), end = d(2026, 10, 9)).isEmpty())
        assertFalse(occ(monThu, d(2026, 10, 10), d(2026, 10, 1), d(2026, 12, 31), end = d(2026, 10, 12)).isEmpty())
    }
}
