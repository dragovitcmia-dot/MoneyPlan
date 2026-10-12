package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.YearMonth

class PostponeTest {
    private val start = d(2026, 10, 1)
    private val today = d(2026, 10, 10)

    private fun engine(rows: List<Transaction>, balance: Long = 100) = BalanceEngine(settings(start, balance), rows, today)

    // ---- one single item ----------------------------------------------------------------------

    @Test fun postponing_a_single_item_changes_only_its_date() {
        val item = tx(d(2026, 10, 12), 30, id = 1, description = "Rent")
        val moved = Postpone.apply(item, d(2026, 10, 15))!!
        assertEquals(d(2026, 10, 15), moved.date)
        assertEquals(item.copy(date = d(2026, 10, 15)), moved)
        assertEquals(TxStatus.PENDING, moved.status)
    }

    @Test fun forecast_recomputes_on_the_new_date() {
        val before = engine(listOf(tx(d(2026, 10, 12), 30, id = 1)))
        assertEquals(tnd(70), before.forecast(d(2026, 10, 12), d(2026, 10, 12)).single().closing)
        assertEquals(tnd(100), before.available())

        val rows = listOf(Postpone.apply(tx(d(2026, 10, 12), 30, id = 1), d(2026, 10, 15))!!)
        val after = engine(rows)
        val days = after.forecast(d(2026, 10, 11), d(2026, 10, 16)).associate { it.date to it.closing }
        assertEquals(tnd(100), days[d(2026, 10, 12)])
        assertEquals(tnd(100), days[d(2026, 10, 14)])
        assertEquals(tnd(70), days[d(2026, 10, 15)])
        assertEquals(tnd(70), days[d(2026, 10, 16)])
        assertEquals(tnd(100), after.available()) // waiting items never change Available
    }

    @Test fun calendar_cells_follow_the_postponed_item() {
        val rows = listOf(Postpone.apply(tx(d(2026, 10, 12), 30, id = 1), d(2026, 10, 15))!!)
        val m = CalendarSummaries.month(engine(rows), emptyList(), YearMonth.of(2026, 10), DayOfWeek.MONDAY)
        val old = m.day(d(2026, 10, 12))!!
        val new = m.day(d(2026, 10, 15))!!
        assertEquals(0L, old.expenses)
        assertFalse(old.hasExpenses)
        assertEquals(tnd(100), old.closing)
        assertEquals(tnd(30), new.expenses)
        assertEquals(tnd(70), new.closing)
        assertEquals(1, new.items.size)
    }

    @Test fun an_overdue_item_moved_to_a_future_day_stops_being_overdue() {
        val overdue = tx(d(2026, 10, 8), 40, id = 1)
        val before = engine(listOf(overdue))
        assertEquals(listOf(overdue), before.toConfirm())
        assertEquals(tnd(60), before.forecast(today, today).single().closing) // overdue expenses are due today

        val moved = Postpone.apply(overdue, d(2026, 10, 13))!!
        val after = engine(listOf(moved))
        assertTrue(after.toConfirm().isEmpty())
        assertEquals(tnd(100), after.forecast(today, today).single().closing)
        assertEquals(tnd(60), after.forecast(d(2026, 10, 13), d(2026, 10, 13)).single().closing)
        // it now sits under its new day in Upcoming (Oct 10 is a Saturday: the week ends Sunday Oct 11)
        assertEquals(UpcomingGroupKey.Month(YearMonth.of(2026, 10)), Upcoming.groupOf(moved.date, today, DayOfWeek.MONDAY))
        assertEquals(UpcomingGroupKey.Waiting, Upcoming.groupOf(overdue.date, today, DayOfWeek.MONDAY))
    }

    // ---- edge cases -----------------------------------------------------------------------------

    @Test fun the_same_day_or_an_earlier_day_is_refused() {
        val item = tx(d(2026, 10, 12), 30, id = 1)
        assertNull(Postpone.apply(item, d(2026, 10, 12)))
        assertNull(Postpone.apply(item, d(2026, 10, 11)))
        assertNull(Postpone.apply(item, d(2026, 9, 1)))
        assertNotNull(Postpone.apply(item, d(2026, 10, 13)))
    }

    @Test fun only_waiting_items_can_be_postponed() {
        for (status in listOf(TxStatus.CONFIRMED, TxStatus.SKIPPED)) {
            val item = tx(d(2026, 10, 8), 30, status = status, id = 1)
            assertFalse(Postpone.canPostpone(item))
            assertNull(Postpone.apply(item, d(2026, 10, 20)))
            assertTrue(Postpone.choices(item, today).isEmpty())
        }
    }

    @Test fun quick_dates_count_from_today() {
        assertEquals(d(2026, 10, 11), Postpone.dateFor(PostponeChoice.TOMORROW, today))
        assertEquals(d(2026, 10, 13), Postpone.dateFor(PostponeChoice.IN_3_DAYS, today))
        assertEquals(d(2026, 10, 17), Postpone.dateFor(PostponeChoice.NEXT_WEEK, today))
    }

    @Test fun only_choices_that_really_move_the_item_later_are_offered() {
        assertEquals(PostponeChoice.entries, Postpone.choices(tx(d(2026, 10, 8), 5, id = 1), today)) // overdue: all
        assertEquals(PostponeChoice.entries, Postpone.choices(tx(d(2026, 10, 10), 5, id = 1), today)) // today: all
        assertEquals(
            listOf(PostponeChoice.IN_3_DAYS, PostponeChoice.NEXT_WEEK),
            Postpone.choices(tx(d(2026, 10, 11), 5, id = 1), today), // tomorrow: "Tomorrow" would not move it
        )
        assertEquals(listOf(PostponeChoice.NEXT_WEEK), Postpone.choices(tx(d(2026, 10, 15), 5, id = 1), today))
        assertTrue(Postpone.choices(tx(d(2026, 10, 20), 5, id = 1), today).isEmpty())
    }

    @Test fun the_picker_starts_the_day_after_the_item() {
        assertEquals(d(2026, 10, 13), Postpone.earliestDate(tx(d(2026, 10, 12), 5, id = 1)))
    }

    // ---- one occurrence of a series -------------------------------------------------------------

    private val sundays = rule(RecurrencePattern.Weekly(DayOfWeek.SUNDAY), start, amount = 10, id = 7)
    private fun occurrences() = listOf(d(2026, 10, 11), d(2026, 10, 18), d(2026, 10, 25)).mapIndexed { i, date ->
        sundays.occurrenceFor(date).copy(id = 11L + i)
    }

    @Test fun postponing_one_occurrence_moves_only_that_row_and_keeps_its_occurrence_date() {
        val rows = occurrences()
        val moved = Postpone.apply(rows[0], d(2026, 10, 14))!!
        assertEquals(d(2026, 10, 14), moved.date)
        assertEquals(d(2026, 10, 11), moved.occurrenceDate) // the uniqueness key is unchanged
        assertEquals(7L, moved.recurringRuleId)
        assertEquals(TxSource.RECURRING, moved.source)
        val after = listOf(moved, rows[1], rows[2])
        assertEquals(rows[1], after[1])
        assertEquals(rows[2], after[2])
    }

    @Test fun a_postponed_occurrence_is_not_generated_again() {
        val rows = occurrences()
        val after = listOf(Postpone.apply(rows[0], d(2026, 10, 14))!!, rows[1], rows[2])
        val generated = RuleMaintenance.materialize(sundays, after, today)
        assertTrue(generated.none { it.occurrenceDate == d(2026, 10, 11) }) // no duplicate of the moved one
        assertTrue(generated.none { it.occurrenceDate == d(2026, 10, 18) || it.occurrenceDate == d(2026, 10, 25) })
        // the same holds even when the occurrence is postponed into a later occurrence's day
        val onto = listOf(Postpone.apply(rows[0], d(2026, 10, 18))!!, rows[1], rows[2])
        assertTrue(RuleMaintenance.materialize(sundays, onto, today).none { it.occurrenceDate == d(2026, 10, 11) })
    }

    @Test fun postponing_an_occurrence_updates_the_forecast_and_leaves_the_other_days_alone() {
        val rows = occurrences()
        val after = listOf(Postpone.apply(rows[0], d(2026, 10, 14))!!, rows[1], rows[2])
        val days = engine(after).forecast(d(2026, 10, 10), d(2026, 10, 26)).associate { it.date to it.closing }
        assertEquals(tnd(100), days[d(2026, 10, 11)])
        assertEquals(tnd(100), days[d(2026, 10, 13)])
        assertEquals(tnd(90), days[d(2026, 10, 14)]) // the moved occurrence
        assertEquals(tnd(80), days[d(2026, 10, 18)]) // the others are where they were
        assertEquals(tnd(70), days[d(2026, 10, 25)])
    }
}
