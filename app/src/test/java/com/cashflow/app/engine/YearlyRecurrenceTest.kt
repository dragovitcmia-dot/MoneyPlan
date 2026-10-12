package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YearlyRecurrenceTest {
    private fun yearly(month: Int, day: Int, start: java.time.LocalDate, end: java.time.LocalDate? = null) =
        rule(RecurrencePattern.Yearly(month, day), start, end)

    @Test fun one_date_per_year_from_the_start() {
        val r = yearly(10, 15, d(2026, 10, 15))
        assertEquals(listOf(d(2026, 10, 15), d(2027, 10, 15), d(2028, 10, 15)), Recurrence.occurrences(r, d(2026, 1, 1), d(2028, 12, 31)))
    }

    @Test fun the_first_occurrence_is_never_before_the_start_date() {
        val r = yearly(3, 1, d(2026, 10, 15))
        assertEquals(listOf(d(2027, 3, 1)), Recurrence.occurrences(r, d(2026, 1, 1), d(2027, 12, 31)))
    }

    @Test fun the_29th_of_february_falls_on_the_28th_in_other_years() {
        val r = yearly(2, 29, d(2028, 2, 1))
        assertEquals(
            listOf(d(2028, 2, 29), d(2029, 2, 28), d(2030, 2, 28), d(2031, 2, 28), d(2032, 2, 29)),
            Recurrence.occurrences(r, d(2028, 1, 1), d(2032, 12, 31)),
        )
    }

    @Test fun the_end_date_stops_it_and_materialization_is_idempotent() {
        val r = yearly(10, 15, d(2026, 10, 15), end = d(2027, 12, 31))
        assertEquals(listOf(d(2026, 10, 15), d(2027, 10, 15)), Recurrence.occurrences(r, d(2026, 1, 1), d(2035, 1, 1)))
        val rows = RuleMaintenance.materialize(r, emptyList(), d(2026, 10, 15))
        assertTrue(rows.isNotEmpty())
        assertTrue(RuleMaintenance.materialize(r, rows, d(2026, 10, 15)).isEmpty())
    }
}
