package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/** Adding an entry from a calendar day: what the form offers and what gets stored. */
class EntryFormDayTest {
    private val today = d(2026, 10, 14)

    private fun valid(state: EntryFormState): NewEntry =
        (EntryForm.build(if (state.amountText.isEmpty()) state.copy(amountText = "12") else state, "Expense") as FormResult.Valid).entry

    @Test fun a_past_day_defaults_to_already_happened_on_that_date() {
        val past = d(2026, 10, 9)
        val form = EntryFormState.forDay(TxKind.EXPENSE, past, today)
        assertEquals(Timing.NOW, form.timing)
        assertEquals(listOf(Timing.NOW, Timing.REPEATS), form.timings) // no "Later"
        val tx = EntryPlanner.transactionFor(valid(form), today)!!
        assertEquals(past, tx.date)
        assertEquals(TxStatus.CONFIRMED, tx.status)
        assertFalse(tx.isPlanned)
        assertEquals(TxSource.MANUAL, tx.source)
    }

    @Test fun today_behaves_like_the_home_buttons() {
        val form = EntryFormState.forDay(TxKind.INCOME, today, today)
        assertEquals(Timing.NOW, form.timing)
        assertEquals(Timing.entries, form.timings)
        assertNull(form.nowDate)
        val tx = EntryPlanner.transactionFor(valid(form), today)!!
        assertEquals(today, tx.date)
        assertEquals(TxStatus.CONFIRMED, tx.status)
    }

    @Test fun a_future_day_defaults_to_later_on_that_date_and_hides_now() {
        val future = d(2026, 11, 3)
        val form = EntryFormState.forDay(TxKind.EXPENSE, future, today)
        assertEquals(Timing.LATER, form.timing)
        assertEquals(listOf(Timing.LATER, Timing.REPEATS), form.timings)
        assertFalse(Timing.NOW in form.timings)
        val tx = EntryPlanner.transactionFor(valid(form), today)!!
        assertEquals(future, tx.date)
        assertEquals(TxStatus.PENDING, tx.status)
        assertTrue(tx.isPlanned)
    }

    @Test fun tomorrow_counts_as_future() {
        val form = EntryFormState.forDay(TxKind.EXPENSE, today.plusDays(1), today)
        assertEquals(Timing.LATER, form.timing)
        assertEquals(today.plusDays(1), form.laterDate)
    }

    @Test fun repeats_starts_on_the_tapped_day_and_matches_its_weekday_and_date() {
        val day = d(2026, 11, 10) // a Tuesday
        val form = EntryFormState.forDay(TxKind.EXPENSE, day, today).copy(timing = Timing.REPEATS)
        assertEquals(day, form.startDate)
        assertEquals(DayOfWeek.TUESDAY, form.weekday)
        assertEquals("10", form.dayOfMonth)
        val entry = valid(form) as NewEntry.Recurring
        assertEquals(RecurrencePattern.Weekly(DayOfWeek.TUESDAY), entry.pattern)
        assertEquals(day, entry.startDate)
    }

    @Test fun repeats_is_offered_on_a_past_day() {
        val form = EntryFormState.forDay(TxKind.EXPENSE, d(2026, 10, 1), today).copy(timing = Timing.REPEATS)
        assertTrue(Timing.REPEATS in form.timings)
        assertTrue(EntryForm.build(form.copy(amountText = "5"), "Expense") is FormResult.Valid)
    }

    @Test fun the_plain_initial_form_is_unchanged() {
        val form = EntryFormState.initial(TxKind.EXPENSE, today)
        assertEquals(Timing.entries, form.timings)
        assertNull(form.nowDate)
        val tx = EntryPlanner.transactionFor(valid(form), today)!!
        assertEquals(today, tx.date)
    }

    @Test fun the_what_if_check_sees_a_past_dated_expense() {
        val past = d(2026, 10, 9)
        val engine = BalanceEngine(settings(d(2026, 10, 1), 50), emptyList(), today)
        val form = EntryFormState.forDay(TxKind.EXPENSE, past, today).copy(amountText = "80")
        val tx = EntryPlanner.transactionFor(valid(form), today)!!
        val result = engine.whatIf(newTransactions = listOf(tx))
        assertTrue(result.wouldCauseNegative)
    }
}
