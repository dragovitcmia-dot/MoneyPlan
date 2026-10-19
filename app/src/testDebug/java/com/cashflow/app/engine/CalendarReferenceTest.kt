package com.cashflow.app.engine

import com.cashflow.app.debug.ReferenceScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.YearMonth

/** The calendar numbers on the user's spreadsheet scenario. */
class CalendarReferenceTest {
    private val today = ReferenceScenario.startDate // Oct 10, 2026

    private val rules = listOf(ReferenceScenario.sundayRule, ReferenceScenario.dailyIncomeSource)

    private fun engine(): BalanceEngine {
        val rows = ReferenceScenario.oneOffExpenses +
            rules.flatMap { r -> Recurrence.missingOccurrences(r, emptySet(), today).map { r.occurrenceFor(it) } }
        return BalanceEngine(ReferenceScenario.settings, rows, today)
    }

    private fun month(ym: YearMonth) = CalendarSummaries.month(engine(), rules, ym, DayOfWeek.MONDAY)

    @Test fun nov_10_and_nov_12_are_the_only_negative_days_of_the_whole_scenario() {
        val flagged = (10..12).flatMap { m -> month(YearMonth.of(2026, m)).weeks.flatten() }
            .filter { it.inMonth && it.negative }.map { it.date }.toSet()
        assertEquals(setOf(d(2026, 11, 10), d(2026, 11, 12)), flagged)
    }

    @Test fun the_cells_show_the_sheet_values() {
        val nov = month(YearMonth.of(2026, 11))
        assertEquals(tnd(-10), nov.day(d(2026, 11, 10))!!.closing)
        assertEquals(tnd(380), nov.day(d(2026, 11, 10))!!.expenses)
        assertEquals(tnd(-13), nov.day(d(2026, 11, 12))!!.closing)
        assertEquals(tnd(63), nov.day(d(2026, 11, 12))!!.expenses)
        assertEquals(tnd(599), month(YearMonth.of(2026, 12)).day(d(2026, 12, 31))!!.closing)
        val oct = month(YearMonth.of(2026, 10))
        assertEquals(tnd(135), oct.day(d(2026, 10, 11))!!.closing)
        assertEquals(tnd(75), oct.day(d(2026, 10, 11))!!.expenses)
        assertEquals(tnd(37), oct.day(d(2026, 10, 12))!!.closing)
        assertEquals(tnd(128), oct.day(d(2026, 10, 12))!!.expenses)
    }

    @Test fun the_daily_income_of_30_is_not_shown_on_any_cell() {
        val all = (10..12).flatMap { m -> month(YearMonth.of(2026, m)).weeks.flatten() }
        assertTrue(all.none { it.hasIncome })
        // ...but it is listed in the day sheet.
        val oct12 = month(YearMonth.of(2026, 10)).day(d(2026, 10, 12))!!
        assertTrue(oct12.items.any { it.fromSeries && it.tx.kind == TxKind.INCOME && it.tx.amount == tnd(30) })
    }

    @Test fun the_banner_says_money_runs_short_on_nov_10() {
        assertEquals(d(2026, 11, 10), CalendarSummaries.firstShortDay(engine()))
    }

    @Test fun sundays_show_the_75_expense() {
        val oct = month(YearMonth.of(2026, 10))
        assertEquals(tnd(75), oct.day(d(2026, 10, 18))!!.expenses)
        assertFalse(oct.day(d(2026, 10, 18))!!.items.none { it.fromSeries && it.tx.kind == TxKind.EXPENSE })
    }
}
