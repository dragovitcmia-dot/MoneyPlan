package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class ItemEditsTest {
    private val today = d(2026, 10, 14)
    private val weekly = rule(RecurrencePattern.Weekly(DayOfWeek.SUNDAY), d(2026, 10, 18), amount = 75, id = 5)

    /** Rows as stored after materialization, with ids. */
    private fun stored(rule: RecurringRule = weekly): List<Transaction> =
        RuleMaintenance.materialize(rule, emptyList(), today)
            .mapIndexed { i, t -> t.copy(id = (i + 1).toLong()) }

    @Test fun editing_one_occurrence_changes_only_that_row() {
        val rows = stored()
        val target = rows[1]
        val edited = ItemEdits.edit(target, tnd(80), "  Gym  ", target.date)
        assertEquals(tnd(80), edited.amount)
        assertEquals("Gym", edited.description)
        assertEquals(target.date, edited.date)
        assertEquals(target.occurrenceDate, edited.occurrenceDate)
        assertEquals(target.recurringRuleId, edited.recurringRuleId)
        assertEquals(TxSource.RECURRING, edited.source)
        // Everything else about the series stays exactly as it was.
        val after = rows.map { if (it.id == target.id) edited else it }
        assertEquals(rows.filter { it.id != target.id }, after.filter { it.id != target.id })
        assertEquals(tnd(75), weekly.amount)
    }

    @Test fun deleting_a_generated_occurrence_marks_it_skipped_and_it_is_not_regenerated() {
        val rows = stored()
        val target = rows[2]
        val outcome = ItemEdits.delete(target)
        assertEquals(DeleteOutcome.MarkSkipped(target.id), outcome)
        val after = rows.map { if (it.id == target.id) it.copy(status = TxStatus.SKIPPED) else it }
        // Materializing again (as every app start does) creates nothing, in particular not the skipped day.
        assertTrue(RuleMaintenance.materialize(weekly, after, today).isEmpty())
        // Had the row been removed instead, the occurrence would come back.
        val removed = rows.filter { it.id != target.id }
        assertEquals(listOf(target.date), RuleMaintenance.materialize(weekly, removed, today).map { it.date })
    }

    @Test fun deleting_a_manual_item_removes_it() {
        val manual = tx(d(2026, 10, 20), 12, id = 9)
        assertEquals(DeleteOutcome.Remove(9), ItemEdits.delete(manual))
        assertFalse(ItemEdits.isFromSeries(manual))
        assertTrue(ItemEdits.isFromSeries(stored().first()))
    }

    @Test fun moving_an_occurrence_does_not_duplicate_it() {
        val rows = stored()
        val target = rows[1]
        val moved = ItemEdits.edit(target, target.amount, target.description, target.date.plusDays(3))
        assertEquals(target.date, moved.occurrenceDate)
        val after = rows.map { if (it.id == target.id) moved else it }
        assertTrue(RuleMaintenance.materialize(weekly, after, today).isEmpty())
        // The row now sits on the new day in the forecast, and nowhere else.
        val engine = BalanceEngine(settings(d(2026, 10, 1), 500), after, today)
        assertEquals(1, engine.forecast(moved.date, moved.date).single().items.count { it.id == moved.id })
        assertTrue(engine.forecast(target.date, target.date).single().items.none { it.id == target.id })
    }

    @Test fun moving_an_occurrence_onto_another_occurrence_day_keeps_both_rows() {
        val rows = stored()
        val moved = ItemEdits.edit(rows[0], rows[0].amount, "", rows[1].date)
        val after = rows.map { if (it.id == moved.id) moved else it }
        assertEquals(2, after.count { it.date == rows[1].date })
        assertTrue(RuleMaintenance.materialize(weekly, after, today).isEmpty())
    }

    @Test fun mark_waiting_undoes_done_and_skipped_without_touching_anything_else() {
        val done = stored().first().copy(status = TxStatus.CONFIRMED)
        assertEquals(TxStatus.PENDING, ItemEdits.markWaiting(done).status)
        assertEquals(done.copy(status = TxStatus.PENDING), ItemEdits.markWaiting(done))
        assertEquals(TxStatus.PENDING, ItemEdits.markWaiting(done.copy(status = TxStatus.SKIPPED)).status)
    }

    @Test fun an_amount_must_be_positive_to_save() {
        assertTrue(ItemEdits.canSave(1))
        assertFalse(ItemEdits.canSave(0))
        assertFalse(ItemEdits.canSave(null))
        assertFalse(ItemEdits.canSave(-5))
    }

    @Test fun a_row_whose_rule_was_deleted_behaves_like_a_manual_one() {
        val orphan = stored().first().copy(recurringRuleId = null)
        assertEquals(DeleteOutcome.Remove(orphan.id), ItemEdits.delete(orphan))
    }

    @Test fun skipping_by_delete_changes_the_forecast_like_a_skip() {
        val rows = stored()
        val engineBefore = BalanceEngine(settings(d(2026, 10, 1), 500), rows, today)
        val target = rows[0]
        val after = rows.map { if (it.id == target.id) it.copy(status = TxStatus.SKIPPED) else it }
        val engineAfter = BalanceEngine(settings(d(2026, 10, 1), 500), after, today)
        val before = engineBefore.forecast(target.date, target.date).single()
        assertEquals(tnd(75), before.expenses)
        assertEquals(0L, engineAfter.forecast(target.date, target.date).single().expenses)
        assertNull(engineAfter.nextExpense()?.takeIf { it.transactionId == target.id })
    }
}
