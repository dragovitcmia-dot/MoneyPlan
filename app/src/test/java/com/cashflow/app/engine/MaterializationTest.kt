package com.cashflow.app.engine

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simulates the repository: apply [Recurrence.missingOccurrences] onto an in-memory row set. */
class MaterializationTest {
    private val today = d(2026, 10, 10)
    private val weekly = rule(RecurrencePattern.Weekly(DayOfWeek.SUNDAY), today)

    private fun materialize(rule: RecurringRule, rows: MutableList<Transaction>, now: java.time.LocalDate = today) {
        val existing = rows.filter { it.recurringRuleId == rule.id }.mapNotNull { it.occurrenceDate }.toSet()
        rows += Recurrence.missingOccurrences(rule, existing, now).map { rule.occurrenceFor(it) }
    }

    @Test fun materialized_rows_are_pending_recurring_and_linked_to_the_rule() {
        val rows = mutableListOf<Transaction>()
        materialize(weekly, rows)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.status == TxStatus.PENDING && it.source == TxSource.RECURRING })
        assertTrue(rows.all { it.recurringRuleId == weekly.id && it.occurrenceDate == it.date })
    }

    @Test fun horizon_is_400_days_ahead() {
        val rows = mutableListOf<Transaction>()
        materialize(rule(RecurrencePattern.EveryNDays(1, today), today), rows)
        assertEquals(today, rows.minOf { it.date })
        assertEquals(today.plusDays(400), rows.maxOf { it.date })
        assertEquals(401, rows.size)
    }

    @Test fun running_twice_does_not_duplicate() {
        val rows = mutableListOf<Transaction>()
        materialize(weekly, rows)
        val first = rows.toList()
        materialize(weekly, rows)
        assertEquals(first, rows)
    }

    @Test fun rerun_on_a_later_day_only_adds_the_new_tail_of_the_horizon() {
        val rows = mutableListOf<Transaction>()
        materialize(weekly, rows)
        val before = rows.size
        materialize(weekly, rows, now = today.plusDays(30))
        val dates = rows.map { it.date }
        assertEquals(dates.size, dates.toSet().size)
        assertTrue(rows.size > before)
        assertTrue(rows.maxOf { it.date } <= today.plusDays(430))
    }

    @Test fun moved_occurrence_is_not_regenerated_on_its_original_day() {
        val rows = mutableListOf<Transaction>()
        materialize(weekly, rows)
        val original = rows.first { it.date == d(2026, 10, 18) }
        rows[rows.indexOf(original)] = original.copy(date = d(2026, 10, 19))
        materialize(weekly, rows)
        assertTrue(rows.none { it.date == d(2026, 10, 18) })
        assertEquals(1, rows.count { it.occurrenceDate == d(2026, 10, 18) })
    }

    @Test fun skipped_or_edited_occurrence_leaves_other_rows_and_the_rule_untouched() {
        val rows = mutableListOf<Transaction>()
        materialize(weekly, rows)
        val ruleBefore = weekly.copy()
        val i = rows.indexOfFirst { it.date == d(2026, 10, 25) }
        rows[i] = rows[i].copy(status = TxStatus.SKIPPED)
        val j = rows.indexOfFirst { it.date == d(2026, 11, 1) }
        rows[j] = rows[j].copy(amount = tnd(99), description = "edited")
        materialize(weekly, rows) // re-run: still exactly one row per occurrence, edits intact
        assertEquals(ruleBefore, weekly)
        assertEquals(TxStatus.SKIPPED, rows.single { it.date == d(2026, 10, 25) }.status)
        assertEquals(tnd(99), rows.single { it.date == d(2026, 11, 1) }.amount)
        assertEquals(tnd(10), rows.single { it.date == d(2026, 11, 8) }.amount)
    }

    @Test fun rule_with_end_date_stops_there() {
        val rows = mutableListOf<Transaction>()
        materialize(weekly.copy(endDate = d(2026, 11, 1)), rows)
        assertEquals(listOf(d(2026, 10, 11), d(2026, 10, 18), d(2026, 10, 25), d(2026, 11, 1)), rows.map { it.date })
    }

    @Test fun rules_are_independent() {
        val a = weekly
        val b = rule(RecurrencePattern.Weekly(DayOfWeek.SUNDAY), today, id = 2)
        val rows = mutableListOf<Transaction>()
        materialize(a, rows)
        materialize(b, rows)
        assertEquals(rows.size / 2, rows.count { it.recurringRuleId == 1L })
        assertEquals(rows.size / 2, rows.count { it.recurringRuleId == 2L })
    }

    @Test fun past_occurrences_of_a_backdated_rule_are_materialized_pending() {
        val old = rule(RecurrencePattern.MonthFirst, d(2026, 7, 1))
        val rows = mutableListOf<Transaction>()
        materialize(old, rows)
        assertTrue(rows.any { it.date == d(2026, 8, 1) && it.status == TxStatus.PENDING })
    }
}
