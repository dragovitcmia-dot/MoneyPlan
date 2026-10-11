package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nothing is ever confirmed automatically; confirm / skip / undo are the only ways rows change. */
class StrictConfirmationTest {
    private val start = d(2026, 10, 10)
    private val cfg = settings(start, 100)

    private fun e(today: java.time.LocalDate, rows: List<Transaction>) = BalanceEngine(cfg, rows, today)

    /** Applies repository-style status changes to an in-memory row set. */
    private fun List<Transaction>.with(id: Long, status: TxStatus) = map { if (it.id == id) it.copy(status = status) else it }

    @Test fun overdue_income_and_expenses_stay_pending_after_days_without_the_app() {
        val source = incomeSource(start = d(2026, 10, 11))
        val today = d(2026, 10, 20)
        // The app was closed for days: maintenance on this launch only *creates* rows (as PENDING).
        val rows = RuleMaintenance.materialize(source, emptyList(), today) + tx(d(2026, 10, 12), 40, id = 1)
        assertTrue(rows.all { it.status == TxStatus.PENDING })
        assertEquals(tnd(100), e(today, rows).currentBalance())
        // 10 income days (Oct 11..20) and the expense are all still waiting to be confirmed.
        assertEquals(11, e(today, rows).toConfirmCount())
        assertEquals(tnd(100 + 10 * 30 - 40), e(today, rows).available())
    }

    @Test fun to_confirm_is_oldest_first_and_includes_income_and_expenses() {
        val rows = listOf(
            tx(d(2026, 10, 14), 1, id = 3),
            tx(d(2026, 10, 12), 1, TxKind.INCOME, id = 2),
            tx(d(2026, 10, 12), 1, id = 1),
            tx(d(2026, 10, 15), 1, id = 4), // today
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), e(d(2026, 10, 15), rows).toConfirm().map { it.id })
    }

    @Test fun to_confirm_excludes_future_confirmed_skipped_and_pre_start_rows() {
        val rows = listOf(
            tx(d(2026, 10, 16), 1, id = 1), // future
            tx(d(2026, 10, 12), 1, status = TxStatus.CONFIRMED, id = 2),
            tx(d(2026, 10, 12), 1, status = TxStatus.SKIPPED, id = 3),
            tx(d(2026, 10, 9), 1, id = 4), // before the balance start date
            tx(d(2026, 10, 13), 1, id = 5),
        )
        val engine = e(d(2026, 10, 15), rows)
        assertEquals(listOf(5L), engine.toConfirm().map { it.id })
        assertEquals(1, engine.toConfirmCount())
    }

    @Test fun confirming_an_expense_moves_it_into_current_balance_and_keeps_available() {
        val rows = listOf(tx(d(2026, 10, 12), 40, id = 1))
        val before = e(d(2026, 10, 14), rows)
        val after = e(d(2026, 10, 14), rows.with(1, TxStatus.CONFIRMED))
        assertEquals(tnd(100), before.currentBalance())
        assertEquals(tnd(60), before.available())
        assertEquals(tnd(60), after.currentBalance())
        assertEquals(tnd(60), after.available())
        assertEquals(1, before.toConfirmCount())
        assertEquals(0, after.toConfirmCount())
    }

    @Test fun confirming_income_raises_current_balance() {
        val rows = listOf(tx(d(2026, 10, 12), 30, TxKind.INCOME, source = TxSource.RECURRING, id = 1))
        val after = e(d(2026, 10, 14), rows.with(1, TxStatus.CONFIRMED))
        assertEquals(tnd(130), after.currentBalance())
        assertEquals(tnd(130), after.available())
    }

    @Test fun skipping_removes_the_effect_from_both_figures() {
        val rows = listOf(tx(d(2026, 10, 12), 40, id = 1), tx(d(2026, 10, 12), 30, TxKind.INCOME, id = 2))
        val skipped = e(d(2026, 10, 14), rows.with(1, TxStatus.SKIPPED).with(2, TxStatus.SKIPPED))
        assertEquals(tnd(100), skipped.currentBalance())
        assertEquals(tnd(100), skipped.available())
        assertEquals(0, skipped.toConfirmCount())
    }

    @Test fun undo_returns_a_row_to_pending_and_reverses_the_effect() {
        val rows = listOf(tx(d(2026, 10, 12), 40, id = 1))
        val confirmed = rows.with(1, TxStatus.CONFIRMED)
        val undone = confirmed.with(1, TxStatus.PENDING)
        assertEquals(tnd(60), e(d(2026, 10, 14), confirmed).currentBalance())
        assertEquals(tnd(100), e(d(2026, 10, 14), undone).currentBalance())
        assertEquals(tnd(60), e(d(2026, 10, 14), undone).available())
        assertEquals(1, e(d(2026, 10, 14), undone).toConfirmCount())
        val skippedThenUndone = rows.with(1, TxStatus.SKIPPED).with(1, TxStatus.PENDING)
        assertEquals(1, e(d(2026, 10, 14), skippedThenUndone).toConfirmCount())
    }

    @Test fun count_updates_as_rows_are_answered() {
        var rows = listOf(tx(d(2026, 10, 11), 1, id = 1), tx(d(2026, 10, 12), 1, id = 2), tx(d(2026, 10, 13), 1, id = 3))
        assertEquals(3, e(d(2026, 10, 14), rows).toConfirmCount())
        rows = rows.with(1, TxStatus.CONFIRMED)
        assertEquals(2, e(d(2026, 10, 14), rows).toConfirmCount())
        rows = rows.with(2, TxStatus.SKIPPED)
        assertEquals(1, e(d(2026, 10, 14), rows).toConfirmCount())
    }

    @Test fun nothing_to_confirm_means_all_caught_up() {
        assertFalse(e(d(2026, 10, 14), emptyList()).toConfirm().isNotEmpty())
    }
}
