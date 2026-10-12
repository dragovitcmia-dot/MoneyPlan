package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class AutoAcceptTest {
    private val start = d(2026, 10, 10)
    private val on = settings(start, 100, autoAccept = true)
    private val off = settings(start, 100, autoAccept = false)

    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)

    private fun income(day: Int, id: Long, source: TxSource = TxSource.RECURRING, status: TxStatus = TxStatus.PENDING) =
        tx(d(2026, 10, day), 30, TxKind.INCOME, status, source, id)

    private fun ids(rows: List<Transaction>) = rows.map { it.id }.sorted()

    @Test fun nothing_happens_when_the_setting_is_off() {
        val rows = listOf(income(11, 1), income(12, 2))
        assertTrue(applyAutoIncome(at(14, 18), rows, off).isEmpty())
    }

    @Test fun today_is_accepted_only_from_the_reminder_time_on() {
        val rows = listOf(income(14, 1))
        assertTrue(applyAutoIncome(at(14, 11, 59), rows, on).isEmpty())
        assertEquals(listOf(1L), ids(applyAutoIncome(at(14, 12, 0), rows, on)))
        assertEquals(listOf(1L), ids(applyAutoIncome(at(14, 23, 59), rows, on)))
    }

    @Test fun the_gate_follows_the_remind_me_time() {
        val early = on.copy(reminderTime = LocalTime.of(8, 30))
        val rows = listOf(income(14, 1))
        assertTrue(applyAutoIncome(at(14, 8, 29), rows, early).isEmpty())
        assertEquals(listOf(1L), ids(applyAutoIncome(at(14, 8, 30), rows, early)))
        val late = on.copy(reminderTime = LocalTime.of(20, 0))
        assertTrue(applyAutoIncome(at(14, 19, 59), rows, late).isEmpty())
    }

    @Test fun earlier_days_are_caught_up_at_any_time_of_day() {
        val rows = listOf(income(11, 1), income(12, 2), income(13, 3), income(14, 4))
        // 07:00 today: the three earlier days are accepted, today's waits for 12:00.
        assertEquals(listOf(1L, 2L, 3L), ids(applyAutoIncome(at(14, 7), rows, on)))
        // 13:00 today: everything up to today.
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(applyAutoIncome(at(14, 13), rows, on)))
    }

    @Test fun accepted_copies_are_marked_confirmed_and_otherwise_unchanged() {
        val row = income(11, 1)
        val out = applyAutoIncome(at(14, 13), listOf(row), on).single()
        assertEquals(TxStatus.CONFIRMED, out.status)
        assertEquals(row.copy(status = TxStatus.CONFIRMED), out)
    }

    @Test fun one_off_planned_income_is_never_auto_accepted() {
        val oneOff = income(11, 1, source = TxSource.MANUAL)
        assertTrue(applyAutoIncome(at(14, 18), listOf(oneOff), on).isEmpty())
    }

    @Test fun expenses_are_never_auto_accepted_not_even_from_a_series() {
        val manual = tx(d(2026, 10, 11), 5, TxKind.EXPENSE, id = 1)
        val series = tx(d(2026, 10, 12), 5, TxKind.EXPENSE, source = TxSource.RECURRING, id = 2)
        assertTrue(applyAutoIncome(at(14, 18), listOf(manual, series), on).isEmpty())
    }

    @Test fun rows_dated_in_the_future_are_untouched() {
        val rows = listOf(income(15, 1), income(20, 2))
        assertTrue(applyAutoIncome(at(14, 23, 59), rows, on).isEmpty())
    }

    @Test fun answered_rows_are_left_alone() {
        val rows = listOf(income(11, 1, status = TxStatus.CONFIRMED), income(12, 2, status = TxStatus.SKIPPED))
        assertTrue(applyAutoIncome(at(14, 18), rows, on).isEmpty())
    }

    @Test fun rows_before_the_balance_start_date_are_ignored() {
        assertTrue(applyAutoIncome(at(14, 18), listOf(income(9, 1)), on).isEmpty())
    }

    @Test fun it_is_idempotent() {
        val rows = listOf(income(11, 1), income(12, 2), income(14, 3), tx(d(2026, 10, 11), 5, id = 4))
        val now = at(14, 13)
        val first = applyAutoIncome(now, rows, on)
        assertEquals(listOf(1L, 2L, 3L), ids(first))
        val after = rows.map { r -> first.firstOrNull { it.id == r.id } ?: r }
        assertTrue(applyAutoIncome(now, after, on).isEmpty())
    }

    @Test fun an_auto_accepted_row_can_be_taken_back_and_is_then_accepted_again_only_by_the_next_run() {
        val row = income(11, 1)
        val accepted = applyAutoIncome(at(14, 13), listOf(row), on).single()
        val undone = ItemEdits.markWaiting(accepted)
        assertEquals(TxStatus.PENDING, undone.status)
        // The next run (a later foreground) would accept it again: the user turns the setting off to keep it waiting.
        assertEquals(1, applyAutoIncome(at(14, 14), listOf(undone), on).size)
        assertTrue(applyAutoIncome(at(14, 14), listOf(undone), off).isEmpty())
    }

    @Test fun accepted_income_reaches_available() {
        val rows = listOf(income(11, 1), income(12, 2))
        val now = at(14, 13)
        val before = BalanceEngine(on, rows, now.toLocalDate()).available()
        val after = rows.map { r -> applyAutoIncome(now, rows, on).firstOrNull { it.id == r.id } ?: r }
        assertEquals(tnd(100), before)
        assertEquals(tnd(160), BalanceEngine(on, after, now.toLocalDate()).available())
    }
}
