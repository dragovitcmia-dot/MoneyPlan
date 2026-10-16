package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeStatusTest {
    private val today = d(2026, 10, 12)
    private fun engine(vararg items: Transaction, balance: Long = 500) = BalanceEngine(settings(today, balance), items.toList(), today)

    @Test fun the_next_expense_is_a_quiet_line_with_name_amount_and_date() {
        val e = engine(tx(d(2026, 11, 5), 300, description = "Rent", id = 1))
        val status = HomeStatusLine.of(e)
        assertEquals(HomeStatus.Next("Rent", tnd(300), d(2026, 11, 5)), status)
        assertEquals("Next: Rent −300 TND, 05 NOV", HomeStatusLine.text(status, today))
        assertNull(HomeStatusLine.tapDay(status))
    }

    @Test fun a_coming_shortage_replaces_it_and_opens_that_day() {
        // 500 - 300 (Nov 5) = 200, then 200 - 400 (Nov 10) = -200
        val e = engine(tx(d(2026, 11, 5), 300, description = "Rent", id = 1), tx(d(2026, 11, 10), 400, description = "Car", id = 2))
        val status = HomeStatusLine.of(e)
        assertEquals(HomeStatus.Short(tnd(200), d(2026, 11, 10)), status)
        assertEquals("Short by 200 TND before 10 NOV", HomeStatusLine.text(status, today))
        assertEquals(d(2026, 11, 10), HomeStatusLine.tapDay(status))
    }

    @Test fun the_first_negative_day_is_the_one_named_even_when_a_later_day_is_deeper() {
        val e = engine(tx(d(2026, 10, 20), 600, description = "Laptop", id = 1), tx(d(2026, 10, 25), 100, description = "Phone", id = 2))
        assertEquals(HomeStatus.Short(tnd(100), d(2026, 10, 20)), HomeStatusLine.of(e))
    }

    @Test fun an_expense_without_a_name_has_no_hole_in_the_line() {
        val e = engine(tx(d(2026, 10, 13), 40, id = 1))
        assertEquals("Next: −40 TND, 13 OCT", HomeStatusLine.text(HomeStatusLine.of(e), today))
    }

    @Test fun a_next_expense_in_another_year_keeps_its_year() {
        val e = engine(tx(d(2027, 1, 3), 50, description = "Tax", id = 1), balance = 5000)
        // beyond the 120-day forecast it is still the next expense
        assertEquals("Next: Tax −50 TND, 03 JAN 2027", HomeStatusLine.text(HomeStatusLine.of(e), today))
    }

    @Test fun nothing_to_say_without_expenses_and_income_does_not_count() {
        val none = engine()
        assertEquals(HomeStatus.None, HomeStatusLine.of(none))
        assertNull(HomeStatusLine.text(HomeStatus.None, today))
        val incomeOnly = engine(tx(d(2026, 10, 20), 100, TxKind.INCOME, description = "Gift", id = 1))
        assertEquals(HomeStatus.None, HomeStatusLine.of(incomeOnly))
    }

    @Test fun an_expense_today_is_next_and_a_shortage_today_is_named_today() {
        assertEquals(HomeStatus.Next("Lunch", tnd(15), today), HomeStatusLine.of(engine(tx(today, 15, description = "Lunch", id = 1))))
        val short = HomeStatusLine.of(engine(tx(today, 600, description = "Laptop", id = 1)))
        assertEquals(HomeStatus.Short(tnd(100), today), short)
    }
}
