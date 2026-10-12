package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryWizardTest {
    private val today = d(2026, 10, 15) // a Thursday

    private fun wizard(kind: TxKind = TxKind.EXPENSE) = EntryWizard.start(kind, today)

    private fun typed(state: WizardState, amount: String = "12.5", name: String = "Bus") =
        EntryWizard.update(state) { it.copy(amountText = amount, description = name) }

    // ---- the path ------------------------------------------------------------------------------

    @Test fun now_goes_amount_name_when_review() {
        assertEquals(
            listOf(WizardStep.AMOUNT, WizardStep.NAME, WizardStep.WHEN, WizardStep.REVIEW),
            EntryWizard.path(wizard().form),
        )
    }

    @Test fun later_adds_the_date_screen() {
        val form = wizard().form.copy(timing = Timing.LATER)
        assertEquals(
            listOf(WizardStep.AMOUNT, WizardStep.NAME, WizardStep.WHEN, WizardStep.LATER_DATE, WizardStep.REVIEW),
            EntryWizard.path(form),
        )
    }

    @Test fun repeating_adds_frequency_start_and_end() {
        val form = wizard().form.copy(timing = Timing.REPEATS)
        assertEquals(
            listOf(
                WizardStep.AMOUNT, WizardStep.NAME, WizardStep.WHEN,
                WizardStep.FREQUENCY, WizardStep.START_DATE, WizardStep.END_DATE, WizardStep.REVIEW,
            ),
            EntryWizard.path(form),
        )
    }

    @Test fun progress_counts_the_screens_of_the_chosen_path() {
        var s = wizard()
        assertEquals(1 to 4, EntryWizard.progress(s))
        s = EntryWizard.update(s) { it.copy(timing = Timing.REPEATS) }
        assertEquals(1 to 7, EntryWizard.progress(s))
        assertEquals(7 to 7, EntryWizard.progress(s.copy(step = WizardStep.REVIEW)))
        assertEquals(5 to 7, EntryWizard.progress(s.copy(step = WizardStep.START_DATE)))
    }

    // ---- going forward: required fields -----------------------------------------------------------

    @Test fun the_amount_is_required_to_leave_the_first_screen() {
        val stuck = EntryWizard.next(wizard())
        assertEquals(WizardStep.AMOUNT, stuck.step)
        assertTrue(stuck.attempted)
        assertEquals(listOf(FormProblem.AMOUNT_MISSING), EntryWizard.problems(stuck))
        val zero = EntryWizard.next(EntryWizard.update(wizard()) { it.copy(amountText = "0.") })
        assertEquals(WizardStep.AMOUNT, zero.step)
        val ok = EntryWizard.next(EntryWizard.update(wizard()) { it.copy(amountText = "5") })
        assertEquals(WizardStep.NAME, ok.step)
        assertFalse(ok.attempted)
    }

    @Test fun an_expense_needs_a_name_but_income_does_not() {
        val expense = EntryWizard.next(EntryWizard.update(wizard()) { it.copy(amountText = "5") })
        assertEquals(listOf(FormProblem.NAME_MISSING), EntryWizard.problems(expense))
        assertEquals(WizardStep.NAME, EntryWizard.next(expense).step)
        val blanks = EntryWizard.update(expense) { it.copy(description = "   ") }
        assertEquals(listOf(FormProblem.NAME_MISSING), EntryWizard.problems(blanks)) // spaces are not a name
        assertEquals(WizardStep.WHEN, EntryWizard.next(EntryWizard.update(expense) { it.copy(description = "Bus") }).step)

        val income = EntryWizard.next(EntryWizard.update(wizard(TxKind.INCOME)) { it.copy(amountText = "5") })
        assertEquals(WizardStep.NAME, income.step)
        assertTrue(EntryWizard.problems(income).isEmpty())
        assertEquals(WizardStep.WHEN, EntryWizard.next(income).step)
    }

    @Test fun later_cannot_be_in_the_past_but_may_be_today() {
        val base = typed(wizard()).copy(step = WizardStep.LATER_DATE)
        val later = EntryWizard.update(base) { it.copy(timing = Timing.LATER, laterDate = today.minusDays(1)) }
        assertEquals(listOf(FormProblem.DATE_IN_PAST), EntryWizard.problems(later))
        assertEquals(WizardStep.LATER_DATE, EntryWizard.next(later).step)
        val todayOk = EntryWizard.update(later) { it.copy(laterDate = today) }
        assertEquals(WizardStep.REVIEW, EntryWizard.next(todayOk).step)
    }

    @Test fun a_repeating_payment_needs_a_start_today_or_later_and_an_end_after_the_start() {
        val base = EntryWizard.update(typed(wizard())) { it.copy(timing = Timing.REPEATS) }
        val past = base.copy(step = WizardStep.START_DATE, form = EntryWizard.withStartDate(base.form, today.minusDays(3)))
        assertEquals(listOf(FormProblem.DATE_IN_PAST), EntryWizard.problems(past))
        val end = base.copy(step = WizardStep.END_DATE, form = base.form.copy(hasEnd = true, endDate = today.minusDays(1)))
        assertEquals(listOf(FormProblem.END_BEFORE_START), EntryWizard.problems(end))
        val noEnd = end.copy(form = end.form.copy(hasEnd = false))
        assertTrue(EntryWizard.problems(noEnd).isEmpty()) // an end date that is switched off does not count
        assertEquals(WizardStep.REVIEW, EntryWizard.next(noEnd).step)
    }

    @Test fun the_review_collects_every_problem_on_the_path() {
        val s = wizard().copy(step = WizardStep.REVIEW) // nothing typed yet
        assertEquals(listOf(FormProblem.AMOUNT_MISSING, FormProblem.NAME_MISSING), EntryWizard.problems(s))
        assertFalse(EntryWizard.canAdvance(s))
        assertTrue(EntryWizard.canAdvance(typed(wizard()).copy(step = WizardStep.REVIEW)))
    }

    @Test fun a_full_walk_for_every_timing_reaches_a_valid_entry() {
        for (kind in TxKind.entries) for (timing in Timing.entries) {
            var s = typed(wizard(kind))
            s = EntryWizard.update(s) { it.copy(timing = timing) }
            var guard = 0
            while (s.step != WizardStep.REVIEW && guard++ < 10) s = EntryWizard.next(s)
            assertEquals("$kind/$timing", WizardStep.REVIEW, s.step)
            assertTrue("$kind/$timing", EntryForm.build(s.form, "x") is FormResult.Valid)
        }
    }

    // ---- going back --------------------------------------------------------------------------------

    @Test fun back_from_the_first_screen_closes_the_wizard() {
        assertNull(EntryWizard.back(wizard()))
    }

    @Test fun back_returns_to_the_previous_screen_and_keeps_everything_typed() {
        var s = typed(wizard())
        s = EntryWizard.update(s) { it.copy(timing = Timing.LATER, laterDate = today.plusDays(9)) }
        s = s.copy(step = WizardStep.REVIEW)
        val back = EntryWizard.back(s)!!
        assertEquals(WizardStep.LATER_DATE, back.step)
        assertEquals(s.form, back.form)
        val again = EntryWizard.back(back)!!
        assertEquals(WizardStep.WHEN, again.step)
        assertEquals("12.5", again.form.amountText)
        assertFalse(again.attempted)
    }

    @Test fun changing_when_changes_the_path_and_back_follows_the_new_one() {
        var s = typed(wizard()).copy(step = WizardStep.WHEN)
        s = EntryWizard.update(s) { it.copy(timing = Timing.REPEATS) }
        s = EntryWizard.next(s)
        assertEquals(WizardStep.FREQUENCY, s.step)
        s = EntryWizard.update(EntryWizard.back(s)!!) { it.copy(timing = Timing.NOW) }
        assertEquals(WizardStep.REVIEW, EntryWizard.next(s).step) // Now skips the date screens
    }

    @Test fun go_to_only_jumps_to_screens_on_the_path() {
        val now = typed(wizard()).copy(step = WizardStep.REVIEW)
        assertEquals(WizardStep.REVIEW, EntryWizard.goTo(now, WizardStep.LATER_DATE).step) // not on the Now path
        val later = EntryWizard.update(now) { it.copy(timing = Timing.LATER) }
        assertEquals(WizardStep.LATER_DATE, EntryWizard.goTo(later, WizardStep.LATER_DATE).step) // "Change date"
    }

    // ---- start -----------------------------------------------------------------------------------

    @Test fun opened_from_a_future_day_it_starts_with_that_day() {
        val s = EntryWizard.start(TxKind.EXPENSE, today, day = today.plusDays(5))
        assertEquals(Timing.LATER, s.form.timing)
        assertEquals(today.plusDays(5), s.form.laterDate)
    }

    @Test fun a_repeating_payment_opened_from_a_past_day_starts_today() {
        val s = EntryWizard.start(TxKind.INCOME, today, day = today.minusDays(4))
        assertEquals(Timing.NOW, s.form.timing)
        assertEquals(today.minusDays(4), s.form.nowDate)
        assertEquals(today, s.form.startDate)
    }

    // ---- start date, frequency, preview -------------------------------------------------------------

    @Test fun the_start_date_defines_weekday_and_day_of_month() {
        val f = EntryWizard.withStartDate(wizard().form, d(2026, 11, 3)) // a Tuesday
        assertEquals(java.time.DayOfWeek.TUESDAY, f.weekday)
        assertEquals("3", f.dayOfMonth)
        assertEquals(d(2026, 11, 3), f.startDate)
    }

    private fun preview(choice: PatternChoice, start: java.time.LocalDate, end: java.time.LocalDate? = null): List<java.time.LocalDate> {
        val base = typed(wizard()).form.copy(timing = Timing.REPEATS)
        val f = EntryWizard.withFrequency(EntryWizard.withStartDate(base, start), choice)
            .let { if (end != null) it.copy(hasEnd = true, endDate = end) else it }
        return EntryWizard.occurrencePreview(f)
    }

    @Test fun preview_shows_the_next_three_dates_of_every_frequency() {
        assertEquals(listOf(d(2026, 10, 15), d(2026, 10, 16), d(2026, 10, 17)), preview(PatternChoice.DAILY, today))
        assertEquals(listOf(d(2026, 10, 15), d(2026, 10, 22), d(2026, 10, 29)), preview(PatternChoice.WEEKLY, today))
        assertEquals(listOf(d(2026, 10, 15), d(2026, 10, 29), d(2026, 11, 12)), preview(PatternChoice.EVERY_2_WEEKS, today))
        assertEquals(listOf(d(2026, 12, 31), d(2027, 1, 31), d(2027, 2, 28)), preview(PatternChoice.MONTH_DAY, d(2026, 12, 31)))
        assertEquals(listOf(d(2027, 2, 28), d(2028, 2, 28), d(2029, 2, 28)), preview(PatternChoice.YEARLY, d(2027, 2, 28)))
    }

    @Test fun a_yearly_payment_on_29_february_falls_on_the_28th_in_other_years() {
        assertEquals(listOf(d(2028, 2, 29), d(2029, 2, 28), d(2030, 2, 28)), preview(PatternChoice.YEARLY, d(2028, 2, 29)))
    }

    @Test fun preview_stops_at_the_end_date_and_is_empty_for_now_and_later() {
        assertEquals(listOf(d(2026, 10, 15), d(2026, 10, 22)), preview(PatternChoice.WEEKLY, today, end = d(2026, 10, 28)))
        assertEquals(listOf(d(2026, 10, 15)), preview(PatternChoice.WEEKLY, today, end = today))
        assertTrue(EntryWizard.occurrencePreview(typed(wizard()).form).isEmpty())
        assertTrue(EntryWizard.occurrencePreview(typed(wizard()).form.copy(timing = Timing.LATER)).isEmpty())
    }

    // ---- the entry for the live calendar -------------------------------------------------------------

    @Test fun entry_at_applies_the_chosen_day_to_later_and_repeating_forms() {
        val base = typed(wizard()).form
        val later = EntryWizard.entryAt(base.copy(timing = Timing.LATER), d(2026, 11, 3)) as NewEntry.Planned
        assertEquals(d(2026, 11, 3), later.date)
        val rec = EntryWizard.entryAt(base.copy(timing = Timing.REPEATS), d(2026, 11, 3)) as NewEntry.Recurring
        assertEquals(d(2026, 11, 3), rec.startDate)
        assertEquals(RecurrencePattern.Weekly(java.time.DayOfWeek.TUESDAY), rec.pattern)
        assertTrue(EntryWizard.entryAt(base.copy(timing = Timing.NOW), d(2026, 11, 3)) is NewEntry.Now)
        assertNull(EntryWizard.entryAt(base.copy(amountText = "", timing = Timing.LATER), d(2026, 11, 3)))
    }

    @Test fun the_five_frequencies_are_offered_for_both_kinds() {
        val five = listOf(PatternChoice.DAILY, PatternChoice.WEEKLY, PatternChoice.EVERY_2_WEEKS, PatternChoice.MONTH_DAY, PatternChoice.YEARLY)
        for (kind in TxKind.entries) assertEquals(five, EntryForm.choicesFor(kind))
        assertNotNull(five)
    }
}
