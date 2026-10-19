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
        val moved = Postpone.apply(item, d(2026, 10, 15), today)!!
        assertEquals(d(2026, 10, 15), moved.date)
        assertEquals(item.copy(date = d(2026, 10, 15)), moved)
        assertEquals(TxStatus.PENDING, moved.status)
    }

    @Test fun forecast_recomputes_on_the_new_date() {
        val before = engine(listOf(tx(d(2026, 10, 12), 30, id = 1)))
        assertEquals(tnd(70), before.forecast(d(2026, 10, 12), d(2026, 10, 12)).single().closing)
        assertEquals(tnd(100), before.available())

        val rows = listOf(Postpone.apply(tx(d(2026, 10, 12), 30, id = 1), d(2026, 10, 15), today)!!)
        val after = engine(rows)
        val days = after.forecast(d(2026, 10, 11), d(2026, 10, 16)).associate { it.date to it.closing }
        assertEquals(tnd(100), days[d(2026, 10, 12)])
        assertEquals(tnd(100), days[d(2026, 10, 14)])
        assertEquals(tnd(70), days[d(2026, 10, 15)])
        assertEquals(tnd(70), days[d(2026, 10, 16)])
        assertEquals(tnd(100), after.available()) // waiting items never change Available
    }

    @Test fun calendar_cells_follow_the_postponed_item() {
        val rows = listOf(Postpone.apply(tx(d(2026, 10, 12), 30, id = 1), d(2026, 10, 15), today)!!)
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

        val moved = Postpone.apply(overdue, d(2026, 10, 13), today)!!
        val after = engine(listOf(moved))
        assertTrue(after.toConfirm().isEmpty())
        assertEquals(tnd(100), after.forecast(today, today).single().closing)
        assertEquals(tnd(60), after.forecast(d(2026, 10, 13), d(2026, 10, 13)).single().closing)
        // it now sits under its new day in Upcoming (Oct 10 is a Saturday: the week ends Sunday Oct 11)
        assertEquals(UpcomingGroupKey.Month(YearMonth.of(2026, 10)), Upcoming.groupOf(moved.date, today, DayOfWeek.MONDAY))
        assertEquals(UpcomingGroupKey.Waiting, Upcoming.groupOf(overdue.date, today, DayOfWeek.MONDAY))
    }

    // ---- edge cases -----------------------------------------------------------------------------

    @Test fun a_day_before_today_or_the_items_own_day_is_refused_but_today_is_allowed() {
        val item = tx(d(2026, 10, 12), 30, id = 1)
        assertNull(Postpone.apply(item, d(2026, 10, 12), today)) // the same day: nothing to move
        assertNull(Postpone.apply(item, d(2026, 10, 9), today)) // yesterday
        assertNull(Postpone.apply(item, d(2026, 9, 1), today))
        assertNotNull(Postpone.apply(item, d(2026, 10, 13), today))
        // v0.10.1: today (and every day from today on) is allowed even though it is EARLIER than the item's own date
        assertEquals(d(2026, 10, 10), Postpone.apply(item, d(2026, 10, 10), today)!!.date)
        assertEquals(d(2026, 10, 11), Postpone.apply(item, d(2026, 10, 11), today)!!.date)
    }

    @Test fun only_waiting_items_can_be_postponed() {
        for (status in listOf(TxStatus.CONFIRMED, TxStatus.SKIPPED)) {
            val item = tx(d(2026, 10, 8), 30, status = status, id = 1)
            assertFalse(Postpone.canPostpone(item))
            assertNull(Postpone.apply(item, d(2026, 10, 20), today))
            assertNull(Postpone.suggestedDate(item, null, today))
        }
    }

    @Test fun quick_dates_count_from_today() {
        assertEquals(d(2026, 10, 11), Postpone.dateFor(PostponeChoice.TOMORROW, today))
        assertEquals(d(2026, 10, 13), Postpone.dateFor(PostponeChoice.IN_3_DAYS, today))
        assertEquals(d(2026, 10, 17), Postpone.dateFor(PostponeChoice.NEXT_WEEK, today))
        assertEquals(d(2026, 10, 17), Postpone.dateFor(PostponeChoice.IN_1_WEEK, today))
    }

    @Test fun the_suggestion_follows_the_nature_of_the_item() {
        assertEquals(PostponeChoice.TOMORROW, Postpone.suggestionFor(RecurrencePattern.Daily))
        assertEquals(PostponeChoice.IN_3_DAYS, Postpone.suggestionFor(RecurrencePattern.Weekly(DayOfWeek.MONDAY)))
        assertEquals(PostponeChoice.IN_3_DAYS, Postpone.suggestionFor(RecurrencePattern.WeeklyDays(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))))
        assertEquals(PostponeChoice.IN_3_DAYS, Postpone.suggestionFor(RecurrencePattern.EveryNDays(14, d(2026, 10, 1))))
        assertEquals(PostponeChoice.IN_1_WEEK, Postpone.suggestionFor(RecurrencePattern.DayOfMonth(15)))
        assertEquals(PostponeChoice.IN_1_WEEK, Postpone.suggestionFor(RecurrencePattern.MonthFirst))
        assertEquals(PostponeChoice.IN_1_WEEK, Postpone.suggestionFor(RecurrencePattern.MonthLast))
        assertEquals(PostponeChoice.IN_1_WEEK, Postpone.suggestionFor(RecurrencePattern.Yearly(3, 1)))
        assertEquals(PostponeChoice.NEXT_WEEK, Postpone.suggestionFor(null)) // a one-time item
    }

    @Test fun the_suggested_date_is_counted_from_today_or_the_items_own_later_date() {
        val dueToday = tx(d(2026, 10, 10), 5, id = 1)
        assertEquals(d(2026, 10, 11), Postpone.suggestedDate(dueToday, RecurrencePattern.Daily, today))
        assertEquals(d(2026, 10, 13), Postpone.suggestedDate(dueToday, RecurrencePattern.Weekly(DayOfWeek.MONDAY), today))
        assertEquals(d(2026, 10, 17), Postpone.suggestedDate(dueToday, RecurrencePattern.Yearly(3, 1), today))
        assertEquals(d(2026, 10, 17), Postpone.suggestedDate(dueToday, null, today))
        val overdue = tx(d(2026, 10, 2), 5, id = 2)
        assertEquals(d(2026, 10, 11), Postpone.suggestedDate(overdue, RecurrencePattern.Daily, today)) // from today, not from the old date
        val planned = tx(d(2026, 10, 20), 5, id = 3)
        assertEquals(d(2026, 10, 21), Postpone.suggestedDate(planned, RecurrencePattern.Daily, today)) // always later than the item
        assertEquals(d(2026, 10, 27), Postpone.suggestedDate(planned, null, today))
        for (p in listOf<RecurrencePattern?>(null, RecurrencePattern.Daily, RecurrencePattern.MonthLast)) {
            assertTrue(Postpone.isValidDate(planned, Postpone.suggestedDate(planned, p, today)!!, today))
        }
    }

    @Test fun a_postponed_item_that_cannot_be_postponed_has_no_suggestion() {
        assertNull(Postpone.suggestedDate(tx(d(2026, 10, 8), 5, status = TxStatus.CONFIRMED, id = 1), null, today))
    }

    @Test fun the_picker_starts_today_whatever_the_items_date() {
        assertEquals(today, Postpone.earliestDate(today))
    }

    // ---- one occurrence of a series -------------------------------------------------------------

    private val sundays = rule(RecurrencePattern.Weekly(DayOfWeek.SUNDAY), start, amount = 10, id = 7)
    private fun occurrences() = listOf(d(2026, 10, 11), d(2026, 10, 18), d(2026, 10, 25)).mapIndexed { i, date ->
        sundays.occurrenceFor(date).copy(id = 11L + i)
    }

    @Test fun postponing_one_occurrence_moves_only_that_row_and_keeps_its_occurrence_date() {
        val rows = occurrences()
        val moved = Postpone.apply(rows[0], d(2026, 10, 14), today)!!
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
        val after = listOf(Postpone.apply(rows[0], d(2026, 10, 14), today)!!, rows[1], rows[2])
        val generated = RuleMaintenance.materialize(sundays, after, today)
        assertTrue(generated.none { it.occurrenceDate == d(2026, 10, 11) }) // no duplicate of the moved one
        assertTrue(generated.none { it.occurrenceDate == d(2026, 10, 18) || it.occurrenceDate == d(2026, 10, 25) })
        // the same holds even when the occurrence is postponed into a later occurrence's day
        val onto = listOf(Postpone.apply(rows[0], d(2026, 10, 18), today)!!, rows[1], rows[2])
        assertTrue(RuleMaintenance.materialize(sundays, onto, today).none { it.occurrenceDate == d(2026, 10, 11) })
    }

    @Test fun postponing_an_occurrence_updates_the_forecast_and_leaves_the_other_days_alone() {
        val rows = occurrences()
        val after = listOf(Postpone.apply(rows[0], d(2026, 10, 14), today)!!, rows[1], rows[2])
        val days = engine(after).forecast(d(2026, 10, 10), d(2026, 10, 26)).associate { it.date to it.closing }
        assertEquals(tnd(100), days[d(2026, 10, 11)])
        assertEquals(tnd(100), days[d(2026, 10, 13)])
        assertEquals(tnd(90), days[d(2026, 10, 14)]) // the moved occurrence
        assertEquals(tnd(80), days[d(2026, 10, 18)]) // the others are where they were
        assertEquals(tnd(70), days[d(2026, 10, 25)])
    }
}
