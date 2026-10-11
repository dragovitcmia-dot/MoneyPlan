package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** Stopping and editing a repeating EXPENSE (the income-source cases live in RuleMaintenanceTest). */
class SeriesTest {
    private val today = d(2026, 10, 20) // a Tuesday
    private val weekly = rule(RecurrencePattern.Weekly(DayOfWeek.SUNDAY), d(2026, 10, 4), amount = 75, id = 3)

    private fun row(date: LocalDate, status: TxStatus, id: Long) = weekly.occurrenceFor(date).copy(id = id, status = status)

    /** Sundays Oct 4 .. Nov 15 in assorted states. */
    private fun rows(): List<Transaction> = listOf(
        row(d(2026, 10, 4), TxStatus.CONFIRMED, 1),
        row(d(2026, 10, 11), TxStatus.SKIPPED, 2),
        row(d(2026, 10, 18), TxStatus.PENDING, 3), // overdue: waiting for the user
        row(d(2026, 10, 25), TxStatus.PENDING, 4),
        row(d(2026, 11, 1), TxStatus.PENDING, 5),
        row(d(2026, 11, 8), TxStatus.CONFIRMED, 6), // answered in advance
        row(d(2026, 11, 15), TxStatus.SKIPPED, 7),
    )

    // ---- stop repeating ---------------------------------------------------------------------

    @Test fun stopping_sets_the_end_date_to_today_and_removes_only_waiting_future_rows() {
        val change = RuleMaintenance.stop(weekly, rows(), today)
        assertEquals(today, change.rule.endDate)
        assertEquals(listOf(4L, 5L), change.deleteIds.sorted())
        assertTrue(change.inserts.isEmpty())
    }

    @Test fun stopping_keeps_done_skipped_and_overdue_rows() {
        val change = RuleMaintenance.stop(weekly, rows(), today)
        val kept = rows().filter { it.id !in change.deleteIds }.map { it.id }
        assertEquals(listOf(1L, 2L, 3L, 6L, 7L), kept)
    }

    @Test fun stopping_a_pending_row_dated_today_leaves_it_for_the_user() {
        val todayRow = row(today, TxStatus.PENDING, 8)
        val change = RuleMaintenance.stop(weekly, rows() + todayRow, today)
        assertFalse(8L in change.deleteIds)
    }

    @Test fun stopping_an_already_ended_series_keeps_the_earlier_end() {
        val ended = weekly.copy(endDate = d(2026, 10, 12))
        assertEquals(d(2026, 10, 12), RuleMaintenance.stop(ended, rows(), today).rule.endDate)
    }

    @Test fun nothing_is_generated_after_stopping() {
        val change = RuleMaintenance.stop(weekly, rows(), today)
        val remaining = rows().filter { it.id !in change.deleteIds }
        assertTrue(RuleMaintenance.materialize(change.rule, remaining, today).isEmpty())
    }

    @Test fun running_means_not_ended_before_today() {
        assertTrue(RuleMaintenance.isRunning(weekly, today))
        assertTrue(RuleMaintenance.isRunning(weekly.copy(endDate = today), today))
        assertFalse(RuleMaintenance.isRunning(weekly.copy(endDate = today.minusDays(1)), today))
    }

    // ---- editing a repeating expense --------------------------------------------------------

    @Test fun editing_the_amount_regenerates_only_waiting_future_rows() {
        val edited = weekly.copy(amount = tnd(90))
        val change = RuleMaintenance.edit(weekly, edited, rows(), WalletMode.DYNAMIC, today)
        assertEquals(listOf(4L, 5L), change.deleteIds.sorted())
        assertTrue(change.inserts.isNotEmpty())
        assertTrue(change.inserts.all { it.amount == tnd(90) && it.status == TxStatus.PENDING && it.date.isAfter(today) })
        // Occurrences that were answered, skipped or are waiting right now are never recreated.
        val keptDates = rows().filter { it.id !in change.deleteIds }.map { it.occurrenceDate }.toSet()
        assertTrue(change.inserts.none { it.occurrenceDate in keptDates })
    }

    @Test fun editing_the_schedule_moves_future_rows_to_the_new_days() {
        val friday = weekly.copy(pattern = RecurrencePattern.Weekly(DayOfWeek.FRIDAY))
        val change = RuleMaintenance.edit(weekly, friday, rows(), WalletMode.DYNAMIC, today)
        assertTrue(change.inserts.isNotEmpty())
        assertTrue(change.inserts.all { it.date.dayOfWeek == DayOfWeek.FRIDAY })
    }

    @Test fun editing_the_end_date_trims_future_rows() {
        val shorter = weekly.copy(endDate = d(2026, 10, 25))
        val change = RuleMaintenance.edit(weekly, shorter, rows(), WalletMode.DYNAMIC, today)
        assertEquals(listOf(4L, 5L), change.deleteIds.sorted())
        assertEquals(listOf(d(2026, 10, 25)), change.inserts.map { it.date })
    }

    @Test fun a_disabled_expense_rule_generates_nothing() {
        val change = RuleMaintenance.edit(weekly, weekly.copy(amount = tnd(10), enabled = false), rows(), WalletMode.DYNAMIC, today)
        assertTrue(change.deleteIds.isEmpty() && change.inserts.isEmpty())
    }

    @Test fun expense_rules_ignore_the_wallet_mode() {
        val edited = weekly.copy(amount = tnd(90))
        val dynamic = RuleMaintenance.edit(weekly, edited, rows(), WalletMode.DYNAMIC, today)
        val static = RuleMaintenance.edit(weekly, edited, rows(), WalletMode.STATIC, today)
        assertEquals(dynamic.inserts.map { it.date }, static.inserts.map { it.date })
    }

    @Test fun a_name_only_change_still_regenerates_with_the_new_name() {
        val renamed = weekly.copy(description = "Gym")
        val change = RuleMaintenance.edit(weekly, renamed, rows(), WalletMode.DYNAMIC, today)
        assertTrue(change.inserts.isNotEmpty() && change.inserts.all { it.description == "Gym" })
    }
}
