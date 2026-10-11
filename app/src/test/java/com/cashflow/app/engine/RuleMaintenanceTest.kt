package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RuleMaintenanceTest {
    private val today = d(2026, 10, 20)
    private val start = d(2026, 10, 10)

    private fun row(rule: RecurringRule, date: LocalDate, status: TxStatus, id: Long) =
        rule.occurrenceFor(date).copy(id = id, status = status)

    /** An enabled daily source with one row per day Oct 15 .. Oct 25 in assorted states. */
    private fun scenario(): Pair<RecurringRule, List<Transaction>> {
        val src = incomeSource(start = d(2026, 10, 11))
        val rows = (15..25).map { day ->
            val date = d(2026, 10, day)
            val status = when {
                day == 16 -> TxStatus.CONFIRMED
                day == 17 -> TxStatus.SKIPPED
                else -> TxStatus.PENDING
            }
            row(src, date, status, id = day.toLong())
        }
        return src to rows
    }

    // ---- activity / wallet mode ------------------------------------------------------------

    @Test fun static_mode_deactivates_income_sources_but_not_expense_rules() {
        val src = incomeSource(start = start)
        val exp = rule(RecurrencePattern.Daily, start)
        assertTrue(RuleMaintenance.isActive(src, WalletMode.DYNAMIC))
        assertFalse(RuleMaintenance.isActive(src, WalletMode.STATIC))
        assertTrue(RuleMaintenance.isActive(exp, WalletMode.STATIC))
        assertFalse(RuleMaintenance.isActive(src.copy(enabled = false), WalletMode.DYNAMIC))
    }

    @Test fun static_mode_gives_no_income_contribution_and_preserves_sources() {
        val src = incomeSource(start = d(2026, 10, 11))
        val rows = RuleMaintenance.materialize(src, emptyList(), start)
        val dynamic = BalanceEngine(settings(start, 100), rows, d(2026, 10, 15))
        val static = BalanceEngine(settings(start, 100, WalletMode.STATIC), rows, d(2026, 10, 15))
        assertEquals(tnd(100 + 5 * 30), dynamic.available())
        assertEquals(tnd(100), static.available())
        assertTrue(static.forecast(start, d(2026, 12, 31)).all { it.closing == tnd(100) && it.income == 0L })
        assertEquals(0, static.toConfirmCount())
        // Switching mode changes nothing about the source itself.
        assertTrue(src.enabled)
        assertEquals(src, src.copy())
    }

    @Test fun static_mode_still_counts_logged_transactions() {
        val rows = listOf(tx(d(2026, 10, 12), 40, status = TxStatus.CONFIRMED), tx(d(2026, 10, 13), 5, TxKind.INCOME, TxStatus.CONFIRMED))
        val e = BalanceEngine(settings(start, 100, WalletMode.STATIC), rows, d(2026, 10, 14))
        assertEquals(tnd(65), e.currentBalance())
        assertEquals(tnd(65), e.available())
    }

    @Test fun enabling_in_static_mode_generates_nothing_until_dynamic() {
        val src = incomeSource(start = d(2026, 10, 11), enabled = false)
        val change = RuleMaintenance.enable(src, emptyList(), WalletMode.STATIC, today)
        assertTrue(change.rule.enabled)
        assertTrue(change.inserts.isEmpty())
    }

    // ---- disable ---------------------------------------------------------------------------

    @Test fun disabling_deletes_only_pending_rows_dated_after_today() {
        val (src, rows) = scenario()
        val change = RuleMaintenance.disable(src, rows, today)
        assertFalse(change.rule.enabled)
        // Pending after Oct 20: 21..25. Oct 20 (today) and earlier pending rows stay.
        assertEquals((21..25).map { it.toLong() }, change.deleteIds.sorted())
        assertTrue(change.inserts.isEmpty())
    }

    @Test fun disabling_never_touches_confirmed_or_skipped_even_if_future() {
        val src = incomeSource(start = d(2026, 10, 11))
        val rows = listOf(
            row(src, d(2026, 10, 25), TxStatus.CONFIRMED, 1),
            row(src, d(2026, 10, 26), TxStatus.SKIPPED, 2),
            row(src, d(2026, 10, 27), TxStatus.PENDING, 3),
        )
        assertEquals(listOf(3L), RuleMaintenance.disable(src, rows, today).deleteIds)
    }

    // ---- enable ----------------------------------------------------------------------------

    @Test fun enabling_generates_from_today_forward_and_never_backfills() {
        val src = incomeSource(start = d(2026, 10, 11), enabled = false)
        val change = RuleMaintenance.enable(src, emptyList(), WalletMode.DYNAMIC, today)
        val dates = change.inserts.map { it.date }
        assertEquals(today, dates.min())
        assertEquals(today.plusDays(Recurrence.HORIZON_DAYS), dates.max())
        assertEquals(today, change.rule.startDate)
        // A later maintenance run (with the stored, bumped rule) still creates nothing in the past.
        val stored = change.inserts.mapIndexed { i, t -> t.copy(id = i + 1L) }
        assertTrue(RuleMaintenance.materialize(change.rule, stored, today.plusDays(3)).none { it.date < today })
    }

    @Test fun enabling_keeps_existing_rows_and_does_not_duplicate() {
        val src = incomeSource(start = d(2026, 10, 11), enabled = false)
        val existing = listOf(row(src, d(2026, 10, 18), TxStatus.PENDING, 1), row(src, d(2026, 10, 21), TxStatus.CONFIRMED, 2))
        val change = RuleMaintenance.enable(src, existing, WalletMode.DYNAMIC, today)
        assertTrue(change.inserts.none { it.date == d(2026, 10, 21) })
        assertEquals(change.inserts.size, change.inserts.map { it.date }.toSet().size)
        assertTrue(change.deleteIds.isEmpty())
    }

    @Test fun enabling_a_future_start_date_is_kept() {
        val src = incomeSource(start = d(2026, 11, 1), enabled = false)
        val change = RuleMaintenance.enable(src, emptyList(), WalletMode.DYNAMIC, today)
        assertEquals(d(2026, 11, 1), change.rule.startDate)
        assertEquals(d(2026, 11, 1), change.inserts.minOf { it.date })
    }

    // ---- edit ------------------------------------------------------------------------------

    @Test fun editing_amount_replaces_only_pending_future_occurrences() {
        val (src, rows) = scenario()
        val edited = src.copy(amount = tnd(50))
        val change = RuleMaintenance.edit(src, edited, rows, WalletMode.DYNAMIC, today)
        assertEquals((21..25).map { it.toLong() }, change.deleteIds.sorted())
        assertTrue(change.inserts.all { it.amount == tnd(50) && it.status == TxStatus.PENDING })
        // Regenerated from today: today's pending row (Oct 20) exists, so the first new row is Oct 21.
        assertEquals(d(2026, 10, 21), change.inserts.minOf { it.date })
        // Confirmed (16), skipped (17) and past/today pending rows are not touched.
        assertTrue(change.deleteIds.none { it in listOf(16L, 17L, 15L, 18L, 19L, 20L) })
        assertEquals(today, change.rule.startDate)
    }

    @Test fun editing_the_schedule_regenerates_future_rows_on_the_new_schedule() {
        val (src, rows) = scenario()
        val weekly = src.copy(pattern = RecurrencePattern.Weekly(DayOfWeek.MONDAY))
        val change = RuleMaintenance.edit(src, weekly, rows, WalletMode.DYNAMIC, today)
        assertEquals((21..25).map { it.toLong() }, change.deleteIds.sorted())
        assertTrue(change.inserts.all { it.date.dayOfWeek == DayOfWeek.MONDAY && it.date > today })
        assertEquals(d(2026, 10, 26), change.inserts.minOf { it.date })
    }

    @Test fun editing_does_not_resurrect_past_occurrences() {
        val (src, rows) = scenario()
        val change = RuleMaintenance.edit(src, src.copy(amount = tnd(31)), rows, WalletMode.DYNAMIC, today)
        assertTrue(change.inserts.none { it.date < today })
    }

    @Test fun editing_only_the_confirm_time_touches_no_rows() {
        val (src, rows) = scenario()
        val change = RuleMaintenance.edit(src, src.copy(confirmTime = java.time.LocalTime.of(18, 30)), rows, WalletMode.DYNAMIC, today)
        assertTrue(change.deleteIds.isEmpty())
        assertTrue(change.inserts.isEmpty())
        assertEquals(java.time.LocalTime.of(18, 30), change.rule.confirmTime)
    }

    @Test fun editing_a_disabled_source_touches_no_rows() {
        val src = incomeSource(start = d(2026, 10, 11), enabled = false)
        val change = RuleMaintenance.edit(src, src.copy(amount = tnd(99)), emptyList(), WalletMode.DYNAMIC, today)
        assertTrue(change.deleteIds.isEmpty() && change.inserts.isEmpty())
        assertEquals(tnd(99), change.rule.amount)
    }

    @Test fun editing_in_static_mode_deletes_but_does_not_materialize() {
        val (src, rows) = scenario()
        val change = RuleMaintenance.edit(src, src.copy(amount = tnd(50)), rows, WalletMode.STATIC, today)
        assertEquals((21..25).map { it.toLong() }, change.deleteIds.sorted())
        assertTrue(change.inserts.isEmpty())
    }

    // ---- new sources -----------------------------------------------------------------------

    @Test fun new_source_starts_the_day_after_the_balance_start_date_or_today() {
        assertEquals(d(2026, 10, 11), RuleMaintenance.newSourceStartDate(settings(d(2026, 10, 10)), d(2026, 10, 10)))
        assertEquals(d(2026, 10, 20), RuleMaintenance.newSourceStartDate(settings(d(2026, 10, 10)), d(2026, 10, 20)))
        assertEquals(d(2026, 11, 2), RuleMaintenance.newSourceStartDate(settings(d(2026, 11, 1)), d(2026, 10, 20)))
    }

    @Test fun multiple_sources_all_contribute() {
        val a = incomeSource(RecurrencePattern.Daily, d(2026, 10, 11), amount = 30, id = 2)
        val b = incomeSource(RecurrencePattern.Weekly(DayOfWeek.MONDAY), d(2026, 10, 11), amount = 100, id = 3)
        val rows = RuleMaintenance.materialize(a, emptyList(), start) + RuleMaintenance.materialize(b, emptyList(), start)
        val e = BalanceEngine(settings(start, 0), rows, start)
        // Oct 11 (Sun) 30; Oct 12 (Mon) 30+100.
        assertEquals(tnd(160), e.forecast(d(2026, 10, 12), d(2026, 10, 12)).single().closing)
    }
}
