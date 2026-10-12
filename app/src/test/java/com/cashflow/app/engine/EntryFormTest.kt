package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for "Complete the schedule": the old form computed `validate(entry) ?: "Complete the
 * schedule."`, and validate() returns null when the entry is VALID, so every valid entry was
 * reported as incomplete and Save stayed disabled.
 */
class EntryFormTest {
    private val today = d(2026, 10, 15) // a Thursday
    private val start = d(2026, 10, 1)

    private fun minimal(kind: TxKind, timing: Timing) =
        EntryFormState.initial(kind, today).copy(amountText = "12.5", timing = timing)

    private fun build(state: EntryFormState) = EntryForm.build(state, "Default", start)

    @Test fun defaults_are_now_tomorrow_and_weekly_on_todays_weekday_starting_today() {
        val s = EntryFormState.initial(TxKind.EXPENSE, today)
        assertEquals(Timing.NOW, s.timing)
        assertEquals(today.plusDays(1), s.laterDate)
        assertEquals(PatternChoice.WEEKLY, s.choice)
        assertEquals(today.dayOfWeek, s.weekday)
        assertEquals(today, s.startDate)
        assertFalse(s.hasEnd)
    }

    @Test fun every_timing_and_kind_combination_with_only_an_amount_is_valid() {
        for (kind in TxKind.entries) for (timing in Timing.entries) {
            val result = build(minimal(kind, timing))
            assertTrue("$kind/$timing should be valid but was $result", result is FormResult.Valid)
        }
    }

    @Test fun every_combination_is_persisted_and_visible_in_the_balance() {
        for (kind in TxKind.entries) for (timing in Timing.entries) {
            val entry = (build(minimal(kind, timing)) as FormResult.Valid).entry
            val tx = EntryPlanner.transactionFor(entry, today)
            val rule = EntryPlanner.ruleFor(entry)
            assertTrue("$kind/$timing must produce a transaction or a rule", (tx != null) xor (rule != null))
            val rows = tx?.let { listOf(it) } ?: RuleMaintenance.materialize(rule!!, emptyList(), today)
            assertTrue("$kind/$timing produced no rows", rows.isNotEmpty())
            val engine = BalanceEngine(settings(start, 100), rows, today)
            val sign = if (kind == TxKind.EXPENSE) -1 else 1
            // Whatever the timing, the money shows up in the forecast for the following month.
            val forecastEnd = engine.forecast(today, today.plusDays(30)).last().closing
            assertTrue("$kind/$timing did not change the forecast", forecastEnd != tnd(100) && (forecastEnd - tnd(100)) * sign > 0)
        }
    }

    @Test fun now_is_stored_confirmed_and_counts_immediately() {
        val entry = (build(minimal(TxKind.EXPENSE, Timing.NOW)) as FormResult.Valid).entry
        val tx = EntryPlanner.transactionFor(entry, today)!!
        assertEquals(TxStatus.CONFIRMED, tx.status)
        assertEquals(tnd(100) - 12_500, BalanceEngine(settings(start, 100), listOf(tx), today).available())
    }

    @Test fun repeats_defaults_to_a_weekly_rule_from_today() {
        val entry = (build(minimal(TxKind.EXPENSE, Timing.REPEATS)) as FormResult.Valid).entry as NewEntry.Recurring
        assertEquals(RecurrencePattern.Weekly(today.dayOfWeek), entry.pattern)
        assertEquals(today, entry.startDate)
        assertEquals(null, entry.endDate)
        val rows = RuleMaintenance.materialize(EntryPlanner.ruleFor(entry)!!, emptyList(), today)
        assertEquals(today, rows.first().date)
    }

    @Test fun daily_is_allowed_for_income_and_starts_after_the_balance_start_day() {
        val state = minimal(TxKind.INCOME, Timing.REPEATS).copy(choice = PatternChoice.DAILY, startDate = start)
        val entry = (build(state) as FormResult.Valid).entry as NewEntry.Recurring
        assertEquals(RecurrencePattern.Daily, entry.pattern)
        assertEquals(start.plusDays(1), entry.startDate)
    }

    @Test fun the_same_five_frequencies_are_offered_for_expenses_and_income() {
        val five = listOf(PatternChoice.DAILY, PatternChoice.WEEKLY, PatternChoice.EVERY_2_WEEKS, PatternChoice.MONTH_DAY, PatternChoice.YEARLY)
        assertEquals(five, EntryForm.choicesFor(TxKind.EXPENSE))
        assertEquals(five, EntryForm.choicesFor(TxKind.INCOME))
        assertTrue(build(minimal(TxKind.EXPENSE, Timing.REPEATS).copy(choice = PatternChoice.DAILY)) is FormResult.Valid)
    }

    @Test fun every_frequency_for_both_kinds_is_persisted_and_visible_in_the_balance() {
        for (kind in TxKind.entries) for (choice in EntryForm.choicesFor(kind)) {
            val form = EntryWizard.withFrequency(minimal(kind, Timing.REPEATS).copy(startDate = today), choice)
            val entry = (build(form) as FormResult.Valid).entry as NewEntry.Recurring
            val rule = EntryPlanner.ruleFor(entry)!!
            val rows = RuleMaintenance.materialize(rule, emptyList(), today)
            assertTrue("$kind/$choice produced no rows", rows.isNotEmpty())
            assertEquals("$kind/$choice first occurrence is the start day", today, rows.first().date)
            val engine = BalanceEngine(settings(start, 100), rows, today)
            val sign = if (kind == TxKind.EXPENSE) -1 else 1
            val end = engine.forecast(today, today.plusDays(30)).last().closing
            assertTrue("$kind/$choice did not change the forecast", (end - tnd(100)) * sign > 0)
        }
    }

    @Test fun every_wizard_path_ends_in_a_stored_row_or_rule() {
        for (kind in TxKind.entries) for (timing in Timing.entries) {
            var s = EntryWizard.update(EntryWizard.start(kind, today)) { it.copy(amountText = "12.5", description = "Thing", timing = timing) }
            while (s.step != WizardStep.REVIEW) s = EntryWizard.next(s)
            val entry = (EntryForm.build(s.form, "x", start) as FormResult.Valid).entry
            assertTrue("$kind/$timing", (EntryPlanner.transactionFor(entry, today) != null) xor (EntryPlanner.ruleFor(entry) != null))
        }
    }

    @Test fun later_accepts_today_and_earlier() {
        for (date in listOf(today, today.minusDays(2), today.plusDays(1))) {
            assertTrue(build(minimal(TxKind.EXPENSE, Timing.LATER).copy(laterDate = date)) is FormResult.Valid)
        }
    }

    @Test fun a_missing_amount_names_the_amount_field() {
        for (timing in Timing.entries) {
            val r = build(minimal(TxKind.EXPENSE, timing).copy(amountText = ""))
            assertEquals(listOf(FormProblem.AMOUNT_MISSING), (r as FormResult.Invalid).problems)
        }
        val zero = build(minimal(TxKind.EXPENSE, Timing.NOW).copy(amountText = "0."))
        assertEquals(listOf(FormProblem.AMOUNT_MISSING), (zero as FormResult.Invalid).problems)
    }

    @Test fun bad_schedule_fields_are_named_individually() {
        val everyN = build(minimal(TxKind.EXPENSE, Timing.REPEATS).copy(choice = PatternChoice.EVERY_N, everyN = ""))
        assertEquals(listOf(FormProblem.EVERY_N_INVALID), (everyN as FormResult.Invalid).problems)
        val zeroN = build(minimal(TxKind.EXPENSE, Timing.REPEATS).copy(choice = PatternChoice.EVERY_N, everyN = "0"))
        assertEquals(listOf(FormProblem.EVERY_N_INVALID), (zeroN as FormResult.Invalid).problems)
        val day = build(minimal(TxKind.EXPENSE, Timing.REPEATS).copy(choice = PatternChoice.MONTH_DAY, dayOfMonth = "32"))
        assertEquals(listOf(FormProblem.DAY_OF_MONTH_INVALID), (day as FormResult.Invalid).problems)
        val end = build(minimal(TxKind.EXPENSE, Timing.REPEATS).copy(hasEnd = true, endDate = today.minusDays(1)))
        assertEquals(listOf(FormProblem.END_BEFORE_START), (end as FormResult.Invalid).problems)
    }

    @Test fun problems_in_unused_fields_are_ignored() {
        // A bad "every N" text does not matter while "weekly" is selected, or while timing is Now.
        assertTrue(build(minimal(TxKind.EXPENSE, Timing.REPEATS).copy(everyN = "abc", dayOfMonth = "99")) is FormResult.Valid)
        assertTrue(build(minimal(TxKind.EXPENSE, Timing.NOW).copy(hasEnd = true, endDate = today.minusDays(5))) is FormResult.Valid)
    }

    @Test fun blank_description_uses_the_default_text_given_by_the_ui() {
        val entry = (build(minimal(TxKind.EXPENSE, Timing.NOW)) as FormResult.Valid).entry
        assertEquals("Default", entry.description)
        val typed = (build(minimal(TxKind.EXPENSE, Timing.NOW).copy(description = " Bus ")) as FormResult.Valid).entry
        assertEquals("Bus", typed.description)
    }

    @Test fun amounts_with_two_decimals_are_kept_exactly() {
        val entry = (build(minimal(TxKind.EXPENSE, Timing.NOW).copy(amountText = "0.05")) as FormResult.Valid).entry
        assertEquals(50L, entry.amount)
    }
}
