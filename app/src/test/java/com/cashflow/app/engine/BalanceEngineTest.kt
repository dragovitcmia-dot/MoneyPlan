package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceEngineTest {
    private val start = d(2026, 10, 10)

    private fun engine(
        today: java.time.LocalDate,
        txs: List<Transaction> = emptyList(),
        balance: Long = 100,
        income: Long = 0,
    ) = BalanceEngine(settings(start, balance, income), txs, today)

    // ---- day semantics ---------------------------------------------------------------------

    @Test fun start_day_has_no_daily_income_and_opening_is_the_starting_balance() {
        val day = engine(start, income = 30).forecast(start, start).single()
        assertEquals(tnd(100), day.opening)
        assertEquals(0L, day.income)
        assertEquals(tnd(100), day.closing)
    }

    @Test fun daily_income_is_first_added_the_next_day() {
        val days = engine(start, income = 30).forecast(start, start.plusDays(2))
        assertEquals(listOf(100L, 130L, 160L).map(::tnd), days.map { it.closing })
        assertEquals(tnd(130), days[1].opening)
        assertEquals(tnd(30), days[1].income)
    }

    @Test fun expense_on_start_day_reduces_the_start_day_closing() {
        val e = engine(start, listOf(tx(start, 40)), income = 30)
        assertEquals(tnd(60), e.forecast(start, start).single().closing)
    }

    @Test fun one_off_income_is_added_after_opening() {
        val e = engine(start, listOf(tx(d(2026, 10, 11), 50, kind = TxKind.INCOME)), income = 30)
        val day = e.forecast(d(2026, 10, 11), d(2026, 10, 11)).single()
        assertEquals(tnd(130), day.opening)
        assertEquals(tnd(50), day.extraIncome)
        assertEquals(tnd(180), day.closing)
    }

    @Test fun items_list_every_row_of_the_day_including_skipped() {
        val a = tx(d(2026, 10, 11), 5)
        val b = tx(d(2026, 10, 11), 7, status = TxStatus.SKIPPED)
        val day = engine(start, listOf(a, b)).forecast(d(2026, 10, 11), d(2026, 10, 11)).single()
        assertEquals(listOf(a, b), day.items)
        assertEquals(tnd(5), day.expenses)
    }

    @Test fun forecast_window_is_clamped_to_the_balance_start_date() {
        val days = engine(start).forecast(start.minusDays(5), start.plusDays(1))
        assertEquals(listOf(start, start.plusDays(1)), days.map { it.date })
        assertTrue(engine(start).forecast(start.minusDays(5), start.minusDays(1)).isEmpty())
    }

    // ---- current balance -------------------------------------------------------------------

    @Test fun current_balance_counts_only_confirmed_rows_up_to_today() {
        val today = d(2026, 10, 15)
        val txs = listOf(
            tx(d(2026, 10, 11), 10, status = TxStatus.CONFIRMED),
            tx(d(2026, 10, 12), 20, status = TxStatus.PENDING),
            tx(d(2026, 10, 13), 30, status = TxStatus.SKIPPED),
            tx(d(2026, 10, 14), 5, kind = TxKind.INCOME, status = TxStatus.CONFIRMED),
            tx(d(2026, 10, 20), 999, status = TxStatus.CONFIRMED), // future-dated
            tx(d(2026, 10, 9), 999, status = TxStatus.CONFIRMED), // before the balance start
        )
        assertEquals(tnd(100 - 10 + 5), engine(today, txs).currentBalance())
    }

    @Test fun current_balance_includes_daily_income_only_when_confirmed_or_auto_applied() {
        val today = d(2026, 10, 14)
        fun income(day: Int, status: TxStatus, auto: Boolean = false) =
            tx(d(2026, 10, day), 30, TxKind.INCOME, status, TxSource.DAILY_INCOME, autoApplied = auto)
        val txs = listOf(
            income(11, TxStatus.CONFIRMED),
            income(12, TxStatus.CONFIRMED, auto = true),
            income(13, TxStatus.SKIPPED),
            income(14, TxStatus.PENDING),
        )
        assertEquals(tnd(100 + 30 + 30), engine(today, txs, income = 30).currentBalance())
    }

    @Test fun daily_income_without_a_stored_row_is_not_part_of_the_current_balance() {
        assertEquals(tnd(100), engine(d(2026, 10, 20), income = 30).currentBalance())
    }

    @Test fun daily_income_row_on_the_start_day_is_ignored() {
        val row = tx(start, 30, TxKind.INCOME, TxStatus.CONFIRMED, TxSource.DAILY_INCOME)
        assertEquals(tnd(100), engine(start, listOf(row), income = 30).currentBalance())
    }

    @Test fun today_before_start_date_gives_the_starting_balance() {
        val e = engine(start.minusDays(3), listOf(tx(start, 10, status = TxStatus.CONFIRMED)), income = 30)
        assertEquals(tnd(100), e.currentBalance())
        assertEquals(tnd(100), e.balanceAfterPending())
    }

    // ---- pending handling ------------------------------------------------------------------

    @Test fun pending_expenses_up_to_today_reduce_after_pending_but_not_current() {
        val today = d(2026, 10, 12)
        val txs = listOf(tx(d(2026, 10, 10), 10), tx(d(2026, 10, 12), 25), tx(d(2026, 10, 13), 99))
        val e = engine(today, txs)
        assertEquals(tnd(100), e.currentBalance())
        assertEquals(tnd(65), e.balanceAfterPending())
    }

    @Test fun pending_incomes_up_to_today_count_as_received_in_after_pending() {
        val e = engine(d(2026, 10, 12), listOf(tx(d(2026, 10, 11), 40, kind = TxKind.INCOME)))
        assertEquals(tnd(140), e.balanceAfterPending())
    }

    @Test fun skipped_rows_have_no_effect_anywhere() {
        val today = d(2026, 10, 12)
        val txs = listOf(
            tx(d(2026, 10, 11), 50, status = TxStatus.SKIPPED),
            tx(d(2026, 10, 14), 50, status = TxStatus.SKIPPED),
            tx(d(2026, 10, 13), 50, TxKind.INCOME, TxStatus.SKIPPED),
        )
        val e = engine(today, txs)
        assertEquals(tnd(100), e.currentBalance())
        assertEquals(tnd(100), e.balanceAfterPending())
        assertTrue(e.forecast(start, d(2026, 10, 20)).all { it.closing == tnd(100) })
        assertNull(e.nextExpense())
    }

    @Test fun overdue_pending_items_keep_their_date_and_stay_pending() {
        val overdue = tx(d(2026, 10, 11), 30, id = 1)
        val e = engine(d(2026, 10, 18), listOf(overdue))
        // After-pending still counts it as spent on its own day in the forecast.
        assertEquals(tnd(70), e.forecast(d(2026, 10, 11), d(2026, 10, 11)).single().closing)
        assertEquals(tnd(70), e.balanceAfterPending())
        assertEquals(tnd(100), e.currentBalance())
        assertEquals(listOf(overdue), e.forecast(d(2026, 10, 11), d(2026, 10, 11)).single().items)
    }

    @Test fun after_pending_equals_forecast_closing_of_today() {
        val today = d(2026, 10, 14)
        val txs = listOf(
            tx(d(2026, 10, 11), 10, status = TxStatus.CONFIRMED),
            tx(d(2026, 10, 12), 20),
            tx(d(2026, 10, 14), 5),
            tx(d(2026, 10, 16), 500),
        )
        val e = engine(today, txs, income = 30)
        assertEquals(e.forecast(today, today).single().closing, e.balanceAfterPending())
    }

    @Test fun after_pending_equals_current_when_nothing_is_pending() {
        val today = d(2026, 10, 12)
        val rows = listOf(
            tx(d(2026, 10, 11), 10, status = TxStatus.CONFIRMED),
            tx(d(2026, 10, 11), 30, TxKind.INCOME, TxStatus.CONFIRMED, TxSource.DAILY_INCOME),
            tx(d(2026, 10, 12), 30, TxKind.INCOME, TxStatus.CONFIRMED, TxSource.DAILY_INCOME),
        )
        val e = engine(today, rows, income = 30)
        assertEquals(e.currentBalance(), e.balanceAfterPending())
        assertEquals(tnd(150), e.currentBalance())
    }

    @Test fun forecast_prefers_a_stored_daily_income_row_over_the_planned_amount() {
        val row = tx(d(2026, 10, 11), 45, TxKind.INCOME, TxStatus.CONFIRMED, TxSource.DAILY_INCOME)
        assertEquals(tnd(145), engine(start, listOf(row), income = 30).forecast(d(2026, 10, 11), d(2026, 10, 11)).single().closing)
    }

    // ---- nextExpense / negativeDays --------------------------------------------------------

    @Test fun next_expense_is_the_earliest_pending_expense_from_today() {
        val today = d(2026, 10, 12)
        val txs = listOf(
            tx(d(2026, 10, 10), 1, id = 1), // overdue, not "next"
            tx(d(2026, 10, 12), 2, status = TxStatus.CONFIRMED, id = 2),
            tx(d(2026, 10, 13), 3, status = TxStatus.SKIPPED, id = 3),
            tx(d(2026, 10, 13), 4, TxKind.INCOME, id = 4),
            tx(d(2026, 10, 20), 5, id = 5, description = "later"),
            tx(d(2026, 10, 15), 6, id = 6, description = "rent"),
        )
        val next = engine(today, txs).nextExpense()!!
        assertEquals(d(2026, 10, 15), next.date)
        assertEquals(3, next.daysAway)
        assertEquals(tnd(6), next.amount)
        assertEquals("rent", next.description)
        assertEquals(6L, next.transactionId)
    }

    @Test fun next_expense_today_is_zero_days_away() {
        val next = engine(d(2026, 10, 12), listOf(tx(d(2026, 10, 12), 8))).nextExpense()!!
        assertEquals(0, next.daysAway)
    }

    @Test fun next_expense_ties_on_a_date_pick_the_lowest_id() {
        val txs = listOf(tx(d(2026, 10, 15), 8, id = 9, description = "b"), tx(d(2026, 10, 15), 9, id = 4, description = "a"))
        assertEquals("a", engine(d(2026, 10, 12), txs).nextExpense()!!.description)
    }

    @Test fun no_upcoming_expenses() {
        assertNull(engine(d(2026, 10, 12)).nextExpense())
        assertNull(engine(d(2026, 10, 12), listOf(tx(d(2026, 10, 11), 5))).nextExpense())
    }

    @Test fun negative_days_are_days_with_negative_closing_from_today() {
        val e = engine(start, listOf(tx(d(2026, 10, 12), 150)), income = 30)
        // 100, 130, 10, 40...
        assertTrue(e.negativeDays().isEmpty())
        val e2 = engine(start, listOf(tx(d(2026, 10, 12), 250)), income = 30)
        // 100, 130, -90, -60, -30, 0, 30
        assertEquals(listOf(d(2026, 10, 12), d(2026, 10, 13), d(2026, 10, 14)), e2.negativeDays())
    }

    @Test fun negative_days_only_look_forward_from_today_by_default() {
        val e = engine(d(2026, 10, 14), listOf(tx(d(2026, 10, 12), 250)), income = 0, balance = 100)
        // closing is negative from Oct 12 on, and stays negative; only days >= today are reported
        assertEquals(d(2026, 10, 14), e.negativeDays(toDate = d(2026, 10, 16)).first())
    }
}
