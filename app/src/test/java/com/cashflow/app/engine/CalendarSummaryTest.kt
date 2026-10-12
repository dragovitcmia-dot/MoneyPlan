package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.YearMonth

class CalendarSummaryTest {
    private val start = d(2026, 10, 1)
    private val today = d(2026, 10, 10)

    private fun month(
        rows: List<Transaction>,
        rules: List<RecurringRule> = emptyList(),
        balance: Long = 100,
        ym: YearMonth = YearMonth.of(2026, 10),
    ): MonthSummary {
        val engine = BalanceEngine(settings(start, balance), rows, today)
        return CalendarSummaries.month(engine, rules, ym, DayOfWeek.MONDAY)
    }

    @Test fun cells_carry_the_forecast_numbers_exactly() {
        val rows = listOf(
            tx(d(2026, 10, 12), 30, id = 1), tx(d(2026, 10, 12), 20, id = 2),
            tx(d(2026, 10, 12), 5, TxKind.INCOME, id = 3),
        )
        val engine = BalanceEngine(settings(start, 100), rows, today)
        val m = CalendarSummaries.month(engine, emptyList(), YearMonth.of(2026, 10), DayOfWeek.MONDAY)
        for (day in m.weeks.flatten()) {
            val f = engine.forecast(day.date, day.date).singleOrNull() ?: continue
            assertEquals(f.expenses, day.expenses)
            assertEquals(f.closing, day.closing)
            assertEquals(f.closing < 0, day.negative)
        }
        val oct12 = m.day(d(2026, 10, 12))!!
        assertEquals(tnd(50), oct12.expenses)
        assertEquals(tnd(5), oct12.shownIncome)
        assertEquals(tnd(55), oct12.closing)
        assertTrue(oct12.hasExpenses && oct12.hasIncome)
    }

    @Test fun skipped_rows_are_listed_but_have_no_effect() {
        val rows = listOf(tx(d(2026, 10, 12), 30, status = TxStatus.SKIPPED, id = 1), tx(d(2026, 10, 12), 10, id = 2))
        val day = month(rows).day(d(2026, 10, 12))!!
        assertEquals(tnd(10), day.expenses)
        assertEquals(2, day.items.size)
        assertEquals(TxStatus.SKIPPED, day.items.first { it.tx.id == 1L }.tx.status)
    }

    @Test fun daily_income_is_never_shown_but_other_recurring_income_is() {
        val daily = incomeSource(RecurrencePattern.Daily, d(2026, 10, 2), amount = 30, id = 2)
        val weekly = incomeSource(RecurrencePattern.Weekly(DayOfWeek.MONDAY), d(2026, 10, 2), amount = 50, id = 3)
        val rows = RuleMaintenance.materialize(daily, emptyList(), today) + RuleMaintenance.materialize(weekly, emptyList(), today)
        val m = month(rows, listOf(daily, weekly))
        val monday = m.day(d(2026, 10, 12))!! // a Monday: 30 daily + 50 weekly
        assertEquals(tnd(50), monday.shownIncome)
        assertEquals(2, monday.items.size)
        assertTrue(monday.items.first { it.tx.recurringRuleId == 2L }.dailyIncome)
        assertFalse(monday.items.first { it.tx.recurringRuleId == 3L }.dailyIncome)
        val tuesday = m.day(d(2026, 10, 13))!!
        assertEquals(0L, tuesday.shownIncome)
        assertFalse(tuesday.hasIncome)
        // The daily income still counts in the closing balance.
        assertEquals(m.day(d(2026, 10, 12))!!.closing + tnd(30), tuesday.closing)
    }

    @Test fun negative_flag_follows_the_closing_balance() {
        val rows = listOf(tx(d(2026, 10, 14), 150, id = 1))
        val m = month(rows, balance = 100)
        assertFalse(m.day(d(2026, 10, 13))!!.negative)
        val oct14 = m.day(d(2026, 10, 14))!!
        assertTrue(oct14.negative)
        assertEquals(tnd(-50), oct14.closing)
        // The money stays short on later days too.
        assertTrue(m.day(d(2026, 10, 20))!!.negative)
    }

    @Test fun waiting_badge_counts_pending_items_dated_today_or_earlier_only() {
        val rows = listOf(
            tx(d(2026, 10, 8), 5, id = 1), // overdue, pending
            tx(d(2026, 10, 8), 5, status = TxStatus.CONFIRMED, id = 2),
            tx(d(2026, 10, 8), 5, status = TxStatus.SKIPPED, id = 3),
            tx(d(2026, 10, 10), 5, id = 4), // today, pending
            tx(d(2026, 10, 11), 5, id = 5), // future, pending: planned, not waiting
        )
        val m = month(rows)
        assertEquals(1, m.day(d(2026, 10, 8))!!.waiting)
        assertEquals(1, m.day(d(2026, 10, 10))!!.waiting)
        assertEquals(0, m.day(d(2026, 10, 11))!!.waiting)
        assertEquals(0, m.day(d(2026, 10, 9))!!.waiting)
    }

    @Test fun today_past_and_in_month_flags() {
        val m = month(emptyList())
        val t = m.day(today)!!
        assertTrue(t.isToday)
        assertFalse(t.isPast)
        assertTrue(m.day(d(2026, 10, 9))!!.isPast)
        assertFalse(m.day(d(2026, 10, 11))!!.isPast)
        assertTrue(m.day(d(2026, 10, 31))!!.inMonth)
        assertFalse(m.day(d(2026, 11, 1))!!.inMonth)
        assertFalse(m.day(d(2026, 9, 30))!!.inMonth)
    }

    @Test fun days_before_the_balance_start_have_no_forecast() {
        val m = month(emptyList())
        val before = m.day(d(2026, 9, 30))!!
        assertFalse(before.hasForecast)
        assertFalse(before.negative)
        assertTrue(m.day(d(2026, 10, 1))!!.hasForecast)
    }

    @Test fun the_grid_always_has_six_rows() {
        assertEquals(6, month(emptyList(), ym = YearMonth.of(2026, 10)).weeks.size)
        assertEquals(6, month(emptyList(), ym = YearMonth.of(2026, 8)).weeks.size)
    }

    @Test fun banner_picks_the_first_negative_day() {
        val rows = listOf(tx(d(2026, 11, 3), 130, id = 1), tx(d(2026, 11, 20), 5, id = 2))
        val engine = BalanceEngine(settings(start, 100), rows, today)
        assertEquals(d(2026, 11, 3), CalendarSummaries.firstShortDay(engine))
    }

    @Test fun banner_ignores_past_negative_days_and_is_null_when_nothing_is_short() {
        val past = BalanceEngine(settings(start, 10), listOf(tx(d(2026, 10, 5), 50, id = 1)), today)
        // Oct 5 is before today, but the balance stays negative from then on, so today itself is the first short day.
        assertEquals(today, CalendarSummaries.firstShortDay(past))
        val fine = BalanceEngine(settings(start, 100), listOf(tx(d(2026, 11, 3), 30, id = 1)), today)
        assertNull(CalendarSummaries.firstShortDay(fine))
    }

    @Test fun past_cells_show_history_and_later_cells_show_the_forecast() {
        // Today is Oct 10, balance 100 from Oct 1. Oct 5: an accepted 30 expense and a pending 20 expense.
        val rows = listOf(
            tx(d(2026, 10, 5), 30, status = TxStatus.CONFIRMED, id = 1),
            tx(d(2026, 10, 5), 20, id = 2),
            tx(d(2026, 10, 12), 10, id = 3),
        )
        val m = month(rows)
        assertEquals(tnd(100), m.day(d(2026, 10, 4))!!.closing)
        assertEquals(tnd(70), m.day(d(2026, 10, 5))!!.closing) // history: accepted items only
        assertEquals(tnd(70), m.day(d(2026, 10, 9))!!.closing)
        // Today: Available 70 minus the overdue pending 20 = 50; then the pending 10 on Oct 12.
        assertEquals(tnd(50), m.day(d(2026, 10, 10))!!.closing)
        assertEquals(tnd(40), m.day(d(2026, 10, 12))!!.closing)
        assertTrue(m.day(d(2026, 10, 9))!!.isPast)
    }

    @Test fun a_day_summary_exposes_the_rows_of_the_day_for_the_sheet() {
        val rule = rule(RecurrencePattern.Weekly(DayOfWeek.MONDAY), d(2026, 10, 12), id = 7)
        val rows = RuleMaintenance.materialize(rule, emptyList(), today).map { it.copy(id = it.date.dayOfMonth.toLong()) }
        val day = month(rows, listOf(rule)).day(d(2026, 10, 12))!!
        assertEquals(1, day.items.size)
        assertTrue(day.items.single().fromSeries)
    }
}

class CompactAmountTest {
    @Test fun small_amounts_are_exact() {
        assertEquals("75", CompactAmount.format(tnd(75)))
        assertEquals("128", CompactAmount.format(tnd(128)))
        assertEquals("12.5", CompactAmount.format(12_500))
        assertEquals("0.25", CompactAmount.format(250))
        assertEquals("999.99", CompactAmount.format(999_990))
    }

    @Test fun thousands_use_k_with_one_decimal_below_ten_thousand() {
        assertEquals("1k", CompactAmount.format(tnd(1000)))
        assertEquals("1.3k", CompactAmount.format(tnd(1250)))
        assertEquals("9.9k", CompactAmount.format(tnd(9900)))
        assertEquals("13k", CompactAmount.format(tnd(12_600)))
        assertEquals("125k", CompactAmount.format(tnd(125_000)))
    }

    @Test fun millions_use_m() {
        assertEquals("1M", CompactAmount.format(tnd(1_000_000)))
        assertEquals("2.4M", CompactAmount.format(tnd(2_400_000)))
    }

    @Test fun signed_text_uses_plus_and_minus() {
        assertEquals("-75", CompactAmount.signed(TxKind.EXPENSE, tnd(75)))
        assertEquals("+30", CompactAmount.signed(TxKind.INCOME, tnd(30)))
    }

    @Test fun balances_are_compact_and_signed_only_when_negative() {
        assertEquals("135", CompactAmount.balance(tnd(135)))
        assertEquals("-10", CompactAmount.balance(-tnd(10)))
        assertEquals("0", CompactAmount.balance(0))
        assertEquals("1.2k", CompactAmount.balance(tnd(1200)))
        assertEquals("-1.2k", CompactAmount.balance(-tnd(1200)))
    }

    @Test fun negative_input_is_formatted_by_magnitude() {
        assertEquals("10", CompactAmount.format(-tnd(10)))
    }
}
