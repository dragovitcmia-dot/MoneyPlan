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

/** The v0.10.0 scenarios (and the two v0.9.1 leftovers): the hand-worked numbers their checklists promise. */
class LabScenariosV10Test {
    private val real = LocalDate.of(2026, 10, 15)
    private fun scenario(id: String) = LabScenarios.byId(id)!!

    private fun rows(s: LabSeed, today: LocalDate): List<Transaction> {
        var next = 1L
        val seeded = s.transactions.map { it.copy(id = next++) }
        val generated = s.rules.filter { RuleMaintenance.isActive(it) }.flatMap { rule ->
            RuleMaintenance.materialize(rule, seeded.filter { it.recurringRuleId == rule.id }, today)
        }.map { it.copy(id = next++) }
        return seeded + generated
    }

    private fun engineAt(id: String, day: LocalDate? = null): Pair<BalanceEngine, LabSeed> {
        val s = scenario(id)
        val seed = s.build(real)
        val today = day ?: s.today(real)
        return BalanceEngine(seed.settings, rows(seed, s.today(real)).filter { !it.date.isBefore(seed.settings.balanceStartDate) }, today) to seed
    }

    @Test fun the_last_payment_scenario_ends_in_three_days_and_the_reminder_waits_for_noon() {
        val s = scenario("last-payment-tomorrow")
        assertEquals(LocalDateTime.of(2026, 10, 12, 11, 55), s.clock)
        val seed = s.build(real)
        val rule = seed.rules.single()
        assertEquals(d(2026, 10, 15), rule.endDate)
        val items = rows(seed, d(2026, 10, 12))
        assertEquals(d(2026, 10, 15), SeriesLast.lastDate(rule, items))
        assertEquals(4, items.size)
        val zone = java.time.ZoneId.of("Africa/Tunis")
        fun at(time: String, day: LocalDate = d(2026, 10, 12)) = day.atTime(java.time.LocalTime.parse(time)).atZone(zone).toInstant()
        fun last(time: String, day: LocalDate = d(2026, 10, 12)) = Notifications.reconcile(at(time, day), zone, NotificationSettings(), items, seed.rules, emptyList(), emptySet())
            .filter { it.type == NotificationType.LAST_PAYMENT }
        assertTrue(last("11:55").isEmpty())
        val fired = last("12:05").single()
        assertEquals("Trial subscription −5 TND ends 15 OCT. Extend?", fired.body)
        assertEquals(1L, fired.ruleId)
        // two days later (the time machine +2 d) the last payment is tomorrow and the reminder still stands
        assertEquals(1, last("12:05", d(2026, 10, 14)).size)
        assertTrue(last("12:05", d(2026, 10, 16)).isEmpty()) // after the last day it is too late
    }

    @Test fun the_overdue_scenario_has_five_waiting_cards_and_340_available() {
        val (engine, _) = engineAt("overdue-pending")
        assertEquals(tnd(340), engine.available())
        val waiting = engine.toConfirm()
        assertEquals(listOf("Rent", "Salary", "Phone", "Pharmacy", "Lunch"), waiting.map { it.description })
        assertEquals(listOf(d(2026, 10, 2), d(2026, 10, 7), d(2026, 10, 9), d(2026, 10, 11), d(2026, 10, 12)), waiting.map { it.date })
        assertEquals("Next: Lunch −15 TND, 12 OCT", HomeStatusLine.text(HomeStatusLine.of(engine), engine.today))
    }

    @Test fun home_states_normal_then_shortage_after_thirty_days() {
        val (day0, seed) = engineAt("home-states")
        assertEquals(tnd(500), day0.available())
        assertEquals("Next: Rent −300 TND, 05 NOV", HomeStatusLine.text(HomeStatusLine.of(day0), day0.today))
        assertTrue(day0.toConfirm().isEmpty())
        // the car repair (420 days away) is beyond the 400-day forecast on day 0 ...
        assertTrue(day0.negativeDays().isEmpty())
        // ... and inside it after the time machine moves 30 days: 500 - 300 - 450 = -250 on 06 DEC 2027
        val later = BalanceEngine(seed.settings, rows(seed, d(2026, 10, 12)), d(2026, 11, 11))
        assertEquals(HomeStatus.Short(tnd(250), d(2027, 12, 6)), HomeStatusLine.of(later))
        assertEquals("Short by 250 TND before 06 DEC 2027", HomeStatusLine.text(HomeStatusLine.of(later), later.today))
        assertEquals(d(2027, 12, 6), HomeStatusLine.tapDay(HomeStatusLine.of(later)))
        assertEquals(listOf("Rent"), later.toConfirm().map { it.description }) // the rent is overdue now
        assertEquals(tnd(500), later.available())
    }

    @Test fun smart_suggestions_have_the_chips_and_lines_the_checklist_promises() {
        val s = scenario("smart-suggestions")
        val seed = s.build(real)
        val today = s.today(real)
        val all = rows(seed, today)
        assertEquals(listOf(tnd(12), tnd(22), tnd(25)), Suggestions.recentAmounts(TxKind.EXPENSE, all, seed.rules, today))
        assertEquals(listOf(tnd(80), tnd(1200)), Suggestions.recentAmounts(TxKind.INCOME, all, seed.rules, today))
        fun line(name: String, kind: TxKind = TxKind.EXPENSE) = Suggestions.sameAsLast(name, kind, all, seed.rules)?.let(Suggestions::line)
        assertEquals("Same as last time: 75 TND, every week", line("Gym"))
        assertEquals("Same as last time: 22 TND", line("Coffee beans"))
        assertEquals("Same as last time: 300 TND, every month", line("Rent"))
        assertEquals("Same as last time: ${Money.formatDisplay(tnd(1200))}, every month", line("Salary", TxKind.INCOME))
        assertNull(line("Salary"))
        assertNull(line("Yoga"))
        assertEquals("Same as last time: 80 TND", line("refund", TxKind.INCOME))
    }
}
