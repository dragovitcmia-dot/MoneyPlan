package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class DeletionsTest {
    private val today = d(2026, 10, 10)
    private val sundays = rule(RecurrencePattern.Weekly(DayOfWeek.SUNDAY), d(2026, 10, 1), amount = 10, id = 7)

    private fun occurrences() = listOf(
        d(2026, 10, 4) to TxStatus.CONFIRMED, // accepted in the past
        d(2026, 10, 11) to TxStatus.PENDING,
        d(2026, 10, 18) to TxStatus.PENDING,
        d(2026, 10, 25) to TxStatus.PENDING,
        d(2026, 11, 1) to TxStatus.SKIPPED, // rejected in advance
    ).mapIndexed { i, (date, status) -> sundays.occurrenceFor(date).copy(id = 10L + i, status = status) }

    private fun ledger(extra: List<Transaction> = emptyList()) = LedgerState(occurrences() + extra, listOf(sundays))

    // ---- a manual item ---------------------------------------------------------------------------------

    @Test fun deleting_a_manual_item_removes_it_and_undo_puts_it_back() {
        val manual = tx(d(2026, 10, 20), 30, id = 1, description = "Gift")
        val before = ledger(listOf(manual))
        val plan = Deletions.planOne(manual)
        assertTrue(plan is DeletePlan.RemoveOne)
        val after = Deletions.apply(plan, before)
        assertTrue(after.rows.none { it.id == 1L })
        assertEquals(before.rows.size - 1, after.rows.size)
        assertEquals(before.rows.toSet(), Deletions.undo(plan, after).rows.toSet())
    }

    // ---- one occurrence of a series ----------------------------------------------------------------------

    @Test fun deleting_one_occurrence_marks_it_skipped_and_never_hard_deletes_it() {
        val before = ledger()
        val target = before.rows.first { it.date == d(2026, 10, 18) }
        val plan = Deletions.planOne(target)
        assertTrue(plan is DeletePlan.SkipOne)
        val after = Deletions.apply(plan, before)
        assertEquals(before.rows.size, after.rows.size) // the row is still there, so it cannot be generated again
        assertEquals(TxStatus.SKIPPED, after.rows.first { it.id == target.id }.status)
        assertEquals(target.occurrenceDate, after.rows.first { it.id == target.id }.occurrenceDate)
        // no other row changed
        assertEquals(before.rows.filter { it.id != target.id }, after.rows.filter { it.id != target.id })
    }

    @Test fun a_skipped_occurrence_is_not_regenerated_and_undo_restores_the_previous_status() {
        val before = ledger()
        val target = before.rows.first { it.date == d(2026, 10, 18) }
        val plan = Deletions.planOne(target)
        val after = Deletions.apply(plan, before)
        val regenerated = RuleMaintenance.materialize(sundays, after.rows, today)
        assertTrue(regenerated.none { it.occurrenceDate == d(2026, 10, 18) })
        assertEquals(before, Deletions.undo(plan, after))
    }

    // ---- this and all future -----------------------------------------------------------------------------

    @Test fun this_and_all_future_ends_the_series_the_day_before_and_removes_waiting_rows_from_then_on() {
        val before = ledger()
        val target = before.rows.first { it.date == d(2026, 10, 18) }
        val plan = Deletions.planThisAndFuture(target, sundays, before.rows, today)
        val after = Deletions.apply(plan, before)
        assertEquals(d(2026, 10, 17), after.rules.single().endDate)
        // Oct 18 and Oct 25 (waiting) are gone; Oct 4 (accepted), Oct 11 (earlier waiting) and Nov 1 (rejected) stay
        assertEquals(listOf(d(2026, 10, 4), d(2026, 10, 11), d(2026, 11, 1)), after.rows.map { it.date }.sorted())
        assertEquals(listOf(d(2026, 10, 18), d(2026, 10, 25)), plan.removed.map { it.date }.sorted())
    }

    @Test fun this_and_all_future_never_deletes_accepted_or_rejected_rows() {
        val before = ledger()
        val first = before.rows.first { it.date == d(2026, 10, 11) }
        val plan = Deletions.planThisAndFuture(first, sundays, before.rows, today)
        val after = Deletions.apply(plan, before)
        assertTrue(after.rows.any { it.status == TxStatus.CONFIRMED })
        assertTrue(after.rows.any { it.status == TxStatus.SKIPPED })
        assertTrue(plan.removed.all { it.status == TxStatus.PENDING })
    }

    @Test fun undoing_this_and_all_future_restores_the_rule_and_every_removed_row() {
        val before = ledger()
        val target = before.rows.first { it.date == d(2026, 10, 18) }
        val plan = Deletions.planThisAndFuture(target, sundays, before.rows, today)
        val after = Deletions.apply(plan, before)
        val restored = Deletions.undo(plan, after)
        assertEquals(before.rules, restored.rules)
        assertEquals(before.rows.toSet(), restored.rows.toSet())
        assertEquals(null, restored.rules.single().endDate)
    }

    @Test fun a_series_that_already_ends_earlier_keeps_its_earlier_end_and_undo_restores_it() {
        val ending = sundays.copy(endDate = d(2026, 10, 14))
        val before = LedgerState(occurrences(), listOf(ending))
        val target = before.rows.first { it.date == d(2026, 10, 18) }
        val plan = Deletions.planThisAndFuture(target, ending, before.rows, today)
        assertEquals(d(2026, 10, 14), Deletions.apply(plan, before).rules.single().endDate)
        val restored = Deletions.undo(plan, Deletions.apply(plan, before))
        assertEquals(before.rules, restored.rules)
        assertEquals(before.rows.toSet(), restored.rows.toSet())
    }
}
