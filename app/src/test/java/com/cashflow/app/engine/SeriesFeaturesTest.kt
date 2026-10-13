package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class SeriesFeaturesTest {
    private val today = d(2026, 10, 12)

    // ---- texts ----------------------------------------------------------------------------------

    @Test fun schedule_lines_read_short_and_plain() {
        assertEquals("Every day", SeriesText.schedule(RecurrencePattern.Daily))
        assertEquals("Every Mon", SeriesText.schedule(RecurrencePattern.Weekly(DayOfWeek.MONDAY)))
        assertEquals("Mon, Thu", SeriesText.schedule(RecurrencePattern.WeeklyDays(setOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY))))
        assertEquals("Every 2 weeks", SeriesText.schedule(RecurrencePattern.EveryNDays(14, today)))
        assertEquals("Every week", SeriesText.schedule(RecurrencePattern.EveryNDays(7, today)))
        assertEquals("Every 10 days", SeriesText.schedule(RecurrencePattern.EveryNDays(10, today)))
        assertEquals("Monthly on the 1st", SeriesText.schedule(RecurrencePattern.MonthFirst))
        assertEquals("Monthly on the last day", SeriesText.schedule(RecurrencePattern.MonthLast))
        assertEquals("Yearly 05 OCT", SeriesText.schedule(RecurrencePattern.Yearly(10, 5)))
    }

    @Test fun day_of_month_uses_ordinals() {
        assertEquals("Monthly on the 5th", SeriesText.schedule(RecurrencePattern.DayOfMonth(5)))
        assertEquals("Monthly on the 1st", SeriesText.schedule(RecurrencePattern.DayOfMonth(1)))
        assertEquals("Monthly on the 2nd", SeriesText.schedule(RecurrencePattern.DayOfMonth(2)))
        assertEquals("Monthly on the 3rd", SeriesText.schedule(RecurrencePattern.DayOfMonth(3)))
        assertEquals("Monthly on the 11th", SeriesText.schedule(RecurrencePattern.DayOfMonth(11)))
        assertEquals("Monthly on the 22nd", SeriesText.schedule(RecurrencePattern.DayOfMonth(22)))
    }

    @Test fun yearly_on_the_29th_of_february_still_has_a_text() {
        assertEquals("Yearly 29 FEB", SeriesText.schedule(RecurrencePattern.Yearly(2, 29)))
    }

    @Test fun the_end_line_is_a_date_or_no_end() {
        assertEquals("No end", SeriesText.endLine(rule(RecurrencePattern.Daily, today)))
        assertEquals("Ends 05 DEC 2026", SeriesText.endLine(rule(RecurrencePattern.Daily, today, end = d(2026, 12, 5))))
    }

    // ---- the Repeating list ------------------------------------------------------------------------------

    private fun pendingOf(ruleId: Long, date: java.time.LocalDate) =
        tx(date, 10, id = ruleId * 100 + date.dayOfMonth, source = TxSource.RECURRING).copy(recurringRuleId = ruleId, occurrenceDate = date)

    @Test fun series_are_listed_soonest_next_occurrence_first() {
        val a = rule(RecurrencePattern.Daily, today, id = 1)
        val b = rule(RecurrencePattern.Daily, today, id = 2)
        val c = rule(RecurrencePattern.Daily, today, id = 3)
        val rows = listOf(pendingOf(1, today.plusDays(5)), pendingOf(2, today.plusDays(1)), pendingOf(3, today.plusDays(3)))
        assertEquals(listOf(2L, 3L, 1L), SeriesList.build(listOf(a, b, c), rows, SeriesContext(), today).map { it.rule.id })
    }

    @Test fun a_series_without_a_next_occurrence_goes_last() {
        val a = rule(RecurrencePattern.Daily, today, id = 1)
        val b = rule(RecurrencePattern.Daily, today, id = 2)
        val list = SeriesList.build(listOf(a, b), listOf(pendingOf(2, today.plusDays(2))), SeriesContext(), today)
        assertEquals(listOf(2L, 1L), list.map { it.rule.id })
        assertNull(list.last().nextDate)
    }

    @Test fun an_end_date_that_passed_by_itself_gets_the_ended_tag() {
        val r = rule(RecurrencePattern.Daily, today.minusDays(40), end = today.minusDays(3), id = 1)
        val row = SeriesList.build(listOf(r), emptyList(), SeriesContext(), today).single()
        assertTrue(row.ended)
        assertEquals("Ends ${AppDates.format(today.minusDays(3))}", row.endLine)
    }

    @Test fun a_running_series_has_no_ended_tag_and_shows_its_end_or_no_end() {
        val open = rule(RecurrencePattern.Daily, today, id = 1)
        val ends = rule(RecurrencePattern.Daily, today, end = today.plusDays(9), id = 2)
        val list = SeriesList.build(listOf(open, ends), emptyList(), SeriesContext(), today)
        assertTrue(list.none { it.ended })
        assertEquals(listOf("No end", "Ends ${AppDates.format(today.plusDays(9))}"), list.map { it.endLine })
    }

    @Test fun an_explicitly_ended_series_leaves_the_list_once_its_end_date_has_passed() {
        val r = rule(RecurrencePattern.Daily, today.minusDays(40), end = today.minusDays(1), id = 1)
        assertTrue(SeriesList.build(listOf(r), emptyList(), SeriesContext(endedRuleIds = setOf(1)), today).isEmpty())
    }

    @Test fun an_explicitly_ended_series_still_shows_until_its_end_date() {
        val r = rule(RecurrencePattern.Daily, today, end = today.plusDays(3), id = 1)
        val row = SeriesList.build(listOf(r), emptyList(), SeriesContext(endedRuleIds = setOf(1)), today).single()
        assertFalse(row.ended)
    }

    @Test fun switched_off_series_and_deleted_series_are_not_listed() {
        val off = rule(RecurrencePattern.Daily, today, id = 1, enabled = false)
        assertTrue(SeriesList.build(listOf(off), emptyList(), SeriesContext(), today).isEmpty())
        assertTrue(SeriesList.build(emptyList(), listOf(pendingOf(9, today)), SeriesContext(), today).isEmpty())
    }

    @Test fun the_list_carries_schedule_text_and_expense_and_income_alike() {
        val exp = rule(RecurrencePattern.DayOfMonth(5), today, id = 1)
        val inc = incomeSource(RecurrencePattern.Daily, today, id = 2)
        val list = SeriesList.build(listOf(exp, inc), emptyList(), SeriesContext(), today)
        assertEquals(setOf("Monthly on the 5th", "Every day"), list.map { it.schedule }.toSet())
        assertEquals(setOf(TxKind.EXPENSE, TxKind.INCOME), list.map { it.rule.kind }.toSet())
    }

    // ---- Extend --------------------------------------------------------------------------------------------

    @Test fun plus_months_counts_from_the_current_end() {
        val r = rule(RecurrencePattern.Daily, today, end = d(2026, 12, 5))
        assertEquals(SeriesExtend.Result.End(d(2027, 1, 5)), SeriesExtend.newEnd(r, ExtendChoice.Months(1), today))
        assertEquals(SeriesExtend.Result.End(d(2027, 3, 5)), SeriesExtend.newEnd(r, ExtendChoice.Months(3), today))
        assertEquals(SeriesExtend.Result.End(d(2027, 12, 5)), SeriesExtend.newEnd(r, ExtendChoice.Months(12), today))
    }

    @Test fun plus_months_on_an_ended_series_counts_from_today() {
        val r = rule(RecurrencePattern.Daily, today.minusDays(60), end = today.minusDays(10))
        assertEquals(SeriesExtend.Result.End(today.plusMonths(3)), SeriesExtend.newEnd(r, ExtendChoice.Months(3), today))
    }

    @Test fun month_end_clamps() {
        val r = rule(RecurrencePattern.Daily, today, end = d(2026, 12, 31))
        assertEquals(SeriesExtend.Result.End(d(2027, 2, 28)), SeriesExtend.newEnd(r, ExtendChoice.Months(2), today))
    }

    @Test fun no_end_removes_the_end_and_a_series_without_an_end_stays() {
        val ends = rule(RecurrencePattern.Daily, today, end = d(2026, 12, 5))
        assertEquals(SeriesExtend.Result.End(null), SeriesExtend.newEnd(ends, ExtendChoice.NoEnd, today))
        val open = rule(RecurrencePattern.Daily, today)
        assertEquals(SeriesExtend.Result.Unchanged, SeriesExtend.newEnd(open, ExtendChoice.NoEnd, today))
        assertEquals(SeriesExtend.Result.Unchanged, SeriesExtend.newEnd(open, ExtendChoice.Months(1), today))
    }

    @Test fun a_picked_date_must_lie_after_the_current_end() {
        val r = rule(RecurrencePattern.Daily, today, end = d(2026, 12, 5))
        assertEquals(SeriesExtend.Result.Unchanged, SeriesExtend.newEnd(r, ExtendChoice.Until(d(2026, 12, 5)), today))
        assertEquals(SeriesExtend.Result.Unchanged, SeriesExtend.newEnd(r, ExtendChoice.Until(d(2026, 11, 1)), today))
        assertEquals(SeriesExtend.Result.End(d(2026, 12, 6)), SeriesExtend.newEnd(r, ExtendChoice.Until(d(2026, 12, 6)), today))
    }

    @Test fun the_end_is_never_moved_earlier_by_any_choice() {
        val r = rule(RecurrencePattern.Daily, today, end = d(2026, 12, 5))
        for (choice in ExtendChoice.QUICK + ExtendChoice.Until(d(2026, 10, 20)) + ExtendChoice.Until(d(2027, 6, 1))) {
            val result = SeriesExtend.newEnd(r, choice, today)
            if (result is SeriesExtend.Result.End) assertTrue(result.date == null || result.date.isAfter(d(2026, 12, 5)))
        }
    }

    @Test fun extending_generates_the_waiting_occurrences_without_duplicates() {
        val r = rule(RecurrencePattern.Weekly(DayOfWeek.MONDAY), today, end = d(2026, 11, 2), id = 1)
        val existing = Recurrence.occurrences(r, today, d(2026, 11, 2)).map { r.occurrenceFor(it).copy(id = it.toEpochDay()) }
        val change = SeriesExtend.apply(r, existing, ExtendChoice.Months(1), today)!!
        assertEquals(d(2026, 12, 2), change.rule.endDate)
        val all = existing.map { it.occurrenceDate } + change.inserts.map { it.occurrenceDate }
        assertEquals(all.size, all.toSet().size)
        assertEquals(listOf(d(2026, 11, 9), d(2026, 11, 16), d(2026, 11, 23), d(2026, 11, 30)), change.inserts.map { it.date })
        assertTrue(change.deleteIds.isEmpty())
    }

    @Test fun extending_an_ended_series_does_not_backfill_the_days_it_was_off() {
        val r = rule(RecurrencePattern.Daily, today.minusDays(30), end = today.minusDays(10), id = 1)
        val change = SeriesExtend.apply(r, emptyList(), ExtendChoice.Months(1), today)!!
        assertTrue(change.inserts.isNotEmpty())
        assertTrue(change.inserts.all { !it.date.isBefore(today) })
        assertEquals(today, change.rule.startDate)
        assertEquals(today.plusMonths(1), change.rule.endDate)
    }

    @Test fun nothing_changes_when_the_choice_changes_nothing() {
        assertNull(SeriesExtend.apply(rule(RecurrencePattern.Daily, today), emptyList(), ExtendChoice.NoEnd, today))
    }

    // ---- what was really paid ----------------------------------------------------------------------------------

    private fun m(vararg tndValues: Int) = tndValues.map { tnd(it.toLong()) }

    @Test fun the_average_of_the_last_four_is_shown_when_it_differs_from_the_plan() {
        val s = PaidAverage.of(tnd(75), m(70, 72, 70, 72, 40))!!
        assertEquals(tnd(71), s.average)
        assertEquals(4, s.count)
        assertEquals("Last 4 average 71 TND, planned 75 TND", s.text)
        assertEquals("Update planned amount to 71", s.updateLabel)
    }

    @Test fun at_least_three_accepted_occurrences_are_needed() {
        assertNull(PaidAverage.of(tnd(75), m(70, 70)))
        assertEquals(3, PaidAverage.of(tnd(75), m(70, 71, 72))!!.count)
    }

    @Test fun it_is_hidden_when_everything_paid_equals_the_plan_or_the_average_does() {
        assertNull(PaidAverage.of(tnd(75), m(75, 75, 75, 75)))
        assertNull(PaidAverage.of(tnd(75), m(70, 80, 75)))
    }

    @Test fun only_the_last_four_count_and_the_average_rounds_half_up_to_a_cent() {
        assertEquals(tnd(75) , PaidAverage.of(tnd(80), m(75, 75, 75, 75, 1))!!.average)
        // 70.005 + 70.000 + 70.000 = 210.005 / 3 = 70.00166 -> 70.00; 70.010 + 70.010 + 70.015 = 210.035 / 3 = 70.0116 -> 70.01
        assertEquals(70_000L, PaidAverage.of(tnd(75), listOf(70_005L, 70_000L, 70_000L))!!.average)
        assertEquals(70_010L, PaidAverage.of(tnd(75), listOf(70_010L, 70_010L, 70_015L))!!.average)
        // exactly half a cent up: 70.000 + 70.000 + 70.005 + 70.005 = 280.010 / 4 = 70.0025 -> 70.00 ; 70.0075 -> 70.01
        assertEquals(70_010L, PaidAverage.of(tnd(75), listOf(70_010L, 70_010L, 70_005L, 70_005L))!!.average)
    }

    // ---- what each item offers -----------------------------------------------------------------------------------

    @Test fun a_due_waiting_item_can_be_answered_postponed_and_edited() {
        val a = ItemActionMap.forItem(TxStatus.PENDING, today, today, belongsToSeries = false)
        assertTrue(a.accept && a.reject && a.postpone && a.editItem)
        assertFalse(a.markWaiting)
        assertFalse(a.seriesEdit || a.seriesExtend || a.seriesEnd || a.seriesDelete)
    }

    @Test fun a_future_item_cannot_be_answered_yet_but_can_be_postponed() {
        val a = ItemActionMap.forItem(TxStatus.PENDING, today.plusDays(3), today, belongsToSeries = false)
        assertFalse(a.accept || a.reject)
        assertTrue(a.postpone && a.editItem)
    }

    @Test fun an_accepted_item_shows_details_and_can_go_back_to_waiting() {
        val a = ItemActionMap.forItem(TxStatus.CONFIRMED, today, today, belongsToSeries = false)
        assertFalse(a.accept || a.reject || a.postpone)
        assertTrue(a.markWaiting && a.editItem)
    }

    @Test fun every_item_of_a_series_offers_all_four_series_actions_whatever_its_status() {
        for (status in TxStatus.entries) for (offset in listOf(-5L, 0L, 5L)) {
            val a = ItemActionMap.forItem(status, today.plusDays(offset), today, belongsToSeries = true)
            assertTrue("$status $offset", a.seriesEdit && a.seriesExtend && a.seriesEnd && a.seriesDelete)
        }
    }

    @Test fun items_outside_a_series_never_offer_series_actions() {
        for (status in TxStatus.entries) {
            val a = ItemActionMap.forItem(status, today, today, belongsToSeries = false)
            assertFalse(a.seriesEdit || a.seriesExtend || a.seriesEnd || a.seriesDelete)
        }
    }

    // ---- the Edit form -------------------------------------------------------------------------------------------

    private fun roundTrip(p: RecurrencePattern): RecurringRule {
        val r = rule(p, today.minusDays(3), id = 1, amount = 75)
        val form = SeriesForm.formFor(r, today)
        assertEquals(Timing.REPEATS, form.timing)
        assertFalse(form.startDate.isBefore(today))
        return SeriesForm.ruleFrom(r, form)!!
    }

    @Test fun every_schedule_survives_a_trip_through_the_form() {
        val patterns = listOf(
            RecurrencePattern.Daily,
            RecurrencePattern.Weekly(DayOfWeek.THURSDAY),
            RecurrencePattern.WeeklyDays(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
            RecurrencePattern.MonthFirst,
            RecurrencePattern.MonthLast,
            RecurrencePattern.DayOfMonth(17),
        )
        for (p in patterns) assertEquals(p, roundTrip(p).pattern)
    }

    @Test fun every_n_days_keeps_its_phase_through_the_form() {
        val original = rule(RecurrencePattern.EveryNDays(10, d(2026, 9, 1)), d(2026, 9, 1), id = 1)
        val back = SeriesForm.ruleFrom(original, SeriesForm.formFor(original, today))!!
        val a = Recurrence.occurrences(original, today, today.plusDays(100))
        val b = Recurrence.occurrences(back, today, today.plusDays(100))
        assertEquals(a, b)
    }

    @Test fun every_two_weeks_and_yearly_keep_their_dates_through_the_form() {
        val two = rule(RecurrencePattern.EveryNDays(14, d(2026, 9, 3)), d(2026, 9, 3), id = 1)
        val backTwo = SeriesForm.ruleFrom(two, SeriesForm.formFor(two, today))!!
        assertEquals(Recurrence.occurrences(two, today, today.plusDays(90)), Recurrence.occurrences(backTwo, today, today.plusDays(90)))
        val yearly = rule(RecurrencePattern.Yearly(11, 20), d(2025, 11, 20), id = 1)
        val backYearly = SeriesForm.ruleFrom(yearly, SeriesForm.formFor(yearly, today))!!
        assertEquals(RecurrencePattern.Yearly(11, 20), backYearly.pattern)
    }

    @Test fun the_form_carries_amount_name_and_end_date_and_changing_them_changes_the_series() {
        val r = rule(RecurrencePattern.Daily, today, end = d(2026, 12, 5), id = 7, amount = 75).copy(description = "Gym")
        val form = SeriesForm.formFor(r, today)
        assertEquals("75", form.amountText)
        assertEquals("Gym", form.description)
        assertTrue(form.hasEnd)
        val edited = SeriesForm.ruleFrom(r, form.copy(amountText = "80", description = "Gym plus", hasEnd = false, choice = PatternChoice.MONTH_DAY, dayOfMonth = "5"))!!
        assertEquals(7L, edited.id)
        assertEquals(tnd(80), edited.amount)
        assertEquals("Gym plus", edited.description)
        assertNull(edited.endDate)
        assertEquals(RecurrencePattern.DayOfMonth(5), edited.pattern)
    }

    @Test fun an_invalid_form_gives_no_series() {
        val r = rule(RecurrencePattern.Daily, today, id = 1)
        assertNull(SeriesForm.ruleFrom(r, SeriesForm.formFor(r, today).copy(amountText = "")))
        assertNull(SeriesForm.ruleFrom(r, SeriesForm.formFor(r, today).copy(choice = PatternChoice.WEEKLY, weekdays = emptySet())))
    }
}
