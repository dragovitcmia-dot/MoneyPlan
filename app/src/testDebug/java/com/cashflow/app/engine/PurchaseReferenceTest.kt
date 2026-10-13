package com.cashflow.app.engine

import com.cashflow.app.debug.ReferenceScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan a purchase on the spreadsheet's reference scenario (start 10 OCT 2026, 180 TND, Nov 10 and Nov 12 close below zero). */
class PurchaseReferenceTest {
    private val today = ReferenceScenario.startDate

    private val baseline: PlanBaseline by lazy {
        val rule = ReferenceScenario.sundayRule
        val sundays = Recurrence.missingOccurrences(rule, emptySet(), today).map { rule.occurrenceFor(it) }
        val income = ReferenceScenario.dailyIncomeSource
        val daily = Recurrence.missingOccurrences(income, emptySet(), today).map { income.occurrenceFor(it) }
        PlanBaseline.of(BalanceEngine(ReferenceScenario.settings, ReferenceScenario.oneOffExpenses + sundays + daily, today))
    }

    @Test fun the_baseline_matches_the_sheet() {
        assertEquals(181, baseline.size)
        assertEquals(tnd(180), baseline.closings[0])
        assertEquals(tnd(-10), baseline.closings[(d(2026, 11, 10).toEpochDay() - today.toEpochDay()).toInt()])
    }

    @Test fun nothing_can_be_spent_before_the_second_negative_day_is_over() {
        val h = PurchasePlanner.headroom(baseline)
        val nov12 = (d(2026, 11, 12).toEpochDay() - today.toEpochDay()).toInt()
        assertTrue((0..nov12).all { h[it] == 0L })
        assertTrue(h[nov12 + 1] > 0)
    }

    @Test fun a_small_purchase_is_paid_all_on_the_first_day_after_the_dip_and_matches_brute_force() {
        for (price in listOf(tnd(1), tnd(10), tnd(40), tnd(100))) {
            val brute = baseline.closings.indices.firstOrNull { PurchasePlanner.isSafe(baseline, listOf(Outflow(it, price))) }
            val r = PurchasePlanner.plan(baseline, price)
            assertEquals(brute?.let(baseline::date), r.payAllDate)
            if (r.payAllDate != null) assertFalse(r.payAllDate!!.isBefore(d(2026, 11, 13)))
        }
    }

    @Test fun every_option_on_the_reference_scenario_is_safe_and_ends_no_earlier_than_pay_all() {
        for (price in listOf(tnd(30), tnd(120), tnd(300), tnd(600))) {
            val r = PurchasePlanner.plan(baseline, price)
            for (o in r.options) {
                assertEquals(price, o.amounts.sum())
                assertTrue(PurchasePlanner.isSafe(baseline, o.outflows))
                assertFalse(o.end.isBefore(r.payAllDate!!))
            }
        }
    }

    @Test fun a_price_beyond_everything_ever_earned_in_the_horizon_is_unaffordable_with_the_missing_amount() {
        val price = tnd(1_000_000)
        val r = PurchasePlanner.plan(baseline, price)
        assertTrue(r.options.isEmpty())
        assertEquals(price - baseline.closings.min(), r.extraNeeded)
    }
}
