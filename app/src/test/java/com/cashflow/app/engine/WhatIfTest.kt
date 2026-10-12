package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class WhatIfTest {
    private val start = d(2026, 10, 10)
    private val today = d(2026, 10, 10)

    private fun engine(txs: List<Transaction> = emptyList(), balance: Long = 100) =
        BalanceEngine(settings(start, balance), txs, today)

    private fun planned(date: java.time.LocalDate, amount: Long, id: Long = 0, kind: TxKind = TxKind.EXPENSE) =
        tx(date, amount, kind, id = id)

    @Test fun a_small_expense_is_safe() {
        val r = engine().whatIf(listOf(planned(d(2026, 10, 15), 40)))
        assertFalse(r.wouldCauseNegative)
        assertTrue(r.newNegativeDays.isEmpty())
        assertNull(r.firstNegativeDate)
        assertNull(r.firstNegativePlannedExpense)
        assertEquals(tnd(60), r.lowestBalance)
    }

    @Test fun lowest_balance_is_reported_with_its_earliest_date() {
        val r = engine().whatIf(listOf(planned(d(2026, 10, 15), 40)))
        // 100 until Oct 14, then 60 from Oct 15 onwards; the earliest day at 60 wins.
        assertEquals(tnd(60), r.lowestBalance)
        assertEquals(d(2026, 10, 15), r.lowestBalanceDate)
    }

    @Test fun an_expense_over_the_balance_makes_that_day_and_following_days_newly_negative() {
        val r = engine().whatIf(listOf(planned(d(2026, 10, 15), 130)))
        assertTrue(r.wouldCauseNegative)
        assertEquals(d(2026, 10, 15), r.firstNegativeDate)
        assertEquals(tnd(-30), r.lowestBalance)
        assertEquals(d(2026, 10, 15), r.lowestBalanceDate)
        // With no income the balance stays at -30 for the rest of the horizon (Oct 15 .. Apr 7).
        assertEquals(d(2026, 10, 15), r.newNegativeDays.first())
        assertEquals(today.plusDays(180), r.newNegativeDays.last())
    }

    @Test fun it_names_the_first_planned_expense_that_closes_negative() {
        val existing = planned(d(2026, 10, 20), 10, id = 7).copy(description = "later bill")
        val r = engine(listOf(existing)).whatIf(listOf(planned(d(2026, 10, 15), 130).copy(description = "new big one")))
        assertEquals("new big one", r.firstNegativePlannedExpense!!.description)
        // An existing expense can be the culprit too.
        val r2 = engine(listOf(existing)).whatIf(listOf(planned(d(2026, 10, 12), 95, kind = TxKind.EXPENSE, id = 0).copy(date = d(2026, 10, 12))))
        assertEquals("later bill", r2.firstNegativePlannedExpense!!.description)
    }

    @Test fun already_negative_days_are_not_new_unless_they_get_worse() {
        val base = listOf(planned(d(2026, 10, 15), 130, id = 1)) // already -30 from Oct 15
        val unchanged = engine(base).whatIf(listOf(planned(d(2026, 10, 15), 5, kind = TxKind.INCOME)))
        assertFalse(unchanged.wouldCauseNegative) // better, not worse
        val worse = engine(base).whatIf(listOf(planned(d(2026, 10, 20), 10)))
        assertTrue(worse.wouldCauseNegative)
        assertEquals(d(2026, 10, 20), worse.newNegativeDays.first()) // Oct 15..19 unchanged, Oct 20+ worse
        assertEquals(d(2026, 10, 15), worse.firstNegativeDate)
    }

    @Test fun nothing_new_when_the_baseline_was_already_negative_and_nothing_changes() {
        val base = listOf(planned(d(2026, 10, 15), 130, id = 1))
        val r = engine(base).whatIf()
        assertFalse(r.wouldCauseNegative)
        assertEquals(d(2026, 10, 15), r.firstNegativeDate)
    }

    @Test fun income_never_causes_negatives() {
        val r = engine().whatIf(listOf(planned(d(2026, 10, 15), 500, kind = TxKind.INCOME)))
        assertFalse(r.wouldCauseNegative)
    }

    @Test fun a_recurring_expense_rule_is_expanded_over_the_horizon() {
        // 30 every Monday from Oct 12 against 100 with no income: 100, 70, 40, 10, -20 on Nov 2.
        val rule = rule(RecurrencePattern.Weekly(DayOfWeek.MONDAY), d(2026, 10, 12), amount = 30)
        val r = engine().whatIf(newRule = rule)
        assertTrue(r.wouldCauseNegative)
        assertEquals(d(2026, 11, 2), r.firstNegativeDate)
        assertEquals(d(2026, 11, 2), r.newNegativeDays.first())
        assertEquals("rule", r.firstNegativePlannedExpense!!.description)
        assertEquals(d(2026, 11, 2), r.firstNegativePlannedExpense!!.date)
    }

    @Test fun a_recurring_expense_covered_by_income_is_safe() {
        val income = RuleMaintenance.materialize(incomeSource(start = d(2026, 10, 11), amount = 30), emptyList(), today)
        val rule = rule(RecurrencePattern.Weekly(DayOfWeek.MONDAY), d(2026, 10, 12), amount = 30)
        assertFalse(engine(income).whatIf(newRule = rule).wouldCauseNegative)
    }

    @Test fun the_horizon_limits_what_is_evaluated() {
        val farAway = planned(today.plusDays(100), 500)
        assertTrue(engine().whatIf(listOf(farAway), horizonDays = 180).wouldCauseNegative)
        assertFalse(engine().whatIf(listOf(farAway), horizonDays = 30).wouldCauseNegative)
    }

    @Test fun a_switched_off_rule_is_inert() {
        val rule = incomeSource(start = d(2026, 10, 11), amount = 1000, enabled = false)
        assertEquals(0L, engine(balance = 0).whatIf(newRule = rule).lowestBalance)
        val on = incomeSource(start = d(2026, 10, 11), amount = 1000)
        assertTrue(engine(balance = 0).whatIf(newRule = on).lowestBalance >= 0)
    }

    @Test fun the_engine_itself_is_not_modified() {
        val e = engine(listOf(planned(d(2026, 10, 12), 10)))
        val before = e.forecast(today, today.plusDays(30))
        e.whatIf(listOf(planned(d(2026, 10, 13), 500)))
        assertEquals(before, e.forecast(today, today.plusDays(30)))
    }

    @Test fun a_now_expense_is_checked_like_any_other() {
        // "Now" expenses are CONFIRMED today: they must trigger the warning too when they empty the balance.
        val big = EntryPlanner.transactionFor(NewEntry.Now(TxKind.EXPENSE, tnd(130), "Shopping"), today)!!
        val r = engine().whatIf(listOf(big))
        assertTrue(r.wouldCauseNegative)
        assertEquals(today, r.firstNegativeDate)
        assertEquals(tnd(-30), r.lowestBalance)
        val small = EntryPlanner.transactionFor(NewEntry.Now(TxKind.EXPENSE, tnd(30), "Snack"), today)!!
        assertFalse(engine().whatIf(listOf(small)).wouldCauseNegative)
    }

    @Test fun a_now_expense_that_only_worsens_an_already_negative_future_is_flagged() {
        val future = planned(d(2026, 10, 20), 130, id = 1) // already negative from Oct 20
        val now = EntryPlanner.transactionFor(NewEntry.Now(TxKind.EXPENSE, tnd(10), "x"), today)!!
        val r = engine(listOf(future)).whatIf(listOf(now))
        assertTrue(r.wouldCauseNegative)
    }
}
