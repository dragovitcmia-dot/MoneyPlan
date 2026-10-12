package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class EntryWizardTest {
    private val today = d(2026, 10, 15) // a Thursday

    private fun wizard(kind: TxKind = TxKind.EXPENSE) = EntryWizard.start(kind, today)

    private fun typed(state: WizardState, amount: String = "12.5", name: String = "Bus") =
        EntryWizard.update(state) { it.copy(amountText = amount, description = name) }

    // ---- the path ------------------------------------------------------------------------------

    @Test fun the_path_is_amount_name_when_review() {
        assertEquals(
            listOf(WizardStep.AMOUNT, WizardStep.NAME, WizardStep.WHEN, WizardStep.REVIEW),
            EntryWizard.path(wizard()),
        )
        // Later and Repeating do not add screens: their fields are on the When screen.
        for (timing in Timing.entries) {
            assertEquals(4, EntryWizard.path(EntryWizard.update(wizard()) { it.copy(timing = timing) }).size)
        }
    }

    @Test fun the_below_zero_screen_is_on_the_path_only_when_the_check_found_something() {
        val flagged = wizard().copy(belowZero = true)
        assertEquals(
            listOf(WizardStep.AMOUNT, WizardStep.NAME, WizardStep.WHEN, WizardStep.BELOW_ZERO, WizardStep.REVIEW),
            EntryWizard.path(flagged),
        )
        assertEquals(1 to 5, EntryWizard.progress(flagged))
        assertEquals(1 to 4, EntryWizard.progress(wizard()))
        assertEquals(5 to 5, EntryWizard.progress(flagged.copy(step = WizardStep.REVIEW)))
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

    private fun atWhen(timing: Timing, change: (EntryFormState) -> EntryFormState = { it }) =
        EntryWizard.update(typed(wizard())) { change(it.copy(timing = timing)) }.copy(step = WizardStep.WHEN)

    @Test fun now_has_nothing_to_check_on_the_when_screen() {
        assertTrue(EntryWizard.problems(atWhen(Timing.NOW)).isEmpty())
    }

    @Test fun later_cannot_be_in_the_past_but_may_be_today() {
        val past = atWhen(Timing.LATER) { it.copy(laterDate = today.minusDays(1)) }
        assertEquals(listOf(FormProblem.DATE_IN_PAST), EntryWizard.problems(past))
        assertEquals(WizardStep.WHEN, EntryWizard.next(past).step)
        assertEquals(WizardStep.WHEN, EntryWizard.afterWhen(past, hasDip = false).step)
        assertTrue(EntryWizard.afterWhen(past, hasDip = false).attempted)
        val todayOk = atWhen(Timing.LATER) { it.copy(laterDate = today) }
        assertEquals(WizardStep.REVIEW, EntryWizard.next(todayOk).step)
    }

    @Test fun a_repeating_payment_needs_valid_weekdays_a_start_today_or_later_and_an_end_after_the_start() {
        val noDays = atWhen(Timing.REPEATS) { it.copy(choice = PatternChoice.WEEKLY, weekdays = emptySet()) }
        assertEquals(listOf(FormProblem.WEEKDAYS_EMPTY), EntryWizard.problems(noDays))
        val past = atWhen(Timing.REPEATS) { EntryWizard.withStartDate(it, today.minusDays(3)) }
        assertEquals(listOf(FormProblem.DATE_IN_PAST), EntryWizard.problems(past))
        val end = atWhen(Timing.REPEATS) { it.copy(hasEnd = true, endDate = today.minusDays(1)) }
        assertEquals(listOf(FormProblem.END_BEFORE_START), EntryWizard.problems(end))
        val noEnd = atWhen(Timing.REPEATS) { it.copy(hasEnd = false, endDate = today.minusDays(1)) }
        assertTrue(EntryWizard.problems(noEnd).isEmpty()) // an end date that is "Never" does not count
        val badDay = atWhen(Timing.REPEATS) { it.copy(choice = PatternChoice.MONTH_DAY, dayOfMonth = "32") }
        assertEquals(listOf(FormProblem.DAY_OF_MONTH_INVALID), EntryWizard.problems(badDay))
        assertEquals(WizardStep.REVIEW, EntryWizard.next(noEnd).step)
    }

    @Test fun the_review_collects_every_problem_on_the_way() {
        val s = wizard().copy(step = WizardStep.REVIEW) // nothing typed yet
        assertEquals(listOf(FormProblem.AMOUNT_MISSING, FormProblem.NAME_MISSING), EntryWizard.problems(s))
        assertFalse(EntryWizard.canAdvance(s))
        assertTrue(EntryWizard.canAdvance(typed(wizard()).copy(step = WizardStep.REVIEW)))
    }

    @Test fun a_full_walk_for_every_timing_and_kind_reaches_a_valid_entry() {
        for (kind in TxKind.entries) for (timing in Timing.entries) {
            var s = EntryWizard.update(typed(wizard(kind))) { it.copy(timing = timing) }
            var guard = 0
            while (s.step != WizardStep.REVIEW && guard++ < 10) s = EntryWizard.next(s)
            assertEquals("$kind/$timing", WizardStep.REVIEW, s.step)
            assertTrue("$kind/$timing", EntryForm.build(s.form, "x") is FormResult.Valid)
        }
    }

    // ---- the below-zero check ---------------------------------------------------------------------

    @Test fun without_a_dip_the_step_after_when_is_the_review() {
        val s = EntryWizard.afterWhen(atWhen(Timing.NOW), hasDip = false)
        assertEquals(WizardStep.REVIEW, s.step)
        assertFalse(s.belowZero)
    }

    @Test fun with_a_dip_the_step_after_when_is_below_zero_and_then_the_review() {
        val below = EntryWizard.afterWhen(atWhen(Timing.NOW), hasDip = true)
        assertEquals(WizardStep.BELOW_ZERO, below.step)
        assertTrue(below.belowZero)
        assertEquals(4 to 5, EntryWizard.progress(below)) // "Add anyway" then goes on
        val review = EntryWizard.next(below)
        assertEquals(WizardStep.REVIEW, review.step)
        assertEquals(5 to 5, EntryWizard.progress(review))
    }

    @Test fun change_and_back_return_to_when_and_forget_the_check() {
        val below = EntryWizard.afterWhen(atWhen(Timing.NOW), hasDip = true)
        val changed = EntryWizard.change(below)
        assertEquals(WizardStep.WHEN, changed.step)
        assertFalse(changed.belowZero)
        assertEquals(WizardStep.WHEN, EntryWizard.back(below)!!.step)
        val review = EntryWizard.next(below)
        val back = EntryWizard.back(review)!!
        assertEquals(WizardStep.WHEN, back.step)
        assertFalse(back.belowZero)
        assertEquals(below.form, back.form) // nothing typed is lost
    }

    @Test fun editing_the_form_drops_an_earlier_below_zero_finding() {
        val below = EntryWizard.afterWhen(atWhen(Timing.NOW), hasDip = true)
        assertFalse(EntryWizard.update(below) { it.copy(amountText = "1") }.belowZero)
    }

    @Test fun after_when_does_nothing_on_other_screens() {
        val s = typed(wizard())
        assertEquals(s, EntryWizard.afterWhen(s, hasDip = true))
    }

    // ---- going back --------------------------------------------------------------------------------

    @Test fun back_from_the_first_screen_returns_null_so_the_screen_can_ask_before_closing() {
        assertNull(EntryWizard.back(wizard()))
    }

    @Test fun back_returns_to_the_previous_screen_and_keeps_everything_typed() {
        var s = typed(wizard())
        s = EntryWizard.update(s) { it.copy(timing = Timing.LATER, laterDate = today.plusDays(9)) }
        s = s.copy(step = WizardStep.REVIEW)
        val back = EntryWizard.back(s)!!
        assertEquals(WizardStep.WHEN, back.step)
        assertEquals(s.form, back.form)
        val again = EntryWizard.back(back)!!
        assertEquals(WizardStep.NAME, again.step)
        assertEquals("12.5", again.form.amountText)
        assertFalse(again.attempted)
    }

    @Test fun go_back_all_the_way_and_forward_again_without_losing_input() {
        var s = typed(wizard())
        s = EntryWizard.update(s) { it.copy(timing = Timing.REPEATS, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)) }
        s = s.copy(step = WizardStep.WHEN)
        val form = s.form
        s = EntryWizard.back(EntryWizard.back(s)!!)!!
        assertEquals(WizardStep.AMOUNT, s.step)
        s = EntryWizard.next(EntryWizard.next(s))
        assertEquals(WizardStep.WHEN, s.step)
        assertEquals(form, s.form)
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

    // ---- preview ------------------------------------------------------------------------------------

    private fun preview(
        choice: PatternChoice,
        start: LocalDate,
        end: LocalDate? = null,
        weekdays: Set<DayOfWeek> = setOf(start.dayOfWeek),
        dayOfMonth: String = start.dayOfMonth.toString(),
    ): List<LocalDate> {
        val base = typed(wizard()).form.copy(timing = Timing.REPEATS)
        val f = base.copy(choice = choice, startDate = start, weekdays = weekdays, dayOfMonth = dayOfMonth)
            .let { if (end != null) it.copy(hasEnd = true, endDate = end) else it }
        return EntryWizard.occurrencePreview(f)
    }

    @Test fun preview_shows_the_next_three_dates_of_every_frequency() {
        assertEquals(listOf(d(2026, 10, 15), d(2026, 10, 16), d(2026, 10, 17)), preview(PatternChoice.DAILY, today))
        assertEquals(listOf(d(2026, 10, 15), d(2026, 10, 22), d(2026, 10, 29)), preview(PatternChoice.WEEKLY, today))
        assertEquals(listOf(d(2026, 10, 15), d(2026, 10, 29), d(2026, 11, 12)), preview(PatternChoice.EVERY_2_WEEKS, today))
        assertEquals(listOf(d(2026, 12, 31), d(2027, 1, 31), d(2027, 2, 28)), preview(PatternChoice.MONTH_DAY, d(2026, 12, 31)))
        assertEquals(listOf(d(2026, 11, 1), d(2026, 12, 1), d(2027, 1, 1)), preview(PatternChoice.MONTH_FIRST, today))
        assertEquals(listOf(d(2026, 10, 31), d(2026, 11, 30), d(2026, 12, 31)), preview(PatternChoice.MONTH_LAST, today))
        assertEquals(listOf(d(2027, 2, 28), d(2028, 2, 28), d(2029, 2, 28)), preview(PatternChoice.YEARLY, d(2027, 2, 28)))
    }

    @Test fun preview_for_several_weekdays() {
        assertEquals(
            listOf(d(2026, 10, 15), d(2026, 10, 19), d(2026, 10, 22)),
            preview(PatternChoice.WEEKLY, today, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)),
        )
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

    @Test fun entry_of_builds_what_would_be_saved() {
        val form = typed(wizard()).form
        assertTrue(EntryWizard.entryOf(form.copy(timing = Timing.NOW), "x") is NewEntry.Now)
        val later = EntryWizard.entryOf(form.copy(timing = Timing.LATER, laterDate = d(2026, 11, 3)), "x") as NewEntry.Planned
        assertEquals(d(2026, 11, 3), later.date)
        assertNull(EntryWizard.entryOf(form.copy(amountText = ""), "x"))
    }

    // ---- the discard guard ---------------------------------------------------------------------------

    @Test fun closing_an_untouched_wizard_needs_no_question() {
        val initial = wizard()
        val (state, decision) = DiscardState().onCloseRequested(initial, initial)
        assertEquals(CloseDecision.CLOSE, decision)
        assertFalse(state.asking)
    }

    @Test fun closing_after_typing_asks_first_and_keep_going_changes_nothing() {
        val initial = wizard()
        val current = typed(initial)
        val (asking, decision) = DiscardState().onCloseRequested(current, initial)
        assertEquals(CloseDecision.ASK, decision)
        assertTrue(asking.asking)
        val kept = asking.keepGoing()
        assertFalse(kept.asking)
        // the wizard itself was never touched by the question
        assertEquals("12.5", current.form.amountText)
        assertEquals(current.step, current.step)
        assertFalse(asking.discard().asking)
    }

    @Test fun any_screen_past_the_first_counts_as_progress() {
        val initial = wizard()
        assertTrue(DiscardGuard.hasProgress(initial.copy(step = WizardStep.NAME), initial))
        assertFalse(DiscardGuard.hasProgress(initial, initial))
        assertTrue(DiscardGuard.hasProgress(EntryWizard.update(initial) { it.copy(amountText = "1") }, initial))
    }
}
