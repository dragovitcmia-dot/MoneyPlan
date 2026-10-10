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
        return ReferenceScenario.oneOffExpenses + sundays + extra
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
        val sundays = ledger().filter { it.source == TxSource.RECURRING }.map { it.date }
        assertEquals(12, sundays.size) // Oct 11 .. Dec 27
        assertEquals(d(2026, 10, 11), sundays.first())
        assertEquals(d(2026, 12, 27), sundays.last())
    }

    @Test fun start_day_state() {
        val e = engine()
        assertEquals(tnd(180), e.currentBalance())
        assertEquals(tnd(180), e.balanceAfterPending())
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
}
