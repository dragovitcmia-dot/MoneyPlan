package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedDipTest {
    private val start = d(2026, 10, 1)
    private val today = d(2026, 10, 10) // horizon end: 90 days later = 2027-01-08

    private fun engine(rows: List<Transaction> = emptyList(), balance: Long = 100) = BalanceEngine(settings(start, balance), rows, today)

    private fun planned(amount: Long, date: java.time.LocalDate, kind: TxKind = TxKind.EXPENSE) =
        NewEntry.Planned(kind, tnd(amount), "x", date)

    @Test fun nothing_going_negative_means_no_warning() {
        assertNull(RedDipCheck.check(engine(), planned(30, d(2026, 10, 12))))
        assertNull(RedDipCheck.check(engine(), planned(100, d(2026, 10, 12)))) // exactly zero is not negative
    }

    @Test fun a_newly_negative_day_is_reported_with_the_lowest_balance_and_the_shortfall() {
        val dip = RedDipCheck.check(engine(), planned(150, d(2026, 10, 12)))!!
        assertEquals(d(2026, 10, 12), dip.days.first().date)
        assertEquals(tnd(-50), dip.days.first().closing)
        assertEquals(tnd(100), dip.days.first().before)
        assertEquals(89, dip.days.size) // every day from Oct 12 to Jan 8 stays red
        assertEquals(tnd(-50), dip.lowest)
        assertEquals(d(2026, 10, 12), dip.lowestDate)
        assertEquals(tnd(50), dip.shortfall)
    }

    @Test fun a_day_that_was_already_negative_and_gets_worse_is_reported_only_from_where_it_gets_worse() {
        val existing = tx(d(2026, 10, 12), 200, id = 1) // already closes at -100 from Oct 12
        val dip = RedDipCheck.check(engine(listOf(existing)), planned(30, d(2026, 10, 15)))!!
        assertEquals(d(2026, 10, 15), dip.days.first().date) // Oct 12-14 are unchanged, so they are not "newly" red
        assertEquals(tnd(-130), dip.days.first().closing)
        assertEquals(tnd(-100), dip.days.first().before)
        assertEquals(86, dip.days.size)
        assertEquals(tnd(-130), dip.lowest)
        assertEquals(tnd(130), dip.shortfall)
    }

    @Test fun an_item_that_does_not_touch_the_negative_stretch_is_not_a_new_warning() {
        val existing = tx(d(2026, 10, 12), 200, id = 1)
        // income only helps; and an expense dated beyond the 90 days is not looked at
        assertNull(RedDipCheck.check(engine(listOf(existing)), planned(50, d(2026, 10, 14), TxKind.INCOME)))
        assertNull(RedDipCheck.check(engine(), planned(500, d(2027, 1, 20))))
    }

    @Test fun a_repeating_payment_is_checked_over_its_occurrences_in_the_next_90_days() {
        val weekly = { amount: Long, from: java.time.LocalDate ->
            NewEntry.Recurring(TxKind.EXPENSE, tnd(amount), "x", RecurrencePattern.Weekly(from.dayOfWeek), from)
        }
        val dip = RedDipCheck.check(engine(), weekly(60, d(2026, 10, 11)))!! // Sundays: 100, 40, -20, -80, ...
        assertEquals(d(2026, 10, 18), dip.days.first().date)
        assertEquals(tnd(-20), dip.days.first().closing)
        assertEquals(83, dip.days.size)
        assertEquals(tnd(100 - 13 * 60), dip.lowest) // 13 Sundays up to Jan 3
        assertEquals(d(2027, 1, 3), dip.lowestDate)
        assertEquals(tnd(680), dip.shortfall)
    }

    @Test fun a_small_repeating_payment_that_never_goes_red_does_not_warn() {
        val weekly = NewEntry.Recurring(TxKind.EXPENSE, tnd(5), "x", RecurrencePattern.Weekly(java.time.DayOfWeek.SUNDAY), d(2026, 10, 11))
        assertNull(RedDipCheck.check(engine(), weekly)) // 100 - 13 * 5 = 35
    }

    @Test fun a_repeating_payment_whose_trouble_starts_after_90_days_is_not_flagged() {
        val monthly = NewEntry.Recurring(TxKind.EXPENSE, tnd(300), "x", RecurrencePattern.DayOfMonth(1), d(2027, 2, 1))
        assertNull(RedDipCheck.check(engine(), monthly))
    }

    @Test fun a_now_expense_bigger_than_available_warns_from_today() {
        val dip = RedDipCheck.check(engine(), NewEntry.Now(TxKind.EXPENSE, tnd(150), "x"))!!
        assertEquals(today, dip.days.first().date)
        assertEquals(tnd(-50), dip.days.first().closing)
        assertEquals(tnd(100), dip.days.first().before)
        assertEquals(91, dip.days.size)
        assertEquals(tnd(50), dip.shortfall)
        assertNull(RedDipCheck.check(engine(), NewEntry.Now(TxKind.EXPENSE, tnd(100), "x"))) // exactly Available
        assertNull(RedDipCheck.check(engine(), NewEntry.Now(TxKind.EXPENSE, tnd(40), "x")))
    }

    @Test fun income_never_warns() {
        assertNull(RedDipCheck.check(engine(), NewEntry.Now(TxKind.INCOME, tnd(500), "x")))
        assertNull(RedDipCheck.check(engine(), planned(500, d(2026, 10, 12), TxKind.INCOME)))
    }

    // ---- name suggestions ------------------------------------------------------------------------------

    private fun named(name: String, day: Int, id: Long, kind: TxKind = TxKind.EXPENSE) =
        tx(d(2026, 10, day), 5, kind, id = id, description = name)

    @Test fun recent_names_come_newest_first_without_repeats() {
        val rows = listOf(named("Bus", 1, 1), named("Coffee", 5, 2), named("bus", 7, 3), named("Rent", 3, 4), named("", 9, 5))
        assertEquals(listOf("bus", "Coffee", "Rent"), NameSuggestions.recent(rows, TxKind.EXPENSE))
    }

    @Test fun suggestions_are_per_kind_and_limited() {
        val rows = (1..10).map { named("n$it", it, it.toLong()) } + named("Salary", 2, 50, TxKind.INCOME)
        assertEquals(listOf("Salary"), NameSuggestions.recent(rows, TxKind.INCOME))
        val expenses = NameSuggestions.recent(rows, TxKind.EXPENSE, limit = 4)
        assertEquals(listOf("n10", "n9", "n8", "n7"), expenses)
        assertTrue(NameSuggestions.recent(emptyList(), TxKind.EXPENSE).isEmpty())
        assertNotNull(expenses)
    }
}
