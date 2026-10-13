package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeResolutionTest {
    private val today = d(2026, 10, 12)
    private val tomorrow = today.plusDays(1)
    private val week = today.plusDays(7)

    private fun plan(
        items: List<Transaction>,
        shown: List<ShownNotice>,
        rules: List<RecurringRule> = emptyList(),
        forecast: List<DayForecast> = emptyList(),
        series: SeriesContext = SeriesContext(),
        settings: NotificationSettings = NotificationSettings(),
    ) = NoticeResolution.plan(today, settings, items, rules, forecast, shown, series)

    private fun dayShown(title: String, body: String) = ShownNotice("DAY|$today|bell", NotificationType.DAY_OF, today, title, body)

    private val m = "−"

    // ---- day-of ------------------------------------------------------------------------------------------

    @Test fun when_everything_due_today_is_answered_the_day_notice_is_removed() {
        val items = listOf(tx(today, 10, status = TxStatus.CONFIRMED, id = 1), tx(today, 20, status = TxStatus.SKIPPED, id = 2))
        val p = plan(items, listOf(dayShown("Confirm today's payments", "2 items, ${m}30 TND")))
        assertEquals(listOf("DAY|$today|bell"), p.remove)
        assertTrue(p.updates.isEmpty())
    }

    @Test fun when_part_of_it_remains_the_notice_is_rewritten_in_place() {
        val items = listOf(tx(today, 10, id = 1, description = "Rent"), tx(today, 20, status = TxStatus.CONFIRMED, id = 2))
        val p = plan(items, listOf(dayShown("Confirm today's payments", "2 items, ${m}30 TND")))
        assertTrue(p.remove.isEmpty())
        assertEquals(NoticeUpdate("DAY|$today|bell", "Confirm today's payments", "Rent ${m}10 TND"), p.updates.single())
    }

    @Test fun an_amount_edit_updates_the_total() {
        val items = listOf(tx(today, 12, id = 1, description = "Rent"), tx(today, 5, id = 2, description = "Tea"))
        val p = plan(items, listOf(dayShown("Confirm today's payments", "2 items, ${m}15 TND")))
        assertEquals("2 items, ${m}17 TND", p.updates.single().body)
    }

    @Test fun postponing_the_last_waiting_item_to_a_later_day_removes_the_notice() {
        val p = plan(listOf(tx(tomorrow, 10, id = 1)), listOf(dayShown("Confirm today's payments", "Expense ${m}10 TND")))
        assertEquals(1, p.remove.size)
    }

    @Test fun a_nag_that_lost_its_expenses_keeps_saying_still_waiting() {
        val items = listOf(tx(today, 30, TxKind.INCOME, id = 1, description = "Gift"))
        val p = plan(items, listOf(dayShown("Still waiting", "2 items, ${m}10 TND, +30 TND")))
        assertEquals(NoticeUpdate("DAY|$today|bell", "Still waiting", "Gift +30 TND"), p.updates.single())
    }

    @Test fun nothing_happens_when_an_unrelated_item_changes() {
        val items = listOf(tx(today, 10, id = 1, description = "Rent"), tx(today.plusDays(20), 99, id = 9))
        val p = plan(items, listOf(dayShown("Confirm today's payments", "Rent ${m}10 TND")))
        assertTrue(p.isEmpty)
    }

    @Test fun an_older_day_notice_stays_while_something_is_still_waiting() {
        val old = ShownNotice("DAY|${today.minusDays(2)}|bell", NotificationType.DAY_OF, today.minusDays(2), "Confirm today's payments", "Rent ${m}10 TND")
        val p = plan(listOf(tx(today.minusDays(2), 10, id = 1, description = "Rent")), listOf(old))
        assertTrue(p.isEmpty)
    }

    // ---- heads-ups -------------------------------------------------------------------------------------------

    private fun headsUp(target: java.time.LocalDate, body: String, week: Boolean = true) =
        ShownNotice((if (week) "HU7" else "HU1") + "|$target", if (week) NotificationType.HEADS_UP_WEEK else NotificationType.HEADS_UP_DAY, target, if (week) "Coming up" else "Tomorrow", body)

    @Test fun a_heads_up_for_an_item_that_no_longer_exists_is_removed() {
        val p = plan(emptyList(), listOf(headsUp(week, "Rent ${m}300 TND, 19 OCT")))
        assertEquals(listOf("HU7|$week"), p.remove)
    }

    @Test fun a_heads_up_for_an_item_that_was_postponed_away_is_removed() {
        val p = plan(listOf(tx(week.plusDays(3), 300, id = 1, description = "Rent")), listOf(headsUp(week, "Rent ${m}300 TND, 19 OCT")))
        assertEquals(1, p.remove.size)
    }

    @Test fun a_heads_up_for_an_accepted_or_rejected_item_is_removed() {
        val p = plan(listOf(tx(tomorrow, 30, status = TxStatus.CONFIRMED, id = 1)), listOf(headsUp(tomorrow, "Expense ${m}30 TND, 13 OCT", week = false)))
        assertEquals(1, p.remove.size)
    }

    @Test fun a_grouped_heads_up_with_remaining_items_is_updated_not_removed() {
        val items = listOf(tx(week, 300, id = 1, description = "Rent"), tx(week, 50, id = 2, description = "Phone", status = TxStatus.SKIPPED))
        val p = plan(items, listOf(headsUp(week, "2 payments next week, ${m}350 TND")))
        assertTrue(p.remove.isEmpty())
        assertEquals("Rent ${m}300 TND, 19 OCT", p.updates.single().body)
    }

    @Test fun an_unchanged_heads_up_is_left_alone() {
        val p = plan(listOf(tx(week, 300, id = 1, description = "Rent")), listOf(headsUp(week, "Rent ${m}300 TND, 19 OCT")))
        assertTrue(p.isEmpty)
    }

    // ---- last payment ----------------------------------------------------------------------------------------------

    private val gym = rule(RecurrencePattern.Daily, d(2026, 10, 1), end = d(2026, 12, 5), id = 4, amount = 50)
    private val lastShown = ShownNotice("LAST|4|2026-12-05", NotificationType.LAST_PAYMENT, d(2026, 12, 5), "Last payment", "Gym ${m}50 TND ends 05 DEC. Extend?")
    private fun rowsOf(r: RecurringRule) = Recurrence.occurrences(r, r.startDate, r.endDate!!).map { r.occurrenceFor(it) }

    @Test fun a_last_payment_notice_stays_while_the_series_still_ends_that_day() {
        assertTrue(plan(rowsOf(gym), listOf(lastShown), rules = listOf(gym)).isEmpty)
    }

    @Test fun deleting_the_series_removes_its_last_payment_notice() {
        assertEquals(listOf(lastShown.key), plan(rowsOf(gym), listOf(lastShown), rules = emptyList()).remove)
    }

    @Test fun ending_the_series_explicitly_removes_the_notice() {
        assertEquals(1, plan(rowsOf(gym), listOf(lastShown), rules = listOf(gym), series = SeriesContext(endedRuleIds = setOf(4))).remove.size)
    }

    @Test fun extending_the_series_removes_the_notice_because_the_last_day_moved() {
        val extended = gym.copy(endDate = d(2027, 1, 5))
        assertEquals(1, plan(rowsOf(extended), listOf(lastShown), rules = listOf(extended)).remove.size)
        val noEnd = gym.copy(endDate = null)
        assertEquals(1, plan(rowsOf(gym), listOf(lastShown), rules = listOf(noEnd)).remove.size)
    }

    // ---- shortage and the rest --------------------------------------------------------------------------------------

    private val shortShown = ShownNotice("SHORT|BELL|2026-10-16|40000", NotificationType.SHORTAGE, d(2026, 10, 16), "Low balance ahead", "Short by 40 TND before 16 OCT, save 10/day")

    @Test fun a_shortage_notice_goes_when_no_negative_day_is_left_and_stays_otherwise() {
        val fine = (0..10).map { DayForecast(today.plusDays(it.toLong()), 0, 0, 0, 100_000, emptyList()) }
        assertEquals(1, plan(emptyList(), listOf(shortShown), forecast = fine).remove.size)
        val negative = fine + DayForecast(today.plusDays(11), 0, 0, 0, -5_000, emptyList())
        assertTrue(plan(emptyList(), listOf(shortShown), forecast = negative).isEmpty)
    }

    @Test fun informational_rows_always_stay() {
        val info = listOf(
            ShownNotice("AUTO|$today", NotificationType.AUTO_INCOME, today, "Income received", "+30 TND"),
            ShownNotice("TEST|1", NotificationType.INFO, today, "Test notification", "Reminders are working."),
        )
        assertTrue(plan(emptyList(), info).isEmpty)
    }

    @Test fun nothing_shown_means_nothing_to_do() {
        assertTrue(plan(listOf(tx(today, 10, id = 1)), emptyList()).isEmpty)
    }

    @Test fun several_notices_are_resolved_independently() {
        val items = listOf(tx(today, 10, status = TxStatus.CONFIRMED, id = 1), tx(week, 300, id = 2, description = "Rent"))
        val p = plan(items, listOf(dayShown("Confirm today's payments", "Expense ${m}10 TND"), headsUp(week, "Rent ${m}300 TND, 19 OCT")))
        assertEquals(listOf("DAY|$today|bell"), p.remove)
        assertTrue(p.updates.isEmpty())
    }
}
