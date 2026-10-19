package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.YearMonth

class UpcomingTest {
    private val start = d(2026, 10, 1)
    private val today = d(2026, 10, 14) // a Wednesday
    private val monday = DayOfWeek.MONDAY

    private fun group(date: java.time.LocalDate, first: DayOfWeek = monday) = Upcoming.groupOf(date, today, first)

    // ---- grouping boundaries --------------------------------------------------------------

    @Test fun today_and_overdue_are_waiting() {
        assertEquals(UpcomingGroupKey.Waiting, group(today))
        assertEquals(UpcomingGroupKey.Waiting, group(today.minusDays(1)))
        assertEquals(UpcomingGroupKey.Waiting, group(d(2026, 1, 1)))
    }

    @Test fun tomorrow_is_its_own_group() = assertEquals(UpcomingGroupKey.Tomorrow, group(d(2026, 10, 15)))

    @Test fun this_week_runs_up_to_the_last_day_of_the_week() {
        // Wednesday + Monday-first week: the week ends on Sunday Oct 18.
        assertEquals(UpcomingGroupKey.ThisWeek, group(d(2026, 10, 16)))
        assertEquals(UpcomingGroupKey.ThisWeek, group(d(2026, 10, 18)))
        assertEquals(UpcomingGroupKey.Month(YearMonth.of(2026, 10)), group(d(2026, 10, 19)))
    }

    @Test fun week_edge_follows_the_locale_first_day_of_week() {
        // Sunday-first: the week ends on Saturday Oct 17.
        assertEquals(UpcomingGroupKey.ThisWeek, group(d(2026, 10, 17), DayOfWeek.SUNDAY))
        assertEquals(UpcomingGroupKey.Month(YearMonth.of(2026, 10)), group(d(2026, 10, 18), DayOfWeek.SUNDAY))
    }

    @Test fun when_tomorrow_starts_a_new_week_this_week_is_empty() {
        val sunday = d(2026, 10, 18)
        assertEquals(UpcomingGroupKey.Tomorrow, Upcoming.groupOf(d(2026, 10, 19), sunday, monday))
        assertEquals(UpcomingGroupKey.Month(YearMonth.of(2026, 10)), Upcoming.groupOf(d(2026, 10, 20), sunday, monday))
    }

    @Test fun month_edge_and_year_edge() {
        assertEquals(UpcomingGroupKey.Month(YearMonth.of(2026, 10)), group(d(2026, 10, 31)))
        assertEquals(UpcomingGroupKey.Month(YearMonth.of(2026, 11)), group(d(2026, 11, 1)))
        assertEquals(UpcomingGroupKey.Month(YearMonth.of(2027, 1)), group(d(2027, 1, 1)))
    }

    @Test fun a_week_that_crosses_the_month_stays_this_week() {
        val t = d(2026, 10, 28) // Wednesday; the week ends on Sunday Nov 1
        assertEquals(UpcomingGroupKey.ThisWeek, Upcoming.groupOf(d(2026, 11, 1), t, monday))
        assertEquals(UpcomingGroupKey.Month(YearMonth.of(2026, 11)), Upcoming.groupOf(d(2026, 11, 2), t, monday))
    }

    // ---- the list ---------------------------------------------------------------------------

    private fun build(
        rows: List<Transaction>,
        rules: List<RecurringRule> = emptyList(),
        type: UpcomingType = UpcomingType.ALL,
        balance: Long = 1000,
    ) = Upcoming.build(BalanceEngine(settings(start, balance), rows, today), rules, type, monday)

    @Test fun groups_are_ordered_waiting_tomorrow_week_then_months() {
        val rows = listOf(
            tx(d(2026, 12, 2), 5, id = 1), tx(d(2026, 11, 2), 5, id = 2), tx(d(2026, 10, 22), 5, id = 3),
            tx(d(2026, 10, 16), 5, id = 4), tx(d(2026, 10, 15), 5, id = 5), tx(d(2026, 10, 12), 5, id = 6),
        )
        val keys = build(rows).groups.map { it.key }
        assertEquals(
            listOf(
                UpcomingGroupKey.Waiting, UpcomingGroupKey.Tomorrow, UpcomingGroupKey.ThisWeek,
                UpcomingGroupKey.Month(YearMonth.of(2026, 10)), UpcomingGroupKey.Month(YearMonth.of(2026, 11)),
                UpcomingGroupKey.Month(YearMonth.of(2026, 12)),
            ),
            keys,
        )
    }

    @Test fun only_pending_rows_are_listed_and_rows_are_sorted_by_date() {
        val rows = listOf(
            tx(d(2026, 10, 20), 5, id = 1), tx(d(2026, 10, 19), 5, id = 2),
            tx(d(2026, 10, 19), 5, status = TxStatus.CONFIRMED, id = 3), tx(d(2026, 10, 19), 5, status = TxStatus.SKIPPED, id = 4),
        )
        val octGroup = build(rows).groups.single()
        assertEquals(listOf(2L, 1L), octGroup.rows.map { it.tx.id })
    }

    @Test fun horizon_is_120_days() {
        val rows = listOf(tx(today.plusDays(120), 5, id = 1), tx(today.plusDays(121), 5, id = 2))
        assertEquals(listOf(1L), build(rows).groups.flatMap { it.rows }.map { it.tx.id })
    }

    @Test fun overdue_items_are_in_waiting_with_the_waiting_flag() {
        val rows = listOf(tx(d(2026, 10, 10), 5, id = 1), tx(today, 5, id = 2), tx(today.plusDays(1), 5, id = 3))
        val m = build(rows)
        val waiting = m.groups.first { it.key == UpcomingGroupKey.Waiting }
        assertEquals(listOf(1L, 2L), waiting.rows.map { it.tx.id })
        assertTrue(waiting.rows.all { it.waiting })
        assertFalse(m.groups.first { it.key == UpcomingGroupKey.Tomorrow }.rows.single().waiting)
        assertEquals(-4, waiting.rows.first().daysAway)
        assertEquals(1, m.groups.first { it.key == UpcomingGroupKey.Tomorrow }.rows.single().daysAway)
    }

    @Test fun filters_split_expenses_and_income() {
        val rows = listOf(tx(d(2026, 10, 20), 5, id = 1), tx(d(2026, 10, 20), 7, TxKind.INCOME, id = 2))
        assertEquals(listOf(1L, 2L), build(rows).groups.flatMap { it.rows }.map { it.tx.id })
        assertEquals(listOf(1L), build(rows, type = UpcomingType.EXPENSES).groups.flatMap { it.rows }.map { it.tx.id })
        assertEquals(listOf(2L), build(rows, type = UpcomingType.INCOME).groups.flatMap { it.rows }.map { it.tx.id })
    }

    @Test fun a_row_whose_day_closes_negative_carries_the_shortfall() {
        val rows = listOf(tx(d(2026, 10, 20), 150, id = 1), tx(d(2026, 10, 25), 10, id = 2))
        val all = build(rows, balance = 100).groups.flatMap { it.rows }
        assertEquals(tnd(50), all.first { it.tx.id == 1L }.shortBy)
        assertEquals(tnd(60), all.first { it.tx.id == 2L }.shortBy)
        val fine = build(rows.take(1), balance = 500).groups.flatMap { it.rows }.single()
        assertNull(fine.shortBy)
    }

    @Test fun repeating_rows_are_marked() {
        val r = rule(RecurrencePattern.Weekly(DayOfWeek.FRIDAY), today, id = 4)
        val rows = RuleMaintenance.materialize(r, emptyList(), today).filter { it.date <= d(2026, 10, 30) }
        val list = build(rows, listOf(r)).groups.flatMap { it.rows }
        assertTrue(list.isNotEmpty() && list.all { it.repeating })
        assertFalse(build(listOf(tx(d(2026, 10, 20), 5, id = 1))).groups.single().rows.single().repeating)
    }

    // ---- no special treatment for daily series (v0.10.1) ---------------------------------------

    private val daily = incomeSource(RecurrencePattern.Daily, d(2026, 10, 2), amount = 30, id = 2)

    @Test fun a_daily_series_is_a_normal_repeating_item_with_only_its_next_occurrence_in_the_future() {
        val rows = RuleMaintenance.materialize(daily, emptyList(), today).filter { it.date <= d(2026, 12, 31) }
        val m = build(rows, listOf(daily))
        val listed = m.groups.flatMap { it.rows }
        assertTrue(listed.all { it.repeating })
        val future = listed.filter { !it.waiting }
        assertEquals(1, future.size)
        assertEquals(today.plusDays(1), future.single().tx.date) // exactly the next occurrence (tomorrow)
        assertEquals(setOf(UpcomingGroupKey.Waiting, UpcomingGroupKey.Tomorrow), m.groups.map { it.key }.toSet())
        // the waiting ones (today and earlier) are all kept: they wait for an answer
        assertEquals(rows.count { it.date <= today }, listed.count { it.waiting })
    }

    @Test fun the_collapse_rule_applies_to_everything_that_repeats_more_often_than_weekly() {
        assertTrue(RecurrencePattern.Daily.repeatsMoreOftenThanWeekly())
        assertTrue(RecurrencePattern.EveryNDays(2, today).repeatsMoreOftenThanWeekly())
        assertTrue(RecurrencePattern.EveryNDays(6, today).repeatsMoreOftenThanWeekly())
        assertTrue(RecurrencePattern.WeeklyDays(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)).repeatsMoreOftenThanWeekly())
        assertFalse(RecurrencePattern.EveryNDays(7, today).repeatsMoreOftenThanWeekly())
        assertFalse(RecurrencePattern.EveryNDays(14, today).repeatsMoreOftenThanWeekly())
        assertFalse(RecurrencePattern.Weekly(DayOfWeek.SUNDAY).repeatsMoreOftenThanWeekly())
        assertFalse(RecurrencePattern.MonthFirst.repeatsMoreOftenThanWeekly())
        assertFalse(RecurrencePattern.DayOfMonth(5).repeatsMoreOftenThanWeekly())
        assertFalse(RecurrencePattern.Yearly(3, 1).repeatsMoreOftenThanWeekly())
    }

    @Test fun collapseFrequent_keeps_waiting_rows_the_next_future_row_and_every_row_of_slower_series() {
        val every3 = rule(RecurrencePattern.EveryNDays(3, today), today, id = 5)
        val weekly = rule(RecurrencePattern.Weekly(DayOfWeek.SUNDAY), today, id = 6)
        val rows = listOf(
            every3.occurrenceFor(today.minusDays(3)).copy(id = 1), every3.occurrenceFor(today).copy(id = 2),
            every3.occurrenceFor(today.plusDays(3)).copy(id = 3), every3.occurrenceFor(today.plusDays(6)).copy(id = 4),
            weekly.occurrenceFor(today.plusDays(1)).copy(id = 5), weekly.occurrenceFor(today.plusDays(8)).copy(id = 6),
            tx(today.plusDays(2), 5, id = 7), tx(today.plusDays(4), 5, id = 8),
        )
        val kept = Upcoming.collapseFrequent(rows, listOf(every3, weekly), today).map { it.id }
        assertEquals(listOf(1L, 2L, 3L, 5L, 6L, 7L, 8L), kept)
    }

    @Test fun a_weekly_expense_and_a_daily_income_sit_in_one_normal_list() {
        val sunday = rule(RecurrencePattern.Weekly(DayOfWeek.SUNDAY), today, id = 4)
        val rows = RuleMaintenance.materialize(daily, emptyList(), today).filter { it.date <= d(2026, 11, 30) } +
            RuleMaintenance.materialize(sunday, emptyList(), today).filter { it.date <= d(2026, 11, 30) }
        val m = build(rows, listOf(daily, sunday))
        val future = m.groups.flatMap { it.rows }.filter { !it.waiting }
        assertEquals(1, future.count { it.tx.recurringRuleId == 2L }) // the daily income: next occurrence only
        assertTrue(future.count { it.tx.recurringRuleId == 4L } > 3) // the weekly expense: every Sunday stays listed
        assertTrue(future.all { it.repeating })
    }

    @Test fun empty_means_nothing_to_show() {
        assertTrue(build(emptyList()).isEmpty)
        assertTrue(build(emptyList(), listOf(daily)).isEmpty) // a series with no waiting rows adds nothing: no special card
    }

    @Test fun the_horizon_constant_is_120() = assertEquals(120L, Upcoming.HORIZON_DAYS)
}
