package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class SuggestionsTest {
    private val today = d(2026, 10, 12)
    private fun one(date: java.time.LocalDate, amount: Long, name: String, kind: TxKind = TxKind.EXPENSE, id: Long = 0, status: TxStatus = TxStatus.CONFIRMED) =
        tx(date, amount, kind, status, description = name, id = id)

    private fun series(id: Long, name: String, amount: Long, pattern: RecurrencePattern, start: java.time.LocalDate, kind: TxKind = TxKind.EXPENSE) =
        RecurringRule(id = id, amount = tnd(amount), kind = kind, description = name, pattern = pattern, startDate = start)

    // ---- same as last time ---------------------------------------------------------------------------------------------

    @Test fun the_most_recent_match_wins_ignoring_case_and_spaces() {
        val history = listOf(one(d(2026, 9, 1), 18, "Coffee", id = 1), one(d(2026, 10, 1), 22, "  coffee ", id = 2), one(d(2026, 8, 1), 30, "COFFEE", id = 3))
        assertEquals(Suggestion(tnd(22), null), Suggestions.sameAsLast("COFFEE", TxKind.EXPENSE, history, emptyList()))
        assertEquals(Suggestion(tnd(22), null), Suggestions.sameAsLast(" coffee", TxKind.EXPENSE, history, emptyList()))
    }

    @Test fun a_series_started_after_the_last_one_time_payment_is_the_match() {
        val history = listOf(one(d(2026, 9, 1), 18, "Gym", id = 1))
        val weekly = series(5, "Gym", 75, RecurrencePattern.Weekly(DayOfWeek.MONDAY), d(2026, 10, 5))
        val s = Suggestions.sameAsLast("Gym", TxKind.EXPENSE, history, listOf(weekly))!!
        assertTrue(s.repeating)
        assertEquals(tnd(75), s.amount)
        // ...and a later one-time payment beats the older series
        val later = history + one(d(2026, 10, 9), 20, "Gym", id = 2)
        assertEquals(Suggestion(tnd(20), null), Suggestions.sameAsLast("Gym", TxKind.EXPENSE, later, listOf(weekly)))
    }

    @Test fun expenses_and_income_never_mix() {
        val history = listOf(one(d(2026, 10, 1), 1200, "Salary", TxKind.INCOME, id = 1))
        val rules = listOf(series(3, "Salary", 1200, RecurrencePattern.MonthLast, d(2026, 9, 30), TxKind.INCOME))
        assertNull(Suggestions.sameAsLast("Salary", TxKind.EXPENSE, history, rules))
        assertEquals(tnd(1200), Suggestions.sameAsLast("Salary", TxKind.INCOME, history, rules)!!.amount)
    }

    @Test fun new_blank_and_skipped_names_give_nothing_and_generated_rows_are_not_matches_by_themselves() {
        val history = listOf(
            one(d(2026, 10, 1), 18, "Coffee", id = 1),
            one(d(2026, 10, 2), 50, "Taxi", id = 2, status = TxStatus.SKIPPED),
            tx(d(2026, 10, 5), 75, description = "Gym", source = TxSource.RECURRING, id = 3).copy(recurringRuleId = 9, occurrenceDate = d(2026, 10, 5)),
        )
        assertNull(Suggestions.sameAsLast("Tea", TxKind.EXPENSE, history, emptyList()))
        assertNull(Suggestions.sameAsLast("", TxKind.EXPENSE, history, emptyList()))
        assertNull(Suggestions.sameAsLast("   ", TxKind.EXPENSE, history, emptyList()))
        assertNull(Suggestions.sameAsLast("Taxi", TxKind.EXPENSE, history, emptyList())) // skipped never counts
        assertNull(Suggestions.sameAsLast("Gym", TxKind.EXPENSE, history, emptyList())) // the row belongs to a series that is not in the list
    }

    @Test fun the_line_reads_one_time_and_repeating_differently() {
        assertEquals("Same as last time: 18 TND", Suggestions.line(Suggestion(tnd(18), null)))
        fun repeating(p: RecurrencePattern) = Suggestions.line(Suggestion(tnd(75), series(1, "x", 75, p, today)))
        assertEquals("Same as last time: 75 TND, every week", repeating(RecurrencePattern.Weekly(DayOfWeek.MONDAY)))
        assertEquals("Same as last time: 75 TND, every week", repeating(RecurrencePattern.WeeklyDays(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))))
        assertEquals("Same as last time: 75 TND, every day", repeating(RecurrencePattern.Daily))
        assertEquals("Same as last time: 75 TND, every month", repeating(RecurrencePattern.MonthFirst))
        assertEquals("Same as last time: 75 TND, every month", repeating(RecurrencePattern.MonthLast))
        assertEquals("Same as last time: 75 TND, every month", repeating(RecurrencePattern.DayOfMonth(20)))
        assertEquals("Same as last time: 75 TND, every year", repeating(RecurrencePattern.Yearly(3, 4)))
        assertEquals("Same as last time: 75 TND, every 2 weeks", repeating(RecurrencePattern.EveryNDays(14, today)))
        assertEquals("Same as last time: 75 TND, every week", repeating(RecurrencePattern.EveryNDays(7, today)))
        assertEquals("Same as last time: 75 TND, every 10 days", repeating(RecurrencePattern.EveryNDays(10, today)))
        assertEquals("Same as last time: 75 TND, every 3 weeks", repeating(RecurrencePattern.EveryNDays(21, today)))
    }

    @Test fun the_amount_keeps_its_cents() {
        assertEquals("Same as last time: 12.5 TND", Suggestions.line(Suggestion(12_500, null)))
    }

    @Test fun tapping_fills_the_amount_and_for_a_series_the_schedule() {
        val form = EntryFormState.initial(TxKind.EXPENSE, today).copy(description = "Gym", amountText = "")
        val oneTime = Suggestions.apply(form, Suggestion(tnd(18), null), today)
        assertEquals("18", oneTime.amountText)
        assertEquals(Timing.NOW, oneTime.timing) // a one-time payment changes nothing but the amount
        assertEquals("Gym", oneTime.description)

        val weekly = series(5, "Gym", 75, RecurrencePattern.Weekly(DayOfWeek.MONDAY), d(2026, 10, 5))
        val filled = Suggestions.apply(form, Suggestion(tnd(75), weekly), today)
        assertEquals("75", filled.amountText)
        assertEquals(Timing.REPEATS, filled.timing)
        assertEquals(PatternChoice.WEEKLY, filled.choice)
        assertEquals(setOf(DayOfWeek.MONDAY), filled.weekdays)
        assertEquals("Gym", filled.description)
        assertEquals(TxKind.EXPENSE, filled.kind)
        assertFalse(filled.startDate.isBefore(today))
        // the filled form builds into the same series
        val built = (EntryForm.build(filled, "x") as FormResult.Valid).entry as NewEntry.Recurring
        assertEquals(RecurrencePattern.Weekly(DayOfWeek.MONDAY), built.pattern)
        assertEquals(tnd(75), built.amount)
        // and the wizard accepts it all the way to the review
        val state = WizardState(filled, today, step = WizardStep.WHEN)
        assertTrue(EntryWizard.canAdvance(state))
    }

    // ---- recent amounts ------------------------------------------------------------------------------------------------

    @Test fun recent_amounts_are_the_newest_three_distinct_of_the_type() {
        val history = listOf(
            one(d(2026, 10, 10), 18, "a", id = 1), one(d(2026, 10, 9), 18, "b", id = 2), one(d(2026, 10, 8), 40, "c", id = 3),
            one(d(2026, 10, 7), 75, "d", id = 4), one(d(2026, 10, 6), 12, "e", id = 5),
            one(d(2026, 10, 11), 999, "salary", TxKind.INCOME, id = 6),
        )
        assertEquals(listOf(tnd(18), tnd(40), tnd(75)), Suggestions.recentAmounts(TxKind.EXPENSE, history, emptyList(), today))
        assertEquals(listOf(tnd(999)), Suggestions.recentAmounts(TxKind.INCOME, history, emptyList(), today))
        assertEquals(listOf(tnd(18), tnd(40), tnd(75), tnd(12)), Suggestions.recentAmounts(TxKind.EXPENSE, history, emptyList(), today, limit = 4))
    }

    @Test fun recent_amounts_skip_the_future_skipped_rows_and_rows_of_a_series_but_use_the_series_itself() {
        val history = listOf(
            one(d(2026, 10, 20), 500, "later", id = 1, status = TxStatus.PENDING),
            one(d(2026, 10, 10), 33, "skipped", id = 2, status = TxStatus.SKIPPED),
            tx(d(2026, 10, 11), 75, description = "Gym", source = TxSource.RECURRING, id = 3).copy(recurringRuleId = 4, occurrenceDate = d(2026, 10, 11)),
            one(d(2026, 10, 1), 20, "coffee", id = 4),
        )
        val rules = listOf(series(4, "Gym", 75, RecurrencePattern.Weekly(DayOfWeek.SUNDAY), d(2026, 10, 4)), series(5, "Future", 9, RecurrencePattern.Daily, d(2026, 11, 1)))
        assertEquals(listOf(tnd(75), tnd(20)), Suggestions.recentAmounts(TxKind.EXPENSE, history, rules, today))
    }

    @Test fun without_history_there_are_no_recent_amounts() {
        assertTrue(Suggestions.recentAmounts(TxKind.EXPENSE, emptyList(), emptyList(), today).isEmpty())
        assertTrue(Suggestions.recentAmounts(TxKind.INCOME, listOf(one(d(2026, 10, 1), 10, "x", id = 1)), emptyList(), today).isEmpty())
    }
}
