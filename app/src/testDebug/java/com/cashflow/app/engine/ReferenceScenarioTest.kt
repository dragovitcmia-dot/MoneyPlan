package com.cashflow.app.engine

import com.cashflow.app.debug.ReferenceScenario
import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceScenarioTest {
    private val today = ReferenceScenario.startDate

    /** Rows exactly as the app would have them after materializing the rule at [today]. */
    private fun ledger(extra: List<Transaction> = emptyList()): List<Transaction> {
        val rule = ReferenceScenario.sundayRule
        val sundays = Recurrence.missingOccurrences(rule, emptySet(), today).map { rule.occurrenceFor(it) }
        val income = ReferenceScenario.dailyIncomeSource
        val daily = Recurrence.missingOccurrences(income, emptySet(), today).map { income.occurrenceFor(it) }
        return ReferenceScenario.oneOffExpenses + sundays + daily + extra
    }

    private fun engine(extra: List<Transaction> = emptyList()) =
        BalanceEngine(ReferenceScenario.settings, ledger(extra), today)

    private fun closing(e: BalanceEngine, date: java.time.LocalDate): Long =
        e.forecast(date, date).single().closing

    @Test fun closing_oct10_is_180() = assertEquals(tnd(180), closing(engine(), d(2026, 10, 10)))
    @Test fun closing_oct11_is_135() = assertEquals(tnd(135), closing(engine(), d(2026, 10, 11)))
    @Test fun closing_oct12_is_37() = assertEquals(tnd(37), closing(engine(), d(2026, 10, 12)))
    @Test fun closing_oct13_is_0() = assertEquals(tnd(0), closing(engine(), d(2026, 10, 13)))
    @Test fun closing_nov10_is_minus10() = assertEquals(tnd(-10), closing(engine(), d(2026, 11, 10)))
    @Test fun closing_nov12_is_minus13() = assertEquals(tnd(-13), closing(engine(), d(2026, 11, 12)))
    @Test fun closing_dec31_is_599() = assertEquals(tnd(599), closing(engine(), d(2026, 12, 31)))

    @Test fun negative_days_are_exactly_nov10_and_nov12() =
        assertEquals(listOf(d(2026, 11, 10), d(2026, 11, 12)), engine().negativeDays())

    @Test fun sunday_rule_materializes_oct11_through_dec27() {
        val sundays = ledger().filter { it.recurringRuleId == ReferenceScenario.sundayRule.id }.map { it.date }
        assertEquals(12, sundays.size) // Oct 11 .. Dec 27
        assertEquals(d(2026, 10, 11), sundays.first())
        assertEquals(d(2026, 12, 27), sundays.last())
    }

    @Test fun daily_source_starts_the_day_after_the_balance_start_date() {
        val income = ReferenceScenario.dailyIncomeSource
        assertEquals(d(2026, 10, 11), income.startDate)
        assertEquals(RecurrencePattern.Daily, income.pattern)
        assertEquals(TxKind.INCOME, income.kind)
        assertEquals(tnd(30), income.amount)
    }

    @Test fun start_day_state() {
        val e = engine()
        assertEquals(tnd(180), e.available())
        val next = e.nextExpense()!!
        assertEquals(d(2026, 10, 11), next.date)
        assertEquals(1, next.daysAway)
        assertEquals(tnd(75), next.amount)
    }

    /**
     * Documents the discrepancy in the written spec: counting the listed "Oct 11 75" in addition to
     * the Sunday rule double-counts Oct 11 (a Sunday) and the sheet's numbers no longer hold.
     */
    @Test fun literal_reading_double_counts_oct11() {
        val e = engine(extra = listOf(ReferenceScenario.oct11OneOff))
        assertEquals(tnd(60), closing(e, d(2026, 10, 11)))
        assertEquals(tnd(-38), closing(e, d(2026, 10, 12)))
        assertEquals(tnd(524), closing(e, d(2026, 12, 31)))
    }

    // ---- the new rule on the same data: nothing is added or deducted unless accepted -------------

    private fun engineOn(today: java.time.LocalDate, rows: List<Transaction> = ledger()) =
        BalanceEngine(ReferenceScenario.settings, rows, today)

    private fun fc(e: BalanceEngine, date: java.time.LocalDate) = e.forecast(date, date).single()

    @Test fun on_oct_14_with_nothing_accepted_available_is_180_and_the_forecast_opens_at_minus_90() {
        val e = engineOn(d(2026, 10, 14))
        assertEquals(tnd(180), e.available())
        // Overdue pending expenses (assumed paid today): Oct 11 Sunday 75 + Oct 12 128 + Oct 13 67 = 270.
        // Overdue pending income (30 on Oct 11, 12, 13) is NOT counted.
        assertEquals(tnd(180 - 270), fc(e, d(2026, 10, 14)).opening)
        // Today's own pending items: +30 daily income, -30 expense of Oct 14.
        assertEquals(tnd(-90), fc(e, d(2026, 10, 14)).closing)
        // Oct 15: +30 income, -20 expense.
        assertEquals(tnd(-80), fc(e, d(2026, 10, 15)).closing)
    }

    @Test fun history_before_today_ignores_everything_that_is_not_accepted() {
        val e = engineOn(d(2026, 10, 14))
        for (day in 10..13) assertEquals(tnd(180), fc(e, d(2026, 10, day)).closing)
    }

    @Test fun accepting_the_oct_12_expense_changes_available_by_exactly_128() {
        val rows = ledger()
        val target = rows.first { it.date == d(2026, 10, 12) && it.kind == TxKind.EXPENSE }
        val accepted = rows.map { if (it === target) it.copy(status = TxStatus.CONFIRMED) else it }
        assertEquals(tnd(180 - 128), engineOn(d(2026, 10, 14), accepted).available())
        // Accepted instead of overdue-and-pending: the forecast opening today stays the same.
        assertEquals(tnd(-90), fc(engineOn(d(2026, 10, 14), accepted), d(2026, 10, 14)).opening)
    }

    @Test fun rejecting_the_oct_12_expense_changes_nothing_but_the_forecast() {
        val rows = ledger()
        val target = rows.first { it.date == d(2026, 10, 12) && it.kind == TxKind.EXPENSE }
        val rejected = rows.map { if (it === target) it.copy(status = TxStatus.SKIPPED) else it }
        val e = engineOn(d(2026, 10, 14), rejected)
        assertEquals(tnd(180), e.available())
        assertEquals(tnd(-90 + 128), fc(e, d(2026, 10, 14)).opening)
    }
}
