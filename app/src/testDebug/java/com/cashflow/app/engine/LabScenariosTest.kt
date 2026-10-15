package com.cashflow.app.engine

import com.cashflow.app.debug.ReferenceScenario
import com.cashflow.app.debug.lab.LabScenario
import com.cashflow.app.debug.lab.LabScenarios
import com.cashflow.app.debug.lab.LabSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** The Lab catalogue: every scenario seeds consistent data and the facts its checklist promises are true. */
class LabScenariosTest {
    private val real = LocalDate.of(2026, 10, 15)

    /** The rows the app would hold right after loading [s]: its own rows plus what maintenance generates for its series. */
    private fun rows(s: LabSeed, today: LocalDate): List<Transaction> {
        var next = 1L
        val seeded = s.transactions.map { it.copy(id = next++) }
        val generated = s.rules.filter { RuleMaintenance.isActive(it) }.flatMap { rule ->
            RuleMaintenance.materialize(rule, seeded.filter { it.recurringRuleId == rule.id }, today)
        }.map { it.copy(id = next++) }
        return seeded + generated
    }

    private fun engine(s: LabSeed, today: LocalDate) = BalanceEngine(s.settings, rows(s, today).filter { !it.date.isBefore(s.settings.balanceStartDate) }, today)

    private fun scenario(id: String): LabScenario = LabScenarios.byId(id)!!

    @Test fun ids_are_unique_and_every_scenario_is_described() {
        val ids = LabScenarios.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (s in LabScenarios.all) {
            assertTrue(s.id, s.title.isNotBlank())
            assertFalse(s.id, s.description.contains('\n'))
            assertTrue("${s.id}: ${s.checklist.size} bullets", s.checklist.size in 3..6)
            assertTrue(s.id, s.checklist.all { it.isNotBlank() && !it.contains('\n') })
        }
        assertTrue(listOf("reference", "empty", "upcoming-tabs", "purchase-far").all { it in ids })
        assertNull(LabScenarios.byId("nope"))
    }

    @Test fun every_seed_is_consistent() {
        for (s in LabScenarios.all) {
            val seed = s.build(real)
            val ruleIds = seed.rules.map { it.id }
            assertTrue("${s.id}: positive unique rule ids", ruleIds.all { it > 0 } && ruleIds.size == ruleIds.toSet().size)
            assertTrue("${s.id}: rows point at a seeded series", seed.transactions.all { it.recurringRuleId == null || it.recurringRuleId in ruleIds })
            assertTrue("${s.id}: ask flags belong to seeded series", ruleIds.containsAll(seed.askAmountRuleIds))
            assertTrue("${s.id}: amounts", seed.transactions.all { it.amount > 0 } && seed.rules.all { it.amount > 0 })
            val all = rows(seed, s.today(real))
            // a series' rows never repeat a day
            assertEquals(s.id, all.size, all.map { it.recurringRuleId to it.occurrenceDate }.filter { it.first != null }.toSet().size + all.count { it.recurringRuleId == null })
        }
    }

    @Test fun the_scenario_day_is_its_clock_day_or_the_real_day() {
        assertEquals(LocalDate.of(2026, 10, 10), scenario("reference").today(real))
        assertEquals(LocalDateTime.of(2026, 10, 10, 9, 0), scenario("reference").clock)
        assertEquals(real, scenario("empty").today(real))
        assertNull(scenario("empty").clock)
        assertEquals(LabScenarios.BASE_DAY, scenario("upcoming-tabs").today(real))
        assertEquals(DayOfWeek.MONDAY, LabScenarios.BASE_DAY.dayOfWeek)
    }

    @Test fun the_reference_scenario_is_the_sheet() {
        val s = scenario("reference")
        val seed = s.build(real)
        assertEquals(ReferenceScenario.oneOffExpenses, seed.transactions)
        assertEquals(setOf(1L, 2L, 3L), seed.rules.map { it.id }.toSet())
        assertEquals(tnd(180), engine(seed, s.today(real)).available())
        assertEquals(tnd(-10), engine(seed, s.today(real)).forecast(d(2026, 11, 10), d(2026, 11, 10)).single().closing)
    }

    @Test fun the_empty_scenario_has_nothing() {
        val seed = scenario("empty").build(real)
        assertTrue(seed.transactions.isEmpty() && seed.rules.isEmpty())
        assertEquals(0L, seed.settings.startingBalance)
        assertEquals(real, seed.settings.balanceStartDate)
    }

    @Test fun the_tabs_scenario_mixes_repeating_and_one_time_income_and_expenses() {
        val s = scenario("upcoming-tabs")
        val t = s.today(real)
        val seed = s.build(real)
        val engine = engine(seed, t)
        fun items(type: UpcomingType) = Upcoming.build(engine, seed.rules, type, DayOfWeek.MONDAY).groups.flatMap { it.rows }
        val all = items(UpcomingType.ALL)
        assertTrue(all.any { it.repeating && it.tx.kind == TxKind.EXPENSE })
        assertTrue(all.any { it.repeating && it.tx.kind == TxKind.INCOME })
        assertTrue(all.any { !it.repeating && it.tx.kind == TxKind.EXPENSE })
        assertTrue(all.any { !it.repeating && it.tx.kind == TxKind.INCOME })
        assertTrue(items(UpcomingType.EXPENSES).all { it.tx.kind == TxKind.EXPENSE } && items(UpcomingType.EXPENSES).isNotEmpty())
        assertTrue(items(UpcomingType.INCOME).all { it.tx.kind == TxKind.INCOME } && items(UpcomingType.INCOME).isNotEmpty())
        // far rows are listed and tappable: Car repair (+45 days) and Bonus (+90 days)
        assertEquals(setOf("Car repair", "Bonus"), all.filter { it.daysAway >= 45 && !it.repeating }.map { it.tx.description }.toSet())
        assertTrue(all.filter { it.waiting }.map { it.tx.description }.containsAll(listOf("Pharmacy", "Lunch")))
        // the daily income is the pinned row, not listed one by one
        assertEquals(tnd(20), Upcoming.build(engine, seed.rules, UpcomingType.ALL, DayOfWeek.MONDAY).dailyIncome)
        assertNull(Upcoming.build(engine, seed.rules, UpcomingType.EXPENSES, DayOfWeek.MONDAY).dailyIncome)
        // the series view: six series, filtered by the tab
        fun series(type: UpcomingType) = SeriesList.build(seed.rules, engine.transactions, SeriesContext(), t, type).map { it.rule.description }.toSet()
        assertEquals(setOf("Gym", "Phone", "Rent", "Salary", "Freelance", "Daily income"), series(UpcomingType.ALL))
        assertEquals(setOf("Gym", "Phone", "Rent"), series(UpcomingType.EXPENSES))
        assertEquals(setOf("Salary", "Freelance", "Daily income"), series(UpcomingType.INCOME))
    }

    private fun baseline(id: String): Pair<PlanBaseline, LocalDate> {
        val s = scenario(id)
        val t = s.today(real)
        return PlanBaseline.of(engine(s.build(real), t)) to t
    }

    @Test fun the_far_purchase_scenario_is_safe_only_months_away() {
        val (b, t) = baseline("purchase-far")
        val result = PurchasePlanner.plan(b, tnd(1200))
        assertEquals(LocalDate.of(2027, 2, 1), result.payAllDate)
        assertEquals(1, result.defaultParts)
        val payAll = result.option(1)!!
        assertEquals(112, payAll.startIndex)
        assertEquals(StripWindow(109, 115), PurchaseStrip.window(payAll, b.size))
        assertEquals("Buy on 01 FEB 2027", PurchaseStrip.label(payAll, t))
        val cells = PurchaseStrip.cells(b, payAll)
        assertEquals(LocalDate.of(2027, 1, 29), cells.first().date)
        assertEquals(LocalDate.of(2027, 2, 4), cells.last().date)
        assertEquals(3, PurchaseStrip.firstPurchaseIndex(cells))
        assertEquals(tnd(818), cells[3].closing)
        assertEquals(tnd(20), cells[2].closing)
        val two = result.option(2)!!
        assertEquals(listOf(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 8)), two.dates)
        assertEquals("2 x 600 TND, 01 FEB 2027 to 08 FEB 2027", PurchaseStrip.label(two, t))
        assertEquals(StripWindow(109, 122), PurchaseStrip.window(two, b.size))
        assertFalse(PurchasePlanner.plan(b, tnd(9000)).affordable)
    }

    @Test fun the_instalment_scenario_starts_soon_and_spans_weeks() {
        val (b, _) = baseline("purchase-instalments")
        val result = PurchasePlanner.plan(b, tnd(700))
        assertTrue(result.affordable)
        val six = result.option(6)
        assertNotNull(six)
        val window = PurchaseStrip.window(six!!, b.size)
        assertTrue(window.from >= 0)
        assertTrue("window ${window.size} days", window.size > 28)
        val first = result.options.minOf { it.startIndex }
        assertTrue(first < 30)
        assertEquals(maxOf(0, first - 3), PurchaseStrip.window(result.options.minByOrNull { it.startIndex }!!, b.size).from)
    }

    @Test fun the_history_scenario_has_answered_past_rows_an_overdue_row_and_future_rows() {
        val s = scenario("history-guard")
        val t = s.today(real)
        val seed = s.build(real)
        val all = rows(seed, t)
        val gym = seed.rules.single { it.description == "Gym" }
        val gymRows = all.filter { it.recurringRuleId == gym.id }
        assertEquals(3, gymRows.count { it.status == TxStatus.CONFIRMED })
        val overdue = gymRows.single { it.date == LocalDate.of(2026, 10, 9) }
        assertEquals(TxStatus.PENDING, overdue.status)
        assertTrue(HistoryGuard.isHistory(overdue, t))
        assertTrue(gymRows.any { !it.date.isBefore(t) && it.status == TxStatus.PENDING })
        // deleting the series touches nothing in the past
        val plan = SeriesOps.planDelete(gym, gymRows, t)
        val removed = gymRows.filter { it.id in plan.removeIds }
        assertTrue(removed.isNotEmpty() && removed.all { !it.date.isBefore(t) && it.status == TxStatus.PENDING })
        assertTrue(gymRows.filter { HistoryGuard.isHistory(it, t) }.all { it.id in plan.detachIds })
    }

    @Test fun the_ask_amount_scenario_has_a_bill_due_today_that_asks() {
        val s = scenario("ask-amount")
        val t = s.today(real)
        val seed = s.build(real)
        val all = rows(seed, t)
        val electricity = seed.rules.single { it.description == "Electricity bill" }
        assertEquals(setOf(electricity.id), seed.askAmountRuleIds)
        val dueToday = all.filter { it.date == t && it.status == TxStatus.PENDING }.map { it.description }.toSet()
        assertEquals(setOf("Electricity bill", "Water bill", "Coffee beans"), dueToday)
        assertEquals(tnd(90), all.single { it.recurringRuleId == electricity.id && it.date == t }.amount)
    }

    @Test fun the_backup_scenario_is_a_small_ledger_with_one_series() {
        val seed = scenario("backup-restore").build(real)
        assertEquals(1, seed.rules.size)
        assertTrue(seed.transactions.size in 3..6)
        assertTrue(rows(seed, scenario("backup-restore").today(real)).any { it.recurringRuleId == seed.rules.single().id })
    }
}
