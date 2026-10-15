package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random

class PurchasePlannerTest {
    private val today = d(2026, 10, 12)

    /** A baseline from closings in TND; index 0 is today. */
    private fun base(vararg tndValues: Int) = PlanBaseline(today, tndValues.map { tnd(it.toLong()) })
    private fun flat(value: Int, days: Int) = PlanBaseline(today, List(days) { tnd(value.toLong()) })
    private fun out(day: Int, amount: Int) = Outflow(day, tnd(amount.toLong()))

    // ---- simulate -------------------------------------------------------------------------------

    @Test fun an_outflow_lowers_its_day_and_every_later_day() {
        assertEquals(listOf(100, 100, 70, 70).map { tnd(it.toLong()) }, PurchasePlanner.simulate(base(100, 100, 100, 100), listOf(out(2, 30))))
    }

    @Test fun outflows_add_up_and_out_of_range_ones_are_ignored() {
        val sim = PurchasePlanner.simulate(base(100, 100, 100), listOf(out(0, 10), out(1, 20), out(9, 50), out(-1, 5)))
        assertEquals(listOf(90, 70, 70).map { tnd(it.toLong()) }, sim)
    }

    @Test fun no_outflow_leaves_the_baseline_alone() {
        val b = base(5, -3, 8)
        assertEquals(b.closings, PurchasePlanner.simulate(b, emptyList()))
    }

    // ---- headroom and safety -----------------------------------------------------------------------

    @Test fun headroom_is_the_lowest_non_negative_closing_from_that_day_on() {
        assertEquals(listOf(50, 50, 50, 50).map { tnd(it.toLong()) }, PurchasePlanner.headroom(base(100, 80, 120, 50)).toList())
        assertEquals(listOf(10, 20, 30).map { tnd(it.toLong()) }, PurchasePlanner.headroom(base(10, 20, 30)).toList())
    }

    @Test fun a_negative_day_gives_no_headroom_on_or_before_it() {
        assertEquals(listOf(0, 0, 50).map { tnd(it.toLong()) }, PurchasePlanner.headroom(base(100, -20, 50)).toList())
    }

    @Test fun spending_the_headroom_is_safe_and_one_millime_more_is_not() {
        val b = base(100, 40, 120)
        val h = PurchasePlanner.headroom(b)[0]
        assertEquals(tnd(40), h)
        assertTrue(PurchasePlanner.isSafe(b, listOf(Outflow(0, h))))
        assertFalse(PurchasePlanner.isSafe(b, listOf(Outflow(0, h + 1))))
    }

    @Test fun a_day_that_is_negative_anyway_may_not_get_worse_but_later_days_can_be_spent_on() {
        val b = base(100, -20, 50)
        assertFalse(PurchasePlanner.isSafe(b, listOf(out(0, 10)))) // would make day 1 -30
        assertTrue(PurchasePlanner.isSafe(b, listOf(out(2, 10))))
        assertFalse(PurchasePlanner.isSafe(b, listOf(out(2, 51))))
    }

    @Test fun headroom_after_earlier_parts_counts_them() {
        val b = flat(100, 30)
        val sim = PurchasePlanner.simulate(b, listOf(out(0, 30)))
        assertEquals(tnd(70), PurchasePlanner.headroom(b, sim)[5])
    }

    // ---- pay all --------------------------------------------------------------------------------------

    @Test fun a_price_within_todays_headroom_is_paid_today() {
        assertEquals(0, PurchasePlanner.payAllIndex(flat(100, 20), tnd(100)))
        assertEquals(0, PurchasePlanner.payAllIndex(flat(100, 20), tnd(1)))
    }

    @Test fun pay_all_waits_for_the_first_day_after_a_dip() {
        assertEquals(3, PurchasePlanner.payAllIndex(base(100, 10, 10, 200, 200), tnd(50)))
    }

    @Test fun pay_all_is_never_when_the_price_exceeds_every_headroom() {
        assertNull(PurchasePlanner.payAllIndex(base(50, 40, 60), tnd(100)))
    }

    @Test fun the_last_day_of_the_horizon_counts_and_one_more_millime_does_not() {
        val b = base(0, 0, 0, 100)
        assertEquals(3, PurchasePlanner.payAllIndex(b, tnd(100)))
        assertNull(PurchasePlanner.payAllIndex(b, tnd(100) + 1))
    }

    @Test fun pay_all_matches_a_brute_force_search_on_random_baselines() {
        val rnd = Random(7)
        repeat(200) {
            val closings = List(40) { (rnd.nextInt(400) - 80) * 1000L }
            val b = PlanBaseline(today, closings)
            val price = (rnd.nextInt(150) + 1) * 1000L
            val brute = closings.indices.firstOrNull { PurchasePlanner.isSafe(b, listOf(Outflow(it, price))) }
            assertEquals(brute, PurchasePlanner.payAllIndex(b, price))
        }
    }

    // ---- installments ------------------------------------------------------------------------------------

    @Test fun parts_are_rounded_to_a_cent_and_the_last_one_absorbs_the_rest() {
        assertEquals(listOf(33_330L, 33_330L, 33_340L), PurchasePlanner.splitParts(100_000, 3))
        assertEquals(listOf(50_000L, 50_000L), PurchasePlanner.splitParts(100_000, 2))
    }

    @Test fun parts_always_add_up_to_the_price() {
        for (price in listOf(10L, 70L, 1_000L, 12_340L, 99_990L, 123_450L, 5_000_000L)) {
            for (k in 2..6) {
                val parts = PurchasePlanner.splitParts(price, k) ?: continue
                assertEquals("$price/$k", price, parts.sum())
                assertEquals(k, parts.size)
                assertTrue(parts.all { it >= 10 && it % 10 == 0L })
            }
        }
    }

    @Test fun a_price_too_small_to_split_has_no_such_option() {
        assertNull(PurchasePlanner.splitParts(50, 6))
        assertNull(PurchasePlanner.splitParts(0, 2))
    }

    @Test fun parts_are_placed_greedily_seven_days_apart() {
        val placed = PurchasePlanner.installments(flat(100, 40), tnd(90), 3)!!
        assertEquals(listOf(0, 7, 14), placed.map { it.dayIndex })
        assertEquals(tnd(90), placed.sumOf { it.amount })
    }

    @Test fun a_part_waits_for_the_headroom_it_needs() {
        // 100 for ten days, then 30 for ten days, then 500: 2 x 30 starts today and the second part waits for the 500.
        val b = PlanBaseline(today, List(10) { tnd(100) } + List(10) { tnd(30) } + List(20) { tnd(500) })
        val placed = PurchasePlanner.installments(b, tnd(60), 2)!!
        assertEquals(listOf(0, 20), placed.map { it.dayIndex })
        assertTrue(PurchasePlanner.isSafe(b, placed))
    }

    @Test fun an_option_that_does_not_fit_in_the_horizon_is_dropped() {
        assertNull(PurchasePlanner.installments(flat(100, 10), tnd(90), 3)) // the third part would be on day 14
        assertNotNull(PurchasePlanner.installments(flat(100, 15), tnd(90), 3))
    }

    @Test fun the_horizon_edge_for_the_last_part_is_inclusive() {
        assertNotNull(PurchasePlanner.installments(flat(100, 15), tnd(90), 3)) // days 0, 7, 14: index 14 is the last of 15
        assertNull(PurchasePlanner.installments(flat(100, 14), tnd(90), 3))
    }

    // ---- the whole plan ----------------------------------------------------------------------------------

    @Test fun with_plenty_of_money_every_option_starts_today_and_pay_all_wins_the_tie() {
        val r = PurchasePlanner.plan(flat(100, 60), tnd(90))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), r.options.map { it.parts })
        assertEquals(1, r.defaultParts)
        assertEquals(today, r.payAllDate)
        assertNull(r.extraNeeded)
    }

    @Test fun the_default_is_the_earliest_start_and_ties_prefer_fewer_parts() {
        val b = PlanBaseline(today, List(10) { tnd(100) } + List(10) { tnd(30) } + List(30) { tnd(500) })
        val r = PurchasePlanner.plan(b, tnd(60))
        assertEquals(today.plusDays(20), r.payAllDate)
        assertEquals(2, r.defaultParts) // starts today; 3, 4 ... start today as well but have more parts
        assertEquals(today, r.option(2)!!.start)
        assertEquals(today.plusDays(20), r.option(1)!!.start)
    }

    @Test fun the_last_installment_is_never_before_the_pay_all_date_and_every_plan_is_safe() {
        val rnd = Random(11)
        repeat(300) {
            val closings = List(90) { (rnd.nextInt(500) - 100) * 1000L }
            val b = PlanBaseline(today, closings)
            val price = (rnd.nextInt(300) + 1) * 1000L
            val r = PurchasePlanner.plan(b, price)
            for (o in r.options) {
                assertEquals(price, o.amounts.sum())
                assertTrue(PurchasePlanner.isSafe(b, o.outflows))
                assertTrue(o.outflows.zipWithNext().all { (a, c) -> c.dayIndex - a.dayIndex >= 7 })
                assertFalse(o.end.isBefore(r.payAllDate!!))
            }
            // installments exist only when paying everything at once does
            if (r.payAllDate == null) assertTrue(r.options.isEmpty())
        }
    }

    @Test fun every_option_lists_its_dates_in_order() {
        val r = PurchasePlanner.plan(flat(100, 60), tnd(90))
        for (o in r.options) assertEquals(o.dates.sorted(), o.dates)
        assertEquals(listOf(today, today.plusDays(7), today.plusDays(14)), r.option(3)!!.dates)
    }

    @Test fun an_unaffordable_price_has_no_options_and_says_how_much_more_is_needed() {
        val r = PurchasePlanner.plan(base(50, 40, 60), tnd(100))
        assertTrue(r.options.isEmpty())
        assertFalse(r.affordable)
        assertNull(r.defaultParts)
        assertEquals(tnd(60), r.extraNeeded) // the balance sinks to 40 and 100 are wanted
    }

    @Test fun the_extra_needed_includes_a_shortage_that_exists_anyway() {
        val r = PurchasePlanner.plan(base(50, -30, 60), tnd(100))
        assertEquals(tnd(130), r.extraNeeded)
    }

    @Test fun the_extra_needed_is_exactly_enough_for_paying_all_today() {
        for (b in listOf(base(50, 40, 60), base(50, -30, 60), base(10, 5, 5, 8))) {
            val price = tnd(100)
            val extra = PurchasePlanner.plan(b, price).extraNeeded!!
            val richer = PlanBaseline(today, b.closings.map { it + extra })
            assertEquals(0, PurchasePlanner.payAllIndex(richer, price))
            val almost = PlanBaseline(today, b.closings.map { it + extra - 1 })
            assertTrue(PurchasePlanner.payAllIndex(almost, price) != 0) // one millime less is not enough for today
        }
    }

    @Test fun a_price_of_zero_or_less_plans_nothing() {
        val r = PurchasePlanner.plan(flat(100, 20), 0)
        assertTrue(r.options.isEmpty())
        assertNull(r.extraNeeded)
    }

    @Test fun an_option_lookup_falls_back_to_the_default() {
        val r = PurchasePlanner.plan(flat(100, 60), tnd(90))
        assertEquals(1, r.option(null)!!.parts)
        assertEquals(1, r.option(9)!!.parts)
        assertEquals(4, r.option(4)!!.parts)
    }

    @Test fun the_baseline_covers_today_and_180_days_from_the_engine() {
        val engine = BalanceEngine(settings(today, 100), listOf(tx(today.plusDays(3), 40, id = 1)), today)
        val b = PlanBaseline.of(engine)
        assertEquals(181, b.size)
        assertEquals(tnd(100), b.closings[0])
        assertEquals(tnd(60), b.closings[3])
        assertEquals(tnd(60), b.closings[180])
        assertEquals(today.plusDays(180), b.date(180))
    }

    @Test fun overdue_expenses_count_as_due_today_and_overdue_income_is_ignored() {
        val items = listOf(tx(today.minusDays(2), 30, id = 1), tx(today.minusDays(1), 500, TxKind.INCOME, id = 2))
        val b = PlanBaseline.of(BalanceEngine(settings(today.minusDays(5), 100), items, today))
        assertEquals(tnd(70), b.closings[0])
    }

    // ---- strip --------------------------------------------------------------------------------------------

    @Test fun the_window_is_three_days_either_side_of_a_single_purchase() {
        val b = flat(100, 181)
        val far = PurchaseOption(1, listOf(Outflow(100, tnd(10))), listOf(today.plusDays(100)))
        assertEquals(StripWindow(97, 103), PurchaseStrip.window(far, b.size))
        assertEquals(7, PurchaseStrip.window(far, b.size).size)
        val cells = PurchaseStrip.cells(b, far)
        assertEquals(7, cells.size)
        assertEquals(today.plusDays(97), cells.first().date)
        assertEquals(today.plusDays(103), cells.last().date)
        assertEquals(3, PurchaseStrip.firstPurchaseIndex(cells))
        assertFalse(cells.any { it.isToday })
    }

    @Test fun the_window_never_starts_before_today_and_never_passes_the_horizon() {
        fun one(day: Int) = PurchaseOption(1, listOf(Outflow(day, tnd(10))), listOf(today.plusDays(day.toLong())))
        assertEquals(StripWindow(0, 3), PurchaseStrip.window(one(0), 181))
        assertEquals(StripWindow(0, 4), PurchaseStrip.window(one(1), 181))
        assertEquals(StripWindow(0, 6), PurchaseStrip.window(one(3), 181))
        assertEquals(StripWindow(1, 7), PurchaseStrip.window(one(4), 181))
        assertEquals(StripWindow(177, 180), PurchaseStrip.window(one(180), 181))
        assertEquals(StripWindow(0, 3), PurchaseStrip.window(one(0), 4))
        val cellsToday = PurchaseStrip.cells(flat(100, 181), one(0))
        assertEquals(4, cellsToday.size)
        assertTrue(cellsToday[0].isToday)
        assertEquals(0, PurchaseStrip.firstPurchaseIndex(cellsToday))
    }

    @Test fun the_window_of_instalments_runs_from_before_the_first_part_to_after_the_last() {
        val b = flat(1000, 181)
        val two = PurchaseOption(2, listOf(Outflow(40, tnd(150)), Outflow(54, tnd(150))), listOf(today.plusDays(40), today.plusDays(54)))
        assertEquals(StripWindow(37, 57), PurchaseStrip.window(two, b.size))
        val cells = PurchaseStrip.cells(b, two)
        assertEquals(21, cells.size)
        assertEquals(listOf(3, 17), cells.mapIndexedNotNull { i, c -> i.takeIf { c.purchase != null } })
        // the balances come from the whole simulation: the second part lowers every later day
        assertEquals(tnd(850), cells[3].closing)
        assertEquals(tnd(700), cells[17].closing)
        val six = PlanBaseline(today, List(181) { tnd(10_000) })
        val plan = PurchasePlanner.installments(six, tnd(600), 6)!!
        val opt = PurchaseOption(6, plan, plan.map { six.date(it.dayIndex) })
        assertEquals(StripWindow(0, 5 * 7 + 3), PurchaseStrip.window(opt, six.size))
    }

    @Test fun balances_in_the_window_come_from_the_180_day_simulation_not_from_a_short_one() {
        val engine = BalanceEngine(settings(today, 100), listOf(tx(today.plusDays(30), 40, id = 1), tx(today.plusDays(120), 25, TxKind.INCOME, id = 2)), today)
        val b = PlanBaseline.of(engine)
        val option = PurchaseOption(1, listOf(Outflow(125, tnd(10))), listOf(today.plusDays(125)))
        val cells = PurchaseStrip.cells(b, option)
        assertEquals(tnd(85), cells.first().closing) // 100 - 40 + 25 on day 122, no purchase yet
        assertEquals(tnd(75), cells[3].closing)
        assertEquals(tnd(75), cells.last().closing)
    }

    @Test fun the_first_cell_and_the_first_of_a_month_carry_the_month() {
        val cells = PurchaseStrip.cells(flat(100, 181), PurchaseOption(1, listOf(Outflow(22, tnd(5))), listOf(today.plusDays(22))))
        assertEquals("31 OCT", cells[0].label)
        assertEquals("01 NOV", cells[1].label)
        assertEquals("2", cells[2].label)
    }

    @Test fun without_an_option_the_strip_shows_a_flat_week_from_today() {
        val cells = PurchaseStrip.cells(flat(100, 181), null)
        assertEquals(7, cells.size)
        assertTrue(cells.all { it.closing == tnd(100) && it.purchase == null })
        assertTrue(cells[0].isToday)
        assertEquals("12 OCT", cells[0].label)
        assertNull(PurchaseStrip.firstPurchaseIndex(cells))
        assertEquals(StripWindow(0, 2), PurchaseStrip.window(null, 3))
    }

    @Test fun negative_days_are_marked_and_the_purchase_text_is_compact() {
        val cheap = PurchaseStrip.cells(base(10, -5, 7, 7), null)
        assertEquals(listOf(false, true, false, false), cheap.map { it.negative })
        assertEquals("-5", cheap[1].closingText)
        val b = flat(100, 181)
        val cells = PurchaseStrip.cells(b, PurchasePlanner.plan(b, tnd(90)).option(3)!!)
        assertEquals(18, cells.size) // days 0..17: three parts on days 0, 7 and 14, three days after the last
        assertEquals("-30", cells[7].purchaseText)
        assertEquals(tnd(70), cells[0].closing)
        assertEquals(tnd(40), cells[7].closing)
    }

    @Test fun the_label_reads_like_the_examples() {
        val b = flat(500, 181)
        val payAll = PurchaseOption(1, listOf(Outflow(125, tnd(100))), listOf(d(2027, 2, 14)))
        assertEquals("Buy on 14 FEB 2027", PurchaseStrip.label(payAll, today))
        val soon = PurchasePlanner.plan(b, tnd(100)).option(1)!!
        assertEquals("Buy on 12 OCT 2026", PurchaseStrip.label(soon, today))
        val two = PurchaseOption(2, listOf(Outflow(13, tnd(150)), Outflow(27, tnd(150))), listOf(d(2026, 10, 25), d(2026, 11, 8)))
        assertEquals("2 x 150 TND, 25 OCT to 08 NOV", PurchaseStrip.label(two, today))
        val across = PurchaseOption(2, listOf(Outflow(80, tnd(150)), Outflow(94, tnd(150))), listOf(d(2026, 12, 30), d(2027, 1, 13)))
        assertEquals("2 x 150 TND, 30 DEC to 13 JAN 2027", PurchaseStrip.label(across, today))
        assertEquals("Pay all", PurchaseStrip.chipLabel(payAll))
        assertEquals("2", PurchaseStrip.chipLabel(two))
    }
}
