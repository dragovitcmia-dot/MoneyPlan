package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class LastPaymentTest {
    private val zone = ZoneId.of("Africa/Tunis")
    private val today = d(2026, 10, 12)

    private fun rows(r: RecurringRule, from: LocalDate = r.startDate): List<Transaction> =
        Recurrence.occurrences(r, from, r.endDate ?: from.plusDays(60)).mapIndexed { i, date -> r.occurrenceFor(date).copy(id = r.id * 1000 + i) }

    // ---- last occurrence per pattern ----------------------------------------------------------------------

    @Test fun a_daily_series_ends_on_its_end_date() {
        val r = rule(RecurrencePattern.Daily, today, end = d(2026, 12, 5), id = 1)
        assertEquals(d(2026, 12, 5), SeriesLast.lastDate(r, rows(r)))
    }

    @Test fun a_weekly_series_ends_on_the_last_matching_weekday_before_the_end() {
        val r = rule(RecurrencePattern.Weekly(DayOfWeek.MONDAY), today, end = d(2026, 12, 5), id = 1) // Saturday
        assertEquals(d(2026, 11, 30), SeriesLast.lastDate(r, rows(r)))
    }

    @Test fun a_weekday_set_ends_on_the_last_of_its_days() {
        val r = rule(RecurrencePattern.WeeklyDays(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)), today, end = d(2026, 12, 6), id = 1) // Sunday
        assertEquals(d(2026, 12, 3), SeriesLast.lastDate(r, rows(r)))
    }

    @Test fun a_last_day_of_the_month_series_ends_on_the_last_month_end_inside_the_range() {
        val r = rule(RecurrencePattern.MonthLast, today, end = d(2026, 12, 20), id = 1)
        assertEquals(d(2026, 11, 30), SeriesLast.lastDate(r, rows(r)))
        val full = rule(RecurrencePattern.MonthLast, today, end = d(2026, 12, 31), id = 1)
        assertEquals(d(2026, 12, 31), SeriesLast.lastDate(full, rows(full)))
    }

    @Test fun a_first_day_of_the_month_series_ends_on_the_last_first() {
        val r = rule(RecurrencePattern.MonthFirst, today, end = d(2026, 12, 15), id = 1)
        assertEquals(d(2026, 12, 1), SeriesLast.lastDate(r, rows(r)))
    }

    @Test fun day_31_clamps_to_the_end_of_a_short_month() {
        val r = rule(RecurrencePattern.DayOfMonth(31), today, end = d(2027, 2, 28), id = 1)
        assertEquals(d(2027, 2, 28), SeriesLast.lastDate(r, rows(r)))
    }

    @Test fun a_series_without_an_end_date_has_no_last_payment() {
        val r = rule(RecurrencePattern.Daily, today, id = 1)
        assertNull(SeriesLast.lastDate(r, rows(r)))
        assertTrue(SeriesLast.lastDates(listOf(r), rows(r)).isEmpty())
    }

    @Test fun a_skipped_last_item_makes_the_one_before_the_last() {
        val r = rule(RecurrencePattern.Weekly(DayOfWeek.MONDAY), today, end = d(2026, 12, 5), id = 1)
        val all = rows(r).map { if (it.date == d(2026, 11, 30)) it.copy(status = TxStatus.SKIPPED) else it }
        assertEquals(d(2026, 11, 23), SeriesLast.lastDate(r, all))
    }

    @Test fun a_postponed_last_item_counts_where_it_was_moved_to() {
        val r = rule(RecurrencePattern.Weekly(DayOfWeek.MONDAY), today, end = d(2026, 12, 5), id = 1)
        val all = rows(r).map { if (it.date == d(2026, 11, 30)) it.copy(date = d(2026, 12, 8)) else it }
        assertEquals(d(2026, 12, 8), SeriesLast.lastDate(r, all))
    }

    @Test fun lastDates_lists_only_series_with_an_end() {
        val a = rule(RecurrencePattern.Daily, today, end = d(2026, 11, 1), id = 1)
        val b = rule(RecurrencePattern.Daily, today, id = 2)
        assertEquals(mapOf(1L to d(2026, 11, 1)), SeriesLast.lastDates(listOf(a, b), rows(a)))
    }

    // ---- the reminder ----------------------------------------------------------------------------------------------

    private fun run(
        date: LocalDate,
        time: String,
        rules: List<RecurringRule>,
        posted: Set<String> = emptySet(),
        series: SeriesContext = SeriesContext(),
        settings: NotificationSettings = NotificationSettings(),
        items: List<Transaction> = rules.flatMap { rows(it) },
    ): List<NotificationDecision> {
        val at = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
        return Notifications.reconcile(at, zone, settings, items, rules, emptyList(), posted, series)
            .filter { it.type == NotificationType.LAST_PAYMENT }
    }

    private val gym = rule(RecurrencePattern.Daily, d(2026, 10, 1), end = d(2026, 12, 5), id = 4, amount = 50).copy(description = "Gym")

    @Test fun the_reminder_comes_a_week_before_the_last_payment_at_the_reminder_time() {
        val r = run(d(2026, 11, 28), "12:00", listOf(gym))
        assertEquals(1, r.size)
        assertEquals("Last payment", r[0].title)
        assertEquals("Gym −50 TND ends 05 DEC. Extend?", r[0].body)
        assertEquals(NotificationRoute.SERIES, r[0].route)
        assertEquals(4L, r[0].ruleId)
        assertEquals(d(2026, 12, 5), r[0].relatedDate)
        assertTrue(r[0].post)
        assertEquals("LAST|4|2026-12-05", r[0].key)
        assertEquals(r[0].key, r[0].bellKey)
    }

    @Test fun it_is_not_sent_earlier_than_a_week_before() {
        assertTrue(run(d(2026, 11, 27), "12:00", listOf(gym)).isEmpty())
    }

    @Test fun it_waits_for_the_reminder_time_of_that_day() {
        assertTrue(run(d(2026, 11, 28), "11:59", listOf(gym)).isEmpty())
        assertEquals(1, run(d(2026, 11, 28), "12:00", listOf(gym)).size)
    }

    @Test fun it_is_not_sent_inside_quiet_hours() {
        assertTrue(run(d(2026, 11, 28), "22:30", listOf(gym)).isEmpty())
        assertTrue(run(d(2026, 11, 28), "07:00", listOf(gym)).isEmpty())
    }

    @Test fun a_series_created_with_its_last_payment_inside_the_week_gets_the_next_reminder() {
        val r = rule(RecurrencePattern.Daily, today, end = today.plusDays(3), id = 9).copy(description = "Trial")
        assertEquals(1, run(today, "12:00", listOf(r)).size)
    }

    @Test fun catch_up_after_the_app_was_not_opened_still_posts_while_the_last_day_is_not_over() {
        assertEquals(1, run(d(2026, 12, 3), "15:00", listOf(gym)).size) // the days before were missed
        assertEquals(1, run(d(2026, 12, 5), "13:00", listOf(gym)).size) // on the last day itself
        assertTrue(run(d(2026, 12, 6), "13:00", listOf(gym)).isEmpty()) // too late to be useful
    }

    @Test fun it_is_sent_once_per_series_and_last_date() {
        val first = run(d(2026, 11, 28), "12:00", listOf(gym))
        val posted = setOf(first[0].key, first[0].bellKey!!)
        assertTrue(run(d(2026, 11, 28), "12:30", listOf(gym), posted = posted).isEmpty())
        assertTrue(run(d(2026, 12, 1), "12:00", listOf(gym), posted = posted).isEmpty())
    }

    @Test fun extending_so_the_last_date_changes_makes_a_new_key() {
        val first = run(d(2026, 11, 28), "12:00", listOf(gym))
        val posted = setOf(first[0].key)
        val extended = gym.copy(endDate = d(2027, 1, 5))
        assertTrue(run(d(2026, 12, 2), "12:00", listOf(extended), posted = posted).isEmpty()) // far from the new last day
        val later = run(d(2026, 12, 30), "12:00", listOf(extended), posted = posted)
        assertEquals("LAST|4|2027-01-05", later.single().key)
        assertEquals("Gym −50 TND ends 05 JAN. Extend?", later.single().body)
    }

    @Test fun nothing_for_a_series_without_an_end_date() {
        val open = gym.copy(endDate = null)
        assertTrue(run(d(2026, 11, 28), "12:00", listOf(open), items = rows(open)).isEmpty())
    }

    @Test fun nothing_for_a_series_that_was_ended_explicitly() {
        assertTrue(run(d(2026, 11, 28), "12:00", listOf(gym), series = SeriesContext(endedRuleIds = setOf(4))).isEmpty())
    }

    @Test fun nothing_for_a_deleted_series_or_one_that_is_switched_off() {
        assertTrue(run(d(2026, 11, 28), "12:00", emptyList(), items = rows(gym)).isEmpty())
        assertTrue(run(d(2026, 11, 28), "12:00", listOf(gym.copy(enabled = false))).isEmpty())
    }

    @Test fun income_has_its_own_wording() {
        val salary = incomeSource(RecurrencePattern.Daily, d(2026, 10, 1), amount = 30, id = 5).copy(description = "Salary", endDate = d(2026, 12, 5))
        val r = run(d(2026, 11, 28), "12:00", listOf(salary))
        assertEquals("Salary +30 TND ends 05 DEC. Extend?", r.single().body)
        assertEquals(30_000L, r.single().amountMillimes)
    }

    @Test fun a_skipped_final_occurrence_moves_the_reminder_to_the_new_last_day() {
        val all = rows(gym).map { if (it.date == d(2026, 12, 5)) it.copy(status = TxStatus.SKIPPED) else it }
        val r = run(d(2026, 11, 28), "12:00", listOf(gym), items = all)
        assertEquals("LAST|4|2026-12-04", r.single().key)
    }

    @Test fun the_reminder_follows_the_heads_up_switch() {
        assertTrue(run(d(2026, 11, 28), "12:00", listOf(gym), settings = NotificationSettings(headsUp = false)).isEmpty())
        assertTrue(run(d(2026, 11, 28), "12:00", listOf(gym), settings = NotificationSettings(enabled = false)).isEmpty())
    }

    @Test fun two_series_ending_together_get_one_reminder_each() {
        val a = gym
        val b = gym.copy(id = 6, description = "Phone")
        assertEquals(setOf(4L, 6L), run(d(2026, 11, 28), "12:00", listOf(a, b)).map { it.ruleId }.toSet())
    }

    @Test fun the_last_payment_is_flagged_in_the_upcoming_rows_and_the_day_sheet() {
        // a weekly series (slower than weekly-or-more-often collapses nothing): its last payment is flagged in the Upcoming rows
        val saturdays = rule(RecurrencePattern.Weekly(DayOfWeek.SATURDAY), d(2026, 10, 1), end = d(2026, 12, 5), id = 5, amount = 50).copy(description = "Gym")
        val weeklyItems = rows(saturdays).filter { !it.date.isBefore(today) }
        val weeklyEngine = BalanceEngine(settings(d(2026, 10, 1), 1000), weeklyItems, today)
        val flagged = Upcoming.build(weeklyEngine, listOf(saturdays), UpcomingType.ALL, DayOfWeek.MONDAY).groups.flatMap { it.rows }.filter { it.last }
        assertEquals(1, flagged.size)
        assertEquals(d(2026, 12, 5), flagged.single().tx.date)
        // a daily series lists only its next occurrence in Upcoming, but the day sheet still flags its last day
        val items = rows(gym).filter { !it.date.isBefore(today) }
        val engine = BalanceEngine(settings(d(2026, 10, 1), 1000), items, today)
        assertTrue(Upcoming.build(engine, listOf(gym), UpcomingType.ALL, DayOfWeek.MONDAY).groups.flatMap { it.rows }.none { it.last })
        val month = CalendarSummaries.month(engine, listOf(gym), java.time.YearMonth.of(2026, 12), DayOfWeek.MONDAY)
        assertTrue(month.day(d(2026, 12, 5))!!.items.single().last)
        assertFalse(month.day(d(2026, 12, 4))!!.items.single().last)
    }

    @Test fun a_single_item_of_an_ask_amount_series_carries_its_id_for_the_notification_tap() {
        val r = rule(RecurrencePattern.Daily, today, id = 8)
        val item = r.occurrenceFor(today).copy(id = 77)
        val at = today.atTime(12, 0).atZone(zone).toInstant()
        val out = Notifications.reconcile(at, zone, NotificationSettings(), listOf(item), listOf(r), emptyList(), emptySet(), SeriesContext(askAmountRuleIds = setOf(8)))
        assertEquals(77L, out.single { it.type == NotificationType.DAY_OF }.itemId)
        val plain = Notifications.reconcile(at, zone, NotificationSettings(), listOf(item), listOf(r), emptyList(), emptySet())
        assertNull(plain.single { it.type == NotificationType.DAY_OF }.itemId)
    }
}
