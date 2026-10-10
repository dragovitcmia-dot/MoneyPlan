package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyIncomeTest {
    private val start = d(2026, 10, 10)
    private val cfg = settings(start, 100, 30)

    /** Applies a plan to an in-memory row set, like the repository does. */
    private fun apply(rows: List<Transaction>, plan: CatchUpPlan): List<Transaction> {
        val updated = plan.updates.associateBy { it.date }
        return rows.map { updated[it.date] ?: it } + plan.inserts
    }

    @Test fun days_that_ended_unanswered_are_auto_applied_and_today_stays_pending() {
        val today = d(2026, 10, 14)
        val rows = apply(emptyList(), DailyIncome.catchUp(cfg, emptyList(), today))
        assertEquals((11..14).map { d(2026, 10, it) }, rows.map { it.date })
        val ended = rows.filter { it.date < today }
        assertTrue(ended.all { it.autoApplied && it.status == TxStatus.CONFIRMED && it.source == TxSource.DAILY_INCOME })
        val todayRow = rows.single { it.date == today }
        assertEquals(TxStatus.PENDING, todayRow.status)
        assertEquals(false, todayRow.autoApplied)
        assertEquals(tnd(30), todayRow.amount)
    }

    @Test fun start_day_gets_no_income_row() {
        val rows = apply(emptyList(), DailyIncome.catchUp(cfg, emptyList(), start))
        assertTrue(rows.isEmpty())
    }

    @Test fun catch_up_is_idempotent() {
        val today = d(2026, 10, 14)
        val once = apply(emptyList(), DailyIncome.catchUp(cfg, emptyList(), today))
        assertTrue(DailyIncome.catchUp(cfg, once, today).isEmpty)
    }

    @Test fun pending_rows_of_ended_days_are_auto_applied_keeping_their_amount() {
        val old = tx(d(2026, 10, 11), 25, TxKind.INCOME, TxStatus.PENDING, TxSource.DAILY_INCOME, id = 7)
        val plan = DailyIncome.catchUp(cfg, listOf(old), d(2026, 10, 12))
        val updated = plan.updates.single()
        assertEquals(7L, updated.id)
        assertEquals(TxStatus.CONFIRMED, updated.status)
        assertTrue(updated.autoApplied)
        assertEquals(tnd(25), updated.amount)
    }

    @Test fun answered_days_are_left_alone() {
        val confirmed = tx(d(2026, 10, 11), 30, TxKind.INCOME, TxStatus.CONFIRMED, TxSource.DAILY_INCOME, id = 1)
        val skipped = tx(d(2026, 10, 12), 30, TxKind.INCOME, TxStatus.SKIPPED, TxSource.DAILY_INCOME, id = 2)
        val plan = DailyIncome.catchUp(cfg, listOf(confirmed, skipped), d(2026, 10, 14))
        assertTrue(plan.updates.isEmpty())
        assertEquals(listOf(d(2026, 10, 13), d(2026, 10, 14)), plan.inserts.map { it.date })
    }

    @Test fun zero_daily_income_creates_no_rows() {
        assertTrue(DailyIncome.catchUp(settings(start, 100, 0), emptyList(), d(2026, 12, 1)).isEmpty)
    }

    @Test fun app_closed_for_days_loses_nothing_and_pending_expenses_stay_pending() {
        val expense = tx(d(2026, 10, 12), 40, id = 1, description = "bill")
        val today = d(2026, 10, 20)
        val rows = apply(emptyList(), DailyIncome.catchUp(cfg, emptyList(), today))
        val e = BalanceEngine(cfg, rows + expense, today)
        // 10 elapsed income days (Oct 11..20): Oct 11..19 auto-applied = 9 x 30, Oct 20 is pending.
        assertEquals(tnd(100 + 9 * 30), e.currentBalance())
        assertEquals(tnd(100 + 10 * 30 - 40), e.balanceAfterPending())
        // The expense is untouched: same date, still pending, still reported as overdue-pending.
        assertEquals(TxStatus.PENDING, expense.status)
        assertEquals(d(2026, 10, 12), expense.date)
        assertEquals(tnd(100 + 10 * 30 - 40), e.forecast(today, today).single().closing)
    }

    @Test fun late_answer_on_todays_row_moves_it_into_the_current_balance() {
        val today = d(2026, 10, 12)
        val rows = apply(emptyList(), DailyIncome.catchUp(cfg, emptyList(), today))
        val before = BalanceEngine(cfg, rows, today)
        val answered = rows.map { if (it.date == today) it.copy(status = TxStatus.CONFIRMED) else it }
        val after = BalanceEngine(cfg, answered, today)
        assertEquals(before.currentBalance() + tnd(30), after.currentBalance())
        assertEquals(before.balanceAfterPending(), after.balanceAfterPending())
    }
}
