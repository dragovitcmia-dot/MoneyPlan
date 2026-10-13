package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class NotificationsTest {
    private val zone = ZoneId.of("Africa/Tunis")
    private val today = d(2026, 10, 12)
    private val start = d(2026, 10, 1)

    private fun at(date: LocalDate, hhmm: String): Instant = date.atTime(LocalTime.parse(hhmm)).atZone(zone).toInstant()

    private fun run(
        time: String,
        items: List<Transaction> = emptyList(),
        rules: List<RecurringRule> = emptyList(),
        settings: NotificationSettings = NotificationSettings(),
        posted: Set<String> = emptySet(),
        balance: Long = 1000,
        date: LocalDate = today,
    ): List<NotificationDecision> {
        val engine = BalanceEngine(settings(start, balance), items, date)
        return Notifications.reconcile(at(date, time), zone, settings, items, rules, engine.forecast(date, date.plusDays(70)), posted)
    }

    private fun only(l: List<NotificationDecision>, type: NotificationType) = l.filter { it.type == type && it.action == DecisionAction.POST }

    /** Applies what the Android layer records after executing decisions. */
    private fun record(posted: Set<String>, l: List<NotificationDecision>): Set<String> {
        var out = posted
        for (x in l) {
            if (x.action != DecisionAction.POST) continue
            if (x.post) out = out + x.key
            if (x.bellKey != null) out = out + x.bellKey
        }
        return out
    }

    // ---- day-of summary ----------------------------------------------------------------------------

    @Test fun nothing_waiting_gives_nothing() = assertTrue(run("13:00").isEmpty())

    @Test fun before_the_reminder_time_nothing_is_posted() {
        assertTrue(run("11:59", listOf(tx(today, 10))).isEmpty())
    }

    @Test fun at_the_reminder_time_one_summary_groups_everything_due_today() {
        val r = run("12:00", listOf(tx(today, 100, id = 1), tx(today, 20, id = 2), tx(today, 30, TxKind.INCOME, id = 3)))
        val day = only(r, NotificationType.DAY_OF)
        assertEquals(1, day.size)
        assertEquals("Confirm today's payments", day[0].title)
        assertEquals("3 items, −120 TND, +30 TND", day[0].body)
        assertEquals(today, day[0].relatedDate)
        assertEquals(NotificationRoute.UPCOMING, day[0].route)
        assertTrue(day[0].post)
        assertEquals(-90_000L, day[0].amountMillimes)
    }

    @Test fun the_body_leaves_out_a_total_that_is_zero() {
        assertEquals("Expense −75 TND", only(run("12:00", listOf(tx(today, 75))), NotificationType.DAY_OF)[0].body)
        assertEquals("Income +30 TND", only(run("12:00", listOf(tx(today, 30, TxKind.INCOME))), NotificationType.DAY_OF)[0].body)
        assertEquals("2 items, −75 TND", only(run("12:00", listOf(tx(today, 50, id = 1), tx(today, 25, id = 2))), NotificationType.DAY_OF)[0].body)
        assertEquals("2 items, +80 TND", only(run("12:00", listOf(tx(today, 50, TxKind.INCOME, id = 1), tx(today, 30, TxKind.INCOME, id = 2))), NotificationType.DAY_OF)[0].body)
    }

    @Test fun overdue_items_are_part_of_the_summary() {
        val r = run("12:00", listOf(tx(today.minusDays(2), 40, id = 1), tx(today, 10, id = 2)))
        assertEquals("2 items, \u221250 TND", only(r, NotificationType.DAY_OF)[0].body)
    }

    @Test fun future_answered_and_rejected_items_are_not_counted() {
        val r = run(
            "12:00",
            listOf(
                tx(today.plusDays(1), 5, id = 1),
                tx(today, 6, status = TxStatus.CONFIRMED, id = 2),
                tx(today, 7, status = TxStatus.SKIPPED, id = 3),
                tx(today, 8, id = 4),
            ),
        )
        assertEquals("Expense \u22128 TND", only(r, NotificationType.DAY_OF)[0].body)
    }

    @Test fun pending_income_due_today_appears_in_the_summary_as_an_income_line() {
        val r = run("12:00", listOf(tx(today, 120, TxKind.INCOME)))
        assertEquals("Confirm today's income", only(r, NotificationType.DAY_OF)[0].title)
        assertEquals("Income +120 TND", only(r, NotificationType.DAY_OF)[0].body)
    }

    @Test fun the_nag_slots_are_every_two_hours_up_to_21() {
        assertEquals(listOf("12:00", "14:00", "16:00", "18:00", "20:00"), Notifications.daySlots(LocalTime.NOON, true).map { it.toString() })
        assertEquals(listOf("12:30", "14:30", "16:30", "18:30", "20:30"), Notifications.daySlots(LocalTime.of(12, 30), true).map { it.toString() })
        assertEquals(listOf("21:30"), Notifications.daySlots(LocalTime.of(21, 30), true).map { it.toString() })
        assertEquals(listOf("12:00"), Notifications.daySlots(LocalTime.NOON, false).map { it.toString() })
    }

    @Test fun a_reminder_time_inside_quiet_hours_counts_as_eight_in_the_morning() {
        assertEquals(listOf("08:00", "10:00", "12:00", "14:00", "16:00", "18:00", "20:00"), Notifications.daySlots(LocalTime.of(6, 0), true).map { it.toString() })
        assertEquals(listOf("08:00"), Notifications.daySlots(LocalTime.of(23, 0), true).map { it.toString() })
        assertEquals(LocalTime.of(8, 0), Notifications.effective(LocalTime.of(3, 15)))
        assertEquals(LocalTime.of(21, 59), Notifications.effective(LocalTime.of(21, 59)))
    }

    @Test fun quiet_hours_run_from_22_to_08() {
        assertTrue(Notifications.isQuiet(LocalTime.of(22, 0)))
        assertTrue(Notifications.isQuiet(LocalTime.of(23, 59)))
        assertTrue(Notifications.isQuiet(LocalTime.of(0, 0)))
        assertTrue(Notifications.isQuiet(LocalTime.of(7, 59)))
        assertFalse(Notifications.isQuiet(LocalTime.of(8, 0)))
        assertFalse(Notifications.isQuiet(LocalTime.of(21, 59)))
    }

    @Test fun nothing_is_posted_inside_quiet_hours_even_with_things_waiting() {
        val items = listOf(tx(today, 10))
        assertTrue(run("22:30", items).isEmpty())
        assertTrue(run("07:30", items).isEmpty())
    }

    @Test fun a_summary_scheduled_inside_quiet_hours_appears_when_they_end() {
        val items = listOf(tx(today, 10))
        val s = NotificationSettings(reminderTime = LocalTime.of(23, 0))
        assertTrue(run("07:59", items, settings = s).isEmpty())
        assertEquals(1, only(run("08:00", items, settings = s), NotificationType.DAY_OF).size)
    }

    @Test fun a_late_run_catches_up_with_the_latest_slot_only() {
        val r = only(run("19:10", listOf(tx(today, 10))), NotificationType.DAY_OF)
        assertEquals(1, r.size)
        assertEquals("DAY|$today|18:00", r[0].key)
    }

    @Test fun each_slot_is_posted_once_and_the_next_one_two_hours_later() {
        val items = listOf(tx(today, 10))
        var posted = emptySet<String>()
        val keys = mutableListOf<String>()
        for (time in listOf("12:00", "12:15", "13:59", "14:00", "14:30", "16:00", "17:59", "18:00", "20:00", "20:45", "21:30")) {
            val r = only(run(time, items, posted = posted), NotificationType.DAY_OF)
            keys += r.map { it.key }
            posted = record(posted, r)
        }
        assertEquals(listOf("12:00", "14:00", "16:00", "18:00", "20:00").map { "DAY|$today|$it" }, keys)
    }

    @Test fun the_first_message_of_the_day_has_the_confirm_title_and_the_later_ones_say_still_waiting() {
        val items = listOf(tx(today, 10, id = 1, description = "Rent"))
        val first = only(run("12:00", items), NotificationType.DAY_OF)[0]
        assertEquals("Confirm today's payments", first.title)
        assertEquals("Rent \u221210 TND", first.body)
        val nag = only(run("14:00", items, posted = record(emptySet(), listOf(first))), NotificationType.DAY_OF)[0]
        assertEquals("Still waiting", nag.title)
        assertEquals(first.body, nag.body)
    }

    @Test fun the_bell_gets_the_day_summary_once_not_once_per_nag() {
        val items = listOf(tx(today, 10))
        val first = only(run("12:00", items), NotificationType.DAY_OF)[0]
        assertEquals("DAY|$today|bell", first.bellKey)
        val second = only(run("14:00", items, posted = record(emptySet(), listOf(first))), NotificationType.DAY_OF)[0]
        assertNull(second.bellKey)
        assertTrue(second.post)
    }

    @Test fun without_keep_reminding_the_summary_is_posted_once_whenever_the_run_happens() {
        val s = NotificationSettings(keepReminding = false)
        val items = listOf(tx(today, 10))
        val r = only(run("16:00", items, settings = s), NotificationType.DAY_OF)
        assertEquals("DAY|$today|12:00", r[0].key)
        assertTrue(only(run("18:00", items, settings = s, posted = record(emptySet(), r)), NotificationType.DAY_OF).isEmpty())
    }

    @Test fun the_nag_stops_when_everything_is_answered_and_the_notification_is_cancelled() {
        val posted = setOf("DAY|$today|12:00", "DAY|$today|bell")
        val r = run("14:00", listOf(tx(today, 10, status = TxStatus.CONFIRMED)), posted = posted)
        assertEquals(1, r.size)
        assertEquals(DecisionAction.CANCEL, r[0].action)
        assertEquals(NotificationType.DAY_OF, r[0].type)
    }

    @Test fun nothing_is_cancelled_when_nothing_was_posted() {
        assertTrue(run("14:00", listOf(tx(today, 10, status = TxStatus.CONFIRMED))).isEmpty())
    }

    @Test fun a_postponed_item_leaves_todays_set_and_the_last_one_going_cancels() {
        val posted = setOf("DAY|$today|12:00")
        val moved = run("14:00", listOf(tx(today.plusDays(2), 10)), posted = posted)
        assertEquals(DecisionAction.CANCEL, moved.single { it.type == NotificationType.DAY_OF }.action)
    }

    @Test fun answering_part_of_the_set_updates_the_same_notification_with_fewer_items() {
        val posted = setOf("DAY|$today|12:00")
        val r = only(run("14:00", listOf(tx(today, 10, id = 1), tx(today, 20, status = TxStatus.CONFIRMED, id = 2)), posted = posted), NotificationType.DAY_OF)
        assertEquals("Still waiting", r[0].title)
        assertEquals("Expense −10 TND", r[0].body)
    }

    @Test fun income_that_repeats_is_not_asked_about_when_auto_accept_is_on() {
        val s = NotificationSettings(autoAcceptIncome = true)
        val recurring = tx(today, 30, TxKind.INCOME, source = TxSource.RECURRING, id = 1)
        assertTrue(only(run("12:00", listOf(recurring), settings = s), NotificationType.DAY_OF).isEmpty())
        val oneOff = tx(today, 50, TxKind.INCOME, id = 2)
        assertEquals("Income +50 TND", only(run("12:00", listOf(recurring, oneOff), settings = s), NotificationType.DAY_OF)[0].body)
    }

    @Test fun income_that_repeats_is_asked_about_when_auto_accept_is_off() {
        val recurring = tx(today, 30, TxKind.INCOME, source = TxSource.RECURRING, id = 1)
        assertEquals("Confirm today's income", only(run("12:00", listOf(recurring)), NotificationType.DAY_OF)[0].title)
    }

    @Test fun the_master_switch_turns_everything_off() {
        val items = listOf(tx(today, 10), tx(today.plusDays(1), 10), tx(today.plusDays(7), 10))
        assertTrue(run("12:00", items, settings = NotificationSettings(enabled = false), balance = 0).isEmpty())
    }

    @Test fun running_again_after_recording_the_keys_gives_nothing_new() {
        val items = listOf(tx(today, 10), tx(today.plusDays(1), 10), tx(today.plusDays(7), 10))
        val first = run("12:00", items, balance = 0)
        assertTrue(first.isNotEmpty())
        val posted = record(emptySet(), first)
        assertTrue(run("12:00", items, posted = posted, balance = 0).isEmpty())
        assertTrue(run("12:30", items, posted = posted, balance = 0).isEmpty())
    }

    @Test fun keys_are_stable_across_runs_in_the_same_slot() {
        val items = listOf(tx(today, 10))
        assertEquals(run("12:01", items).map { it.key }, run("13:44", items).map { it.key })
    }

    // ---- heads-ups ---------------------------------------------------------------------------------

    private val week = today.plusDays(7)
    private val tomorrow = today.plusDays(1)

    @Test fun a_week_ahead_a_one_time_expense_gets_a_heads_up() {
        val r = only(run("12:00", listOf(tx(week, 600, id = 1, description = "Rent"))), NotificationType.HEADS_UP_WEEK)
        assertEquals(1, r.size)
        assertEquals("Coming up", r[0].title)
        assertEquals("Rent −600 TND, 19 OCT", r[0].body)
        assertEquals(week, r[0].relatedDate)
        assertEquals(NotificationRoute.UPCOMING, r[0].route)
        assertTrue(r[0].post)
    }

    @Test fun a_week_ahead_only_one_time_and_monthly_or_rarer_repeaters_count() {
        fun item(id: Long) = Transaction(id = id, date = week, amount = 5000, kind = TxKind.EXPENSE, status = TxStatus.PENDING, source = TxSource.RECURRING, recurringRuleId = id)
        val patterns = listOf(
            RecurrencePattern.Daily to false,
            RecurrencePattern.Weekly(week.dayOfWeek) to false,
            RecurrencePattern.WeeklyDays(setOf(week.dayOfWeek, week.dayOfWeek.plus(2))) to false,
            RecurrencePattern.EveryNDays(14, week) to false,
            RecurrencePattern.EveryNDays(28, week) to true,
            RecurrencePattern.DayOfMonth(19) to true,
            RecurrencePattern.MonthFirst to true,
            RecurrencePattern.MonthLast to true,
            RecurrencePattern.Yearly(10, 19) to true,
        )
        patterns.forEachIndexed { i, (p, expected) ->
            val id = i + 1L
            val r = only(run("12:00", listOf(item(id)), rules = listOf(rule(p, start, id = id))), NotificationType.HEADS_UP_WEEK)
            assertEquals("$p", expected, r.isNotEmpty())
        }
    }

    @Test fun an_item_whose_series_is_gone_counts_as_one_time() {
        val orphan = Transaction(id = 1, date = week, amount = 5000, kind = TxKind.EXPENSE, status = TxStatus.PENDING, source = TxSource.RECURRING, recurringRuleId = 99)
        assertEquals(1, only(run("12:00", listOf(orphan)), NotificationType.HEADS_UP_WEEK).size)
    }

    @Test fun income_and_answered_expenses_get_no_heads_up() {
        val items = listOf(
            tx(week, 50, TxKind.INCOME, id = 1),
            tx(week, 50, status = TxStatus.CONFIRMED, id = 2),
            tx(tomorrow, 50, TxKind.INCOME, id = 3),
            tx(tomorrow, 50, status = TxStatus.SKIPPED, id = 4),
        )
        assertTrue(only(run("12:00", items), NotificationType.HEADS_UP_WEEK).isEmpty())
        assertTrue(only(run("12:00", items), NotificationType.HEADS_UP_DAY).isEmpty())
    }

    @Test fun the_day_before_everything_except_daily_repeaters_gets_a_heads_up() {
        fun item(id: Long) = Transaction(id = id, date = tomorrow, amount = 5000, kind = TxKind.EXPENSE, status = TxStatus.PENDING, source = TxSource.RECURRING, recurringRuleId = id)
        val daily = run("12:00", listOf(item(1)), rules = listOf(rule(RecurrencePattern.Daily, start, id = 1)))
        assertTrue(only(daily, NotificationType.HEADS_UP_DAY).isEmpty())
        val weekly = run("12:00", listOf(item(2)), rules = listOf(rule(RecurrencePattern.Weekly(tomorrow.dayOfWeek), start, id = 2)))
        assertEquals(1, only(weekly, NotificationType.HEADS_UP_DAY).size)
        val oneTime = only(run("12:00", listOf(tx(tomorrow, 30, id = 3))), NotificationType.HEADS_UP_DAY)
        assertEquals("Tomorrow", oneTime[0].title)
        assertEquals("Expense \u221230 TND, 13 OCT", oneTime[0].body)
        assertEquals(tomorrow, oneTime[0].relatedDate)
    }

    @Test fun payments_of_the_same_day_are_one_notification_with_the_biggest_first_and_a_more_count() {
        val items = (1..5).map { tx(week, it * 10L, id = it.toLong(), description = "P$it") }
        val r = only(run("12:00", items), NotificationType.HEADS_UP_WEEK)
        assertEquals(1, r.size)
        assertEquals("Coming up", r[0].title)
        assertEquals("5 payments next week, −150 TND", r[0].body)
    }

    @Test fun a_payment_without_a_name_is_called_expense() {
        assertTrue(only(run("12:00", listOf(tx(tomorrow, 5, description = " "))), NotificationType.HEADS_UP_DAY)[0].body.contains("Expense"))
    }

    @Test fun heads_ups_can_be_switched_off() {
        val r = run("12:00", listOf(tx(week, 5), tx(tomorrow, 5)), settings = NotificationSettings(headsUp = false))
        assertTrue(r.none { it.type == NotificationType.HEADS_UP_WEEK || it.type == NotificationType.HEADS_UP_DAY })
    }

    @Test fun a_heads_up_belongs_to_its_morning_and_is_never_posted_late() {
        val items = listOf(tx(week, 5))
        assertTrue(only(run("11:59", items), NotificationType.HEADS_UP_WEEK).isEmpty())
        assertEquals(1, only(run("12:00", items), NotificationType.HEADS_UP_WEEK).size)
        assertEquals(1, only(run("17:59", items), NotificationType.HEADS_UP_WEEK).size)
        assertTrue(only(run("18:00", items), NotificationType.HEADS_UP_WEEK).isEmpty())
        assertTrue(only(run("20:00", items), NotificationType.HEADS_UP_WEEK).isEmpty())
    }

    @Test fun no_heads_up_for_days_that_are_already_close_or_past() {
        val items = listOf(tx(today.plusDays(3), 5), tx(today.plusDays(6), 5), tx(today.plusDays(2), 5), tx(today.minusDays(1), 5))
        val r = run("12:00", items)
        assertTrue(r.none { it.type == NotificationType.HEADS_UP_WEEK || it.type == NotificationType.HEADS_UP_DAY })
    }

    @Test fun a_posted_heads_up_is_not_posted_again() {
        val items = listOf(tx(week, 5))
        val first = run("12:00", items)
        assertTrue(only(run("12:20", items, posted = record(emptySet(), first)), NotificationType.HEADS_UP_WEEK).isEmpty())
        assertEquals("HU7|$week", only(first, NotificationType.HEADS_UP_WEEK)[0].key)
    }

    @Test fun a_heads_up_scheduled_inside_quiet_hours_comes_at_eight() {
        val s = NotificationSettings(reminderTime = LocalTime.of(23, 30))
        val items = listOf(tx(tomorrow, 5))
        assertTrue(only(run("07:00", items, settings = s), NotificationType.HEADS_UP_DAY).isEmpty())
        assertEquals(1, only(run("08:10", items, settings = s), NotificationType.HEADS_UP_DAY).size)
        assertTrue(only(run("23:30", items, settings = s), NotificationType.HEADS_UP_DAY).isEmpty())
    }

    @Test fun a_postponed_item_moves_its_heads_up_to_its_new_date() {
        val r = only(run("12:00", listOf(tx(week.plusDays(1), 5)), balance = 100), NotificationType.HEADS_UP_WEEK)
        assertTrue(r.isEmpty())
        val moved = only(run("12:00", listOf(tx(week, 5))), NotificationType.HEADS_UP_WEEK)
        assertEquals(1, moved.size)
    }

    // ---- shortage ---------------------------------------------------------------------------------

    @Test fun no_negative_day_means_no_alert() {
        assertTrue(only(run("12:00", listOf(tx(today.plusDays(5), 100)), balance = 1000), NotificationType.SHORTAGE).isEmpty())
    }

    @Test fun a_shortage_alert_names_the_need_the_date_and_what_to_save_per_day() {
        // 100 TND available, a 140 TND expense in 4 days: short by 40, 10 per day.
        val r = only(run("12:00", listOf(tx(today.plusDays(4), 140)), balance = 100), NotificationType.SHORTAGE)
        assertEquals(1, r.size)
        assertEquals("Low balance ahead", r[0].title)
        assertEquals("Short by 40 TND before 16 OCT, save 10/day", r[0].body)
        assertEquals(NotificationRoute.CALENDAR, r[0].route)
        assertEquals(today.plusDays(4), r[0].relatedDate)
        assertEquals(40_000L, r[0].amountMillimes)
        assertTrue(r[0].post)
    }

    @Test fun the_per_day_amount_is_rounded_up_to_the_next_cent() {
        assertEquals(3340L, Notifications.perDayToSave(10_000, 3)) // 3.3333 -> 3.34
        assertEquals(3330L, Notifications.perDayToSave(9_990, 3)) // exactly 3.33
        assertEquals(10L, Notifications.perDayToSave(1, 1)) // anything short saves at least a cent
        assertEquals(25_000L, Notifications.perDayToSave(25_000, 1))
    }

    @Test fun the_per_day_text_uses_the_rounded_up_value() {
        val r = only(run("12:00", listOf(tx(today.plusDays(3), 110)), balance = 100), NotificationType.SHORTAGE)
        assertEquals("Short by 10 TND before 15 OCT, save 3.34/day", r[0].body)
    }

    @Test fun a_shortage_tomorrow_asks_for_the_whole_amount_in_one_day() {
        val r = only(run("12:00", listOf(tx(tomorrow, 125)), balance = 100), NotificationType.SHORTAGE)
        assertEquals("Short by 25 TND before 13 OCT, save 25/day", r[0].body)
    }

    @Test fun a_shortage_today_also_divides_by_one_day() {
        val r = only(run("12:00", listOf(tx(today, 130)), balance = 100), NotificationType.SHORTAGE)
        assertEquals("Short by 30 TND before 12 OCT, save 30/day", r[0].body)
    }

    @Test fun the_horizon_is_sixty_days_inclusive() {
        assertEquals(1, only(run("12:00", listOf(tx(today.plusDays(60), 150)), balance = 100), NotificationType.SHORTAGE).size)
        assertTrue(only(run("12:00", listOf(tx(today.plusDays(61), 150)), balance = 100), NotificationType.SHORTAGE).isEmpty())
    }

    @Test fun the_first_negative_day_is_the_one_reported() {
        val items = listOf(tx(today.plusDays(3), 130), tx(today.plusDays(10), 500))
        val r = only(run("12:00", items, balance = 100), NotificationType.SHORTAGE)
        assertEquals(today.plusDays(3), r[0].relatedDate)
        assertEquals(30_000L, r[0].amountMillimes)
    }

    @Test fun before_the_reminder_time_the_alert_goes_to_the_bell_only() {
        val r = only(run("09:00", listOf(tx(today.plusDays(4), 140)), balance = 100), NotificationType.SHORTAGE)
        assertEquals(1, r.size)
        assertFalse(r[0].post)
        assertNotNullKey(r[0].bellKey)
    }

    private fun assertNotNullKey(k: String?) = assertTrue(k != null)

    @Test fun at_the_reminder_time_the_alert_is_posted_and_the_bell_gets_its_entry_once() {
        val items = listOf(tx(today.plusDays(4), 140))
        val morning = only(run("09:00", items, balance = 100), NotificationType.SHORTAGE)
        var posted = record(emptySet(), morning)
        val noon = only(run("12:00", items, posted = posted, balance = 100), NotificationType.SHORTAGE)
        assertEquals(1, noon.size)
        assertTrue(noon[0].post)
        assertNull(noon[0].bellKey)
        posted = record(posted, noon)
        assertTrue(only(run("12:30", items, posted = posted, balance = 100), NotificationType.SHORTAGE).isEmpty())
    }

    @Test fun a_changed_need_is_a_new_alert_with_a_new_bell_entry() {
        val before = listOf(tx(today.plusDays(4), 140))
        val posted = record(emptySet(), run("12:00", before, balance = 100))
        val after = listOf(tx(today.plusDays(4), 150))
        val r = only(run("12:30", after, posted = posted, balance = 100), NotificationType.SHORTAGE)
        assertEquals(1, r.size)
        assertTrue(r[0].post)
        assertNotNullKey(r[0].bellKey)
        assertEquals("Short by 50 TND before 16 OCT, save 12.5/day", r[0].body)
    }

    @Test fun the_same_shortage_is_alerted_again_the_next_day_but_the_bell_keeps_one_entry() {
        val items = listOf(tx(today.plusDays(4), 140))
        val posted = record(emptySet(), run("12:00", items, balance = 100))
        val next = today.plusDays(1)
        // Next day the expense is 3 days away and the closing is the same -40.
        val r = only(run("12:00", items, posted = posted, balance = 100, date = next), NotificationType.SHORTAGE)
        assertEquals(1, r.size)
        assertTrue(r[0].post)
        assertNull(r[0].bellKey)
        assertEquals("Short by 40 TND before 16 OCT, save 13.34/day", r[0].body)
    }

    @Test fun shortage_alerts_can_be_switched_off() {
        val r = run("12:00", listOf(tx(today.plusDays(4), 140)), settings = NotificationSettings(shortageAlerts = false), balance = 100)
        assertTrue(only(r, NotificationType.SHORTAGE).isEmpty())
    }

    @Test fun in_quiet_hours_a_new_shortage_still_reaches_the_bell_without_a_status_bar_notification() {
        val r = only(run("23:00", listOf(tx(today.plusDays(4), 140)), balance = 100), NotificationType.SHORTAGE)
        assertEquals(1, r.size)
        assertFalse(r[0].post)
    }

    @Test fun days_before_today_in_the_forecast_never_trigger_an_alert() {
        val engine = BalanceEngine(settings(start, 0), listOf(tx(d(2026, 10, 5), 50, status = TxStatus.CONFIRMED), tx(d(2026, 10, 8), 200, TxKind.INCOME, TxStatus.CONFIRMED)), today)
        val r = Notifications.reconcile(at(today, "12:00"), zone, NotificationSettings(), emptyList(), emptyList(), engine.forecast(start, today.plusDays(5)), emptySet())
        assertTrue(r.none { it.type == NotificationType.SHORTAGE })
    }

    // ---- income accepted by itself -------------------------------------------------------------------------

    private val autoOn = NotificationSettings(autoAcceptIncome = true)
    private fun accepted(amount: Long = 30, id: Long = 1) = tx(today, amount, TxKind.INCOME, TxStatus.CONFIRMED, TxSource.RECURRING, id)

    @Test fun income_accepted_by_itself_goes_to_the_bell_and_never_to_the_status_bar() {
        val r = run("12:05", listOf(accepted()), settings = autoOn)
        val auto = r.single { it.type == NotificationType.AUTO_INCOME }
        assertFalse(auto.post)
        assertEquals("AUTO|$today", auto.bellKey)
        assertEquals("Income received", auto.title)
        assertEquals("+30 TND", auto.body)
        assertTrue(r.none { it.post })
    }

    @Test fun auto_income_entries_add_up_per_day_and_are_written_once() {
        val items = listOf(accepted(30, 1), accepted(20, 2))
        val first = run("12:05", items, settings = autoOn)
        assertEquals("+50 TND", first.single().body)
        assertTrue(run("13:00", items, settings = autoOn, posted = record(emptySet(), first)).isEmpty())
    }

    @Test fun no_auto_income_entry_before_the_accept_time_or_with_the_switch_off() {
        assertTrue(run("11:00", listOf(accepted()), settings = autoOn).isEmpty())
        assertTrue(run("13:00", listOf(accepted()), settings = NotificationSettings()).isEmpty())
    }

    @Test fun income_the_user_added_by_hand_is_not_an_auto_income_entry() {
        assertTrue(run("13:00", listOf(tx(today, 30, TxKind.INCOME, TxStatus.CONFIRMED)), settings = autoOn).isEmpty())
    }

    // ---- posted keys -------------------------------------------------------------------------------------

    @Test fun old_keys_are_dropped_and_recent_or_future_ones_kept() {
        val keys = setOf("DAY|2026-09-01|12:00", "DAY|2026-10-05|12:00", "HU7|2026-10-19", "garbage", "SHORT|BELL|2026-10-20|40000")
        assertEquals(setOf("DAY|2026-10-05|12:00", "HU7|2026-10-19", "SHORT|BELL|2026-10-20|40000"), PostedKeys.prune(keys, today))
    }

    @Test fun a_missed_run_never_produces_duplicates_when_it_finally_happens() {
        val items = listOf(tx(today, 10), tx(tomorrow, 20))
        val late = run("20:30", items) // the worker was blocked all day
        assertEquals(listOf(NotificationType.DAY_OF), late.map { it.type }) // the heads-up window is over; the summary catches up
        val again = run("20:45", items, posted = record(emptySet(), late))
        assertTrue(again.isEmpty())
    }

    @Test fun decisions_come_in_a_fixed_order_day_heads_up_shortage_auto() {
        val items = listOf(tx(today, 10, id = 1), tx(tomorrow, 5, id = 2))
        val r = run("12:00", items, settings = autoOn, balance = 0)
        val order = r.map { it.type }
        assertEquals(order.sortedBy { listOf(NotificationType.DAY_OF, NotificationType.HEADS_UP_WEEK, NotificationType.HEADS_UP_DAY, NotificationType.SHORTAGE, NotificationType.AUTO_INCOME).indexOf(it) }, order)
    }
}
