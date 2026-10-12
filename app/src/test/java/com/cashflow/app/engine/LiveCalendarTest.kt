package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.YearMonth

class LiveCalendarTest {
    private val start = d(2026, 10, 1)
    private val today = d(2026, 10, 10) // a Saturday
    private val oct = YearMonth.of(2026, 10)

    private fun engine(rows: List<Transaction> = emptyList(), balance: Long = 100) = BalanceEngine(settings(start, balance), rows, today)

    private fun planned(amount: Long, kind: TxKind = TxKind.EXPENSE) = { date: java.time.LocalDate ->
        NewEntry.Planned(kind, tnd(amount), "x", date) as NewEntry?
    }

    private fun month(e: BalanceEngine, selected: java.time.LocalDate?, entryAt: (java.time.LocalDate) -> NewEntry?) =
        LiveCalendar.project(e, oct, DayOfWeek.MONDAY, selected, entryAt)

    private fun day(m: LiveMonth, date: java.time.LocalDate) = m.weeks.flatten().first { it.date == date }

    @Test fun balances_are_the_forecast_with_the_item_applied_on_the_chosen_day() {
        val m = month(engine(), d(2026, 10, 12), planned(150))
        assertEquals(tnd(100), day(m, d(2026, 10, 11)).closing)
        assertEquals(tnd(-50), day(m, d(2026, 10, 12)).closing)
        assertEquals(tnd(-50), day(m, d(2026, 10, 25)).closing)
    }

    @Test fun days_that_would_be_negative_turn_red_and_follow_the_tapped_day() {
        val early = month(engine(), d(2026, 10, 12), planned(150))
        assertTrue(day(early, d(2026, 10, 12)).negative)
        assertFalse(day(early, d(2026, 10, 11)).negative)
        val later = month(engine(), d(2026, 10, 20), planned(150))
        assertFalse(day(later, d(2026, 10, 12)).negative) // no longer red once another day is tapped
        assertTrue(day(later, d(2026, 10, 20)).negative)
        assertFalse(day(later, d(2026, 10, 19)).negative)
    }

    @Test fun nothing_is_applied_until_a_day_is_selected() {
        val m = month(engine(), null, planned(150))
        assertTrue(m.weeks.flatten().none { it.negative })
        assertNull(m.summary)
        assertEquals(tnd(100), day(m, d(2026, 10, 20)).closing)
    }

    @Test fun past_days_are_disabled_and_the_selected_day_is_marked() {
        val m = month(engine(), d(2026, 10, 12), planned(10))
        assertFalse(day(m, d(2026, 10, 9)).enabled)
        assertTrue(day(m, d(2026, 10, 10)).enabled) // today can be chosen
        assertTrue(day(m, d(2026, 10, 11)).enabled)
        assertEquals(listOf(d(2026, 10, 12)), m.weeks.flatten().filter { it.selected }.map { it.date })
        assertEquals(tnd(100), day(m, d(2026, 10, 9)).closing) // history is still shown
    }

    @Test fun days_before_the_balance_started_have_no_balance() {
        val m = month(engine(), d(2026, 10, 12), planned(10))
        assertNull(day(m, d(2026, 9, 30)).closing)
        assertFalse(day(m, d(2026, 9, 30)).negative)
        assertFalse(day(m, d(2026, 9, 30)).inMonth)
    }

    @Test fun the_grid_has_five_or_six_rows() {
        assertTrue(month(engine(), d(2026, 10, 12), planned(10)).weeks.size in 5..6)
    }

    @Test fun the_summary_gives_the_balance_on_the_day_and_the_lowest_after_it() {
        val income = tx(d(2026, 10, 14), 200, TxKind.INCOME, id = 1)
        val low = month(engine(listOf(income)), d(2026, 10, 12), planned(150)).summary!!
        assertEquals(d(2026, 10, 12), low.date)
        assertEquals(tnd(-50), low.balanceOnDay)
        assertEquals(tnd(-50), low.lowestAfter)
        assertEquals(d(2026, 10, 12), low.lowestDate)
        // chosen after the income arrived: the balance on the day is also the lowest from then on
        val high = month(engine(listOf(income)), d(2026, 10, 20), planned(150)).summary!!
        assertEquals(tnd(150), high.balanceOnDay)
        assertEquals(tnd(150), high.lowestAfter)
        assertEquals(d(2026, 10, 20), high.lowestDate)
    }

    @Test fun a_repeating_payment_is_applied_from_its_first_day() {
        val weekly = { date: java.time.LocalDate ->
            NewEntry.Recurring(TxKind.EXPENSE, tnd(60), "x", RecurrencePattern.Weekly(date.dayOfWeek), date) as NewEntry?
        }
        val m = month(engine(), d(2026, 10, 11), weekly) // a Sunday
        assertEquals(tnd(40), day(m, d(2026, 10, 11)).closing)
        assertEquals(tnd(40), day(m, d(2026, 10, 17)).closing)
        assertEquals(tnd(-20), day(m, d(2026, 10, 18)).closing)
        assertTrue(day(m, d(2026, 10, 18)).negative)
        assertFalse(day(m, d(2026, 10, 17)).negative)
        // 13 Sundays Oct 11 .. Jan 3 fall within 90 days of the chosen day
        val s = m.summary!!
        assertEquals(tnd(100 - 13 * 60), s.lowestAfter)
        assertEquals(d(2027, 1, 3), s.lowestDate)
    }

    @Test fun an_entry_that_cannot_be_built_applies_nothing() {
        val m = month(engine(), d(2026, 10, 12)) { null }
        assertNotNull(m.summary == null)
        assertTrue(m.weeks.flatten().none { it.negative })
    }

    @Test fun income_raises_the_balances_from_its_day() {
        val m = month(engine(), d(2026, 10, 15), planned(40, TxKind.INCOME))
        assertEquals(tnd(100), day(m, d(2026, 10, 14)).closing)
        assertEquals(tnd(140), day(m, d(2026, 10, 15)).closing)
    }
}
