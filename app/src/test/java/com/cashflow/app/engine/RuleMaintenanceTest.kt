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

    // ---- activity ----------------------------------------------------------------------------

    @Test fun a_series_is_active_exactly_while_it_is_switched_on() {
        val src = incomeSource(start = start)
        val exp = rule(RecurrencePattern.Daily, start)
        assertTrue(RuleMaintenance.isActive(src))
        assertTrue(RuleMaintenance.isActive(exp))
        assertFalse(RuleMaintenance.isActive(src.copy(enabled = false)))
        assertFalse(RuleMaintenance.isActive(exp.copy(enabled = false)))
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
        val change = RuleMaintenance.enable(src, emptyList(), today)
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
        val change = RuleMaintenance.enable(src, existing, today)
        assertTrue(change.inserts.none { it.date == d(2026, 10, 21) })
        assertEquals(change.inserts.size, change.inserts.map { it.date }.toSet().size)
        assertTrue(change.deleteIds.isEmpty())
    }

    @Test fun enabling_a_future_start_date_is_kept() {
        val src = incomeSource(start = d(2026, 11, 1), enabled = false)
        val change = RuleMaintenance.enable(src, emptyList(), today)
        assertEquals(d(2026, 11, 1), change.rule.startDate)
        assertEquals(d(2026, 11, 1), change.inserts.minOf { it.date })
    }

    // ---- edit ------------------------------------------------------------------------------

    @Test fun editing_amount_replaces_only_pending_future_occurrences() {
        val (src, rows) = scenario()
        val edited = src.copy(amount = tnd(50))
        val change = RuleMaintenance.edit(src, edited, rows, today)
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
        val change = RuleMaintenance.edit(src, weekly, rows, today)
        assertEquals((21..25).map { it.toLong() }, change.deleteIds.sorted())
        assertTrue(change.inserts.all { it.date.dayOfWeek == DayOfWeek.MONDAY && it.date > today })
        assertEquals(d(2026, 10, 26), change.inserts.minOf { it.date })
    }

    @Test fun editing_does_not_resurrect_past_occurrences() {
        val (src, rows) = scenario()
        val change = RuleMaintenance.edit(src, src.copy(amount = tnd(31)), rows, today)
        assertTrue(change.inserts.none { it.date < today })
    }

    @Test fun editing_only_the_confirm_time_touches_no_rows() {
        val (src, rows) = scenario()
        val change = RuleMaintenance.edit(src, src.copy(confirmTime = java.time.LocalTime.of(18, 30)), rows, today)
        assertTrue(change.deleteIds.isEmpty())
        assertTrue(change.inserts.isEmpty())
        assertEquals(java.time.LocalTime.of(18, 30), change.rule.confirmTime)
    }

    @Test fun editing_a_disabled_source_touches_no_rows() {
        val src = incomeSource(start = d(2026, 10, 11), enabled = false)
        val change = RuleMaintenance.edit(src, src.copy(amount = tnd(99)), emptyList(), today)
        assertTrue(change.deleteIds.isEmpty() && change.inserts.isEmpty())
        assertEquals(tnd(99), change.rule.amount)
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

    // ---- End series ----------------------------------------------------------------------------

    @Test fun end_from_a_future_day_removes_waiting_items_from_that_day_and_keeps_the_rest() {
        val (src, rows) = scenario()
        val withAccepted = rows + row(src, d(2026, 10, 24), TxStatus.CONFIRMED, id = 30)
        val change = RuleMaintenance.endFrom(src, withAccepted, d(2026, 10, 23), today)
        assertEquals(d(2026, 10, 22), change.rule.endDate)
        assertEquals(listOf(23L, 24L, 25L), change.deleteIds.sorted()) // the waiting ones from Oct 23 on
        assertTrue(30L !in change.deleteIds) // an accepted row is never deleted
        assertTrue(change.inserts.isEmpty())
    }

    @Test fun end_from_a_past_day_leaves_waiting_items_dated_today_or_earlier_for_the_user() {
        val (src, rows) = scenario()
        val change = RuleMaintenance.endFrom(src, rows, d(2026, 10, 18), today)
        assertEquals(d(2026, 10, 17), change.rule.endDate)
        assertEquals((21L..25L).toList(), change.deleteIds.sorted()) // only pending rows after today
    }

    @Test fun ending_never_extends_a_series_that_already_ends_earlier() {
        val (src, rows) = scenario()
        val ends = src.copy(endDate = d(2026, 10, 21))
        assertEquals(d(2026, 10, 21), RuleMaintenance.endFrom(ends, rows, d(2026, 10, 23), today).rule.endDate)
    }

    @Test fun editing_name_and_amount_regenerates_only_waiting_future_occurrences() {
        val (src, rows) = scenario()
        val change = RuleMaintenance.edit(src, src.copy(amount = tnd(50), description = "New name"), rows, today)
        assertTrue(change.deleteIds.all { id -> rows.first { it.id == id }.let { it.status == TxStatus.PENDING && it.date.isAfter(today) } })
        assertTrue(16L !in change.deleteIds && 17L !in change.deleteIds) // accepted and rejected rows stay
        assertTrue(change.inserts.all { it.amount == tnd(50) && it.description == "New name" && it.status == TxStatus.PENDING })
    }
}
