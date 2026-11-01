package com.cashflow.app.engine

import com.cashflow.app.debug.ReferenceScenario
import com.cashflow.app.debug.lab.LabFlow
import com.cashflow.app.debug.lab.LabRelease
import com.cashflow.app.debug.lab.LabResults
import com.cashflow.app.debug.lab.LabScenario
import com.cashflow.app.debug.lab.LabSession
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

/** The Lab catalogue: every scenario seeds consistent data and the facts its questions promise are true. */
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
            assertTrue("${s.id}: has screens", s.steps.isNotEmpty())
            for ((i, step) in s.steps.withIndex()) {
                assertTrue("${s.id} step $i: ${step.checks.size} questions", step.checks.size in 2..4)
                assertTrue("${s.id} step $i", step.checks.all { it.isNotBlank() && !it.contains('\n') && it.endsWith("?") })
            }
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
        // the daily income is a normal repeating item: only its next occurrence is listed (v0.10.1), no pinned summary row
        val dailyRows = all.filter { it.tx.description == "Daily income" && !it.waiting }
        assertEquals(1, dailyRows.size)
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

    @Test fun the_last_payment_scenario_ends_tomorrow_and_the_reminder_waits_for_noon() {
        val s = scenario("last-payment-tomorrow")
        assertEquals(LocalDateTime.of(2026, 10, 12, 11, 55), s.clock)
        val seed = s.build(real)
        val rule = seed.rules.single()
        assertEquals(d(2026, 10, 13), rule.endDate)
        val items = rows(seed, d(2026, 10, 12))
        assertEquals(d(2026, 10, 13), SeriesLast.lastDate(rule, items))
        assertEquals(2, items.size)
        val zone = java.time.ZoneId.of("Africa/Tunis")
        fun at(time: String, day: LocalDate = d(2026, 10, 12)) = day.atTime(java.time.LocalTime.parse(time)).atZone(zone).toInstant()
        fun last(time: String, day: LocalDate = d(2026, 10, 12)) = Notifications.reconcile(at(time, day), zone, NotificationSettings(), items, seed.rules, emptyList(), emptySet())
            .filter { it.type == NotificationType.LAST_PAYMENT }
        assertTrue(last("11:55").isEmpty())
        val fired = last("12:05").single()
        assertEquals("Trial subscription −5 TND ends 13 OCT. Extend?", fired.body)
        assertEquals(1L, fired.ruleId)
        // after the last day it is too late
        assertTrue(last("12:05", d(2026, 10, 15)).isEmpty())
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

/** The v0.10.1 scenarios: zero-effort (they navigate themselves) and the hand-worked facts their questions promise. */
class LabScenariosV101Test {
    private val real = LocalDate.of(2026, 10, 15)
    private fun scenario(id: String) = LabScenarios.byId(id)!!
    private val t = LabScenarios.BASE_DAY

    private fun rows(s: LabSeed, today: LocalDate): List<Transaction> {
        var next = 1L
        val seeded = s.transactions.map { it.copy(id = next++) }
        val generated = s.rules.filter { RuleMaintenance.isActive(it) }.flatMap { rule ->
            RuleMaintenance.materialize(rule, seeded.filter { it.recurringRuleId == rule.id }, today)
        }.map { it.copy(id = next++) }
        return seeded + generated
    }

    private fun engine(id: String, day: LocalDate = t): Pair<BalanceEngine, LabSeed> {
        val seed = scenario(id).build(real)
        return BalanceEngine(seed.settings, rows(seed, day).filter { !it.date.isBefore(seed.settings.balanceStartDate) }, day) to seed
    }

    private fun tx(id: String, name: String, date: LocalDate): Transaction =
        engine(id).first.transactions.first { it.description == name && it.date == date }

    @Test fun every_scenario_navigates_itself_and_never_asks_for_typing_creating_editing_or_deleting() {
        for (s in LabScenarios.all) {
            val targets = s.targets(s.today(real))
            assertEquals(s.id, s.steps.size, targets.size)
            for (step in s.steps) for (q in step.checks) {
                val low = q.lowercase()
                for (word in listOf("type in", "tap ", "enter ", "create ", "add an ", "delete ", "swipe", "press ")) assertFalse("${s.id}: '$q'", low.contains(word))
            }
        }
        assertTrue(listOf("item-sheets", "date-picker", "upcoming-daily", "spent-today", "backups-row").all { LabScenarios.byId(it) != null })
    }

    @Test fun item_sheets_open_the_four_kinds_of_item_with_the_actions_each_allows() {
        val id = "item-sheets"
        val s = scenario(id)
        val targets = s.targets(t)
        assertEquals(
            listOf(
                UiTarget.CalendarDay(t.plusDays(5), "Dentist"), UiTarget.CalendarDay(t, "Lunch"),
                UiTarget.CalendarDay(t.plusDays(4), "Gym"), UiTarget.CalendarDay(t.minusDays(6), "Groceries"),
            ),
            targets,
        )
        val dentist = ItemRules.actionsFor(tx(id, "Dentist", t.plusDays(5)), t)
        assertTrue(dentist.change && dentist.moveDate && !dentist.accept && !dentist.series && !dentist.history)
        val lunch = ItemRules.actionsFor(tx(id, "Lunch", t), t)
        assertTrue(lunch.accept && lunch.change && lunch.moveDate)
        val gym = tx(id, "Gym", t.plusDays(4)) // Friday 16 OCT
        assertEquals(DayOfWeek.FRIDAY, gym.date.dayOfWeek)
        assertNotNull(gym.recurringRuleId)
        val gymActions = ItemRules.actionsFor(gym, t)
        assertTrue(gymActions.series && !gymActions.accept)
        val groceries = ItemRules.actionsFor(tx(id, "Groceries", t.minusDays(6)), t)
        assertTrue(groceries.history && groceries.none)
        // the one plain meta lines the questions describe
        assertEquals("SAT 17 OCT 2026 · Waiting", ItemRules.meta(tx(id, "Dentist", t.plusDays(5)), null))
        val rule = scenario(id).build(real).rules.single()
        assertTrue(ItemRules.meta(gym, rule).endsWith("Every Fri"))
    }

    @Test fun the_date_picker_scenario_preselects_the_suggested_days() {
        val id = "date-picker"
        val phone = tx(id, "Phone bill", t.plusDays(2))
        // the picker opens on the suggested day (a one-time item: a week from the later of today and its date)
        assertEquals(LocalDate.of(2026, 10, 21), Postpone.suggestedDate(phone, null, t))
        assertTrue(ItemRules.canMoveTo(phone, t, t)) // today is selectable although the item is two days ahead
        assertFalse(ItemRules.canMoveTo(phone, t.minusDays(1), t))
        assertNull(ItemRules.pickerCaption(phone))
        val rule = scenario(id).build(real).rules.single()
        val gym = tx(id, "Gym", t.plusDays(4))
        assertEquals(LocalDate.of(2026, 10, 19), Postpone.suggestedDate(gym, rule.pattern, t))
        assertEquals("Only this day moves", ItemRules.pickerCaption(gym))
        assertEquals(
            listOf(UiTarget.CalendarDay(t.plusDays(2), "Phone bill", move = true), UiTarget.CalendarDay(t.plusDays(4), "Gym", move = true)),
            scenario(id).targets(t),
        )
    }

    @Test fun the_upcoming_scenario_has_a_daily_income_once_and_every_sunday_with_no_special_card() {
        val (engine, seed) = engine("upcoming-daily")
        val model = Upcoming.build(engine, seed.rules, UpcomingType.ALL, DayOfWeek.MONDAY)
        val rows = model.groups.flatMap { it.rows }
        val daily = rows.filter { it.tx.description == "Daily income" }
        assertEquals(1, daily.size)
        assertEquals(t.plusDays(1), daily.single().tx.date)
        assertTrue(daily.single().repeating)
        val dailyRule = seed.rules.first { it.description == "Daily income" }
        assertEquals("Every day", SeriesText.schedule(dailyRule.pattern))
        assertEquals("TUE 13 OCT 2026 · in 1 day · Every day", ItemRules.rowSubtitle(daily.single().tx.date, "in 1 day", dailyRule))
        val sundays = rows.filter { it.tx.description == "Sunday expense" }
        assertTrue(sundays.size >= 10)
        assertTrue(sundays.all { it.tx.date.dayOfWeek == DayOfWeek.SUNDAY && it.repeating })
        assertEquals(UpcomingGroupKey.Tomorrow, model.groups.first { g -> g.rows.any { it.tx.description == "Daily income" } }.key)
    }

    @Test fun the_spent_today_scenario_adds_up_by_hand() {
        val (engine, seed) = engine("spent-today")
        // 500 - 12 - 8 - 30 = 450 available; spent today = 12 + 8 = 20 (Taxi was yesterday)
        assertEquals(tnd(450), engine.available())
        assertEquals(tnd(20), SpentToday.of(engine))
        assertTrue(seed.transactions.all { it.status == TxStatus.CONFIRMED && !it.isPlanned })
        val day = CalendarSummaries.month(engine, seed.rules, java.time.YearMonth.of(2026, 10), DayOfWeek.MONDAY).day(t)!!
        assertEquals(tnd(20), day.expenses)
        assertEquals(setOf("Coffee", "Snack"), day.items.map { it.tx.description }.toSet())
        assertEquals(tnd(450), day.closing)
        assertEquals(listOf(UiTarget.CalendarDay(t), UiTarget.Home), scenario("spent-today").targets(t))
    }

    @Test fun the_backups_scenario_seeds_seven_backups_on_seven_days_ending_now() {
        val seed = scenario("backups-row").build(real)
        assertEquals(7, seed.backups.size)
        assertEquals((0..6).toSet(), seed.backups.map { it.daysAgo }.toSet()) // one per day: nothing is consolidated away
        assertEquals(0, seed.backups.maxBy { it.daysAgo * -1 }.daysAgo) // the newest is "now"
        assertEquals(listOf(UiTarget.Settings, UiTarget.Backups), scenario("backups-row").targets(t))
        assertEquals(2, scenario("backup-restore").build(real).backups.size)
    }

    @Test fun home_states_moves_the_clock_by_itself_for_the_shortage() {
        val s = scenario("home-states")
        assertEquals(listOf(0, 30), s.steps.map { it.clockShiftDays })
        assertTrue(s.steps.all { it.target(t) == UiTarget.Home })
    }

    @Test fun the_smart_suggestion_scenario_opens_the_wizard_for_each_name() {
        val targets = scenario("smart-suggestions").targets(t)
        assertEquals(UiTarget.Wizard(TxKind.EXPENSE, "", "", atName = false), targets.first())
        assertEquals(UiTarget.Wizard(TxKind.EXPENSE, "75", "Gym"), targets[1])
        assertTrue(targets.drop(1).all { (it as UiTarget.Wizard).atName && it.amountText.isNotEmpty() })
    }

    @Test fun the_purchase_scenarios_open_the_result_by_themselves() {
        assertEquals(UiTarget.Purchase("1200", "Laptop", parts = 2), scenario("purchase-far").targets(t)[1])
        assertEquals(UiTarget.Purchase("700", "Sofa", parts = 6), scenario("purchase-instalments").targets(t)[1])
    }
}

class LabFlowTest {
    @Test fun looks_right_walks_through_the_steps_then_the_next_scenario() {
        var session = LabFlow.start(listOf("a", "b"))!!
        val first = LabFlow.mark(session, ok = true, stepCount = 2)
        assertEquals(LabSession(listOf("a", "b"), index = 0, step = 1), first.session)
        assertNull(first.finishedId)
        session = first.session!!
        val second = LabFlow.mark(session, ok = true, stepCount = 2)
        assertEquals("a", second.finishedId)
        assertEquals(true, second.verdict)
        assertEquals(LabSession(listOf("a", "b"), index = 1), second.session)
        val last = LabFlow.mark(second.session!!, ok = true, stepCount = 1)
        assertEquals("b", last.finishedId)
        assertNull(last.session) // the run is over: the Results screen follows
    }

    @Test fun a_problem_marks_the_scenario_and_goes_straight_on() {
        val session = LabSession(listOf("a", "b", "c"), index = 0, step = 1)
        val marked = LabFlow.mark(session, ok = false, stepCount = 3)
        assertEquals("a", marked.finishedId)
        assertEquals(false, marked.verdict)
        assertEquals(LabSession(listOf("a", "b", "c"), index = 1), marked.session)
    }

    @Test fun an_empty_queue_has_no_session() = assertNull(LabFlow.start(emptyList()))

    @Test fun results_round_trip_and_summarise_at_a_glance() {
        val ids = listOf("a", "b", "c", "d")
        val results = LabResults().with("a", true).with("b", false).with("c", true)
        assertEquals(results, LabResults.decode(results.encode()))
        assertEquals("2 right, 1 problem, 1 not run", results.summary(ids))
        assertEquals(true, results.mark("a"))
        assertEquals(false, results.mark("b"))
        assertNull(results.mark("d"))
        assertEquals(results.with("b", true).mark("b"), true) // a new mark replaces the old one
        assertEquals(LabResults(), LabResults.decode(null))
        assertEquals(LabResults(), LabResults.decode("garbage;x=2;=1"))
        assertEquals("0 right, 0 problems, 4 not run", LabResults().summary(ids))
    }
}

/** The v0.10.2 scenarios: History with six months of items, hand-worked totals, and the empty state. */
class LabScenariosV102Test {
    private val real = LocalDate.of(2026, 10, 15)
    private val t = LabScenarios.BASE_DAY
    private fun seed(id: String) = LabScenarios.byId(id)!!.build(real)

    /** Everything the seed puts into the ledger: its own rows plus what maintenance generates for its series. */
    private fun rows(id: String): List<Transaction> {
        val s = seed(id)
        var next = 1L
        val seeded = s.transactions.map { it.copy(id = next++) }
        val generated = s.rules.filter { RuleMaintenance.isActive(it) }.flatMap { rule ->
            RuleMaintenance.materialize(rule, seeded.filter { it.recurringRuleId == rule.id }, t)
        }.map { it.copy(id = next++) }
        return seeded + generated
    }

    @Test fun lots_of_history_seeds_hundreds_of_confirmed_rows_over_six_months() {
        val all = rows("history-lots")
        val confirmed = HistoryList.confirmedOnly(all)
        assertTrue("${confirmed.size} confirmed", confirmed.size in 250..800)
        val first = confirmed.minOf { it.date }
        val last = confirmed.maxOf { it.date }
        assertTrue(first.isBefore(t.minusMonths(5)))
        assertEquals(t, last)
        assertTrue(confirmed.any { it.source == TxSource.RECURRING }) // accepted series occurrences
        assertTrue(confirmed.any { !it.isPlanned }) // Now expenses
        assertTrue(confirmed.any { it.kind == TxKind.INCOME })
        // nothing is overdue: the series' past occurrences were all accepted; only later ones wait
        assertTrue(all.filter { it.status == TxStatus.PENDING }.all { it.date.isAfter(t) })
    }

    @Test fun one_item_is_named_phone_cover_and_the_search_finds_only_it() {
        val confirmed = HistoryList.confirmedOnly(rows("history-lots"))
        val hits = HistoryList.filter(confirmed, UpcomingType.EXPENSES, "cover")
        assertEquals(1, hits.size)
        assertEquals("Phone cover", hits.single().description)
        assertEquals(LocalDate.of(2026, 10, 3), hits.single().date)
        assertEquals(25_000L, hits.single().amount)
        assertEquals("1 result, total -25 TND", HistoryList.resultsLine(hits))
        assertEquals(1, HistoryList.filter(confirmed, UpcomingType.ALL, "COVER").size)
        assertEquals("SAT 03 OCT 2026 · Done", ItemRules.meta(hits.single(), null))
        assertTrue(ItemRules.actionsFor(hits.single(), t).history)
    }

    @Test fun the_totals_strip_adds_up_by_hand() {
        // week 12..18 OCT: Coffee 6 + Lunch 14 = 20. month OCT spent: Rent 300 + Phone cover 25 + Groceries 42 + Taxi 18 + 20 = 405.
        // month OCT received: Salary 1200 + Refund 80 = 1280.
        val totals = HistoryList.totals(rows("history-lots"), t)
        assertEquals(20_000L, totals.spentThisWeek)
        assertEquals(405_000L, totals.spentThisMonth)
        assertEquals(1_280_000L, totals.receivedThisMonth)
        assertEquals("20 TND", Money.formatDisplay(totals.spentThisWeek))
        assertEquals("405 TND", Money.formatDisplay(totals.spentThisMonth))
        assertEquals("1 280 TND", Money.formatDisplay(totals.receivedThisMonth).replace('\u00A0', ' ').replace('\u202F', ' '))
    }

    @Test fun the_list_is_newest_first_with_day_totals_and_the_first_row_is_what_the_check_opens() {
        val days = HistoryList.groupByDay(HistoryList.filter(rows("history-lots"), UpcomingType.ALL, ""))
        assertEquals(t, days.first().date)
        assertEquals(setOf("Coffee", "Lunch"), days.first().items.map { it.description }.toSet())
        assertEquals(20_000L, days.first().spent)
        assertEquals(0L, days.first().received)
        assertEquals("MON 12 OCT 2026", AppDates.withWeekday(days.first().date))
        assertTrue(days.zipWithNext().all { (a, b) -> a.date.isAfter(b.date) })
        val oct1 = days.first { it.date == LocalDate.of(2026, 10, 1) }
        assertEquals(1_200_000L, oct1.received)
        // the first row of the Expenses tab with the search "cover" is Phone cover (what the last step opens)
        assertEquals("Phone cover", HistoryList.filter(rows("history-lots"), UpcomingType.EXPENSES, "cover").first().description)
    }

    @Test fun the_steps_apply_the_tab_the_search_and_the_sheet_by_themselves() {
        val targets = LabScenarios.byId("history-lots")!!.targets(t)
        assertEquals(
            listOf(
                UiTarget.History(UpcomingType.ALL), UiTarget.History(UpcomingType.EXPENSES),
                UiTarget.History(UpcomingType.EXPENSES, "cover"), UiTarget.History(UpcomingType.EXPENSES, "cover", openFirst = true),
            ),
            targets,
        )
    }

    @Test fun history_empty_has_no_confirmed_row_and_zero_totals() {
        val all = rows("history-empty")
        assertTrue(HistoryList.confirmedOnly(all).isEmpty())
        assertEquals(HistoryTotals(0, 0, 0), HistoryList.totals(all, t))
        assertEquals("0 TND", Money.formatDisplay(0))
        assertEquals(listOf(UiTarget.History(UpcomingType.ALL)), LabScenarios.byId("history-empty")!!.targets(t))
    }

    @Test fun the_history_scenarios_are_tagged_with_the_release_that_changed_them() {
        assertEquals(listOf("history-lots", "history-empty"), LabScenarios.all.filter { it.release == "0.10.2" }.map { it.id })
    }
}

/** The v0.10.3 lean run: release tags, "Check this update", the five-screen cap, and the four glass checks. */
class LabScenariosV103Test {
    private val real = LocalDate.of(2026, 10, 15)

    private fun scenario(id: String): LabScenario = LabScenarios.byId(id)!!

    private fun rows(s: LabSeed, today: LocalDate): List<Transaction> {
        var next = 1L
        val seeded = s.transactions.map { it.copy(id = next++) }
        val generated = s.rules.filter { RuleMaintenance.isActive(it) }.flatMap { rule ->
            RuleMaintenance.materialize(rule, seeded.filter { it.recurringRuleId == rule.id }, today)
        }.map { it.copy(id = next++) }
        return seeded + generated
    }

    private fun currentVersionName(): String? {
        val file = listOf("build.gradle.kts", "app/build.gradle.kts").map { java.io.File(it) }.firstOrNull { it.isFile } ?: return null
        return Regex("""versionName\s*=\s*"([^"]+)"""").find(file.readText())?.groupValues?.get(1)
    }

    @Test fun every_scenario_is_tagged_with_a_release_and_the_current_release_matches_versionName() {
        val shape = Regex("""\d+\.\d+\.\d+""")
        for (s in LabScenarios.all) assertTrue("${s.id}: release '${s.release}'", shape.matches(s.release))
        val versionName = currentVersionName()
        if (versionName != null) assertEquals("LabScenarios.CURRENT_RELEASE must follow versionName", versionName, LabScenarios.CURRENT_RELEASE)
    }

    @Test fun check_this_update_runs_only_the_current_release_and_never_more_than_five_screens() {
        val update = LabRelease.forUpdate(LabScenarios.all, LabScenarios.CURRENT_RELEASE)
        assertTrue("every release adds Lab scenarios", update.isNotEmpty())
        assertTrue("${LabRelease.screens(update)} screens, the cap is ${LabRelease.MAX_SCREENS}", LabRelease.screens(update) <= LabRelease.MAX_SCREENS)
        assertEquals(5, LabRelease.MAX_SCREENS)
        assertTrue(update.all { it.release == LabScenarios.CURRENT_RELEASE })
        val older = LabRelease.older(LabScenarios.all, LabScenarios.CURRENT_RELEASE)
        assertEquals(LabScenarios.all.size, update.size + older.size)
        assertTrue(older.none { it.release == LabScenarios.CURRENT_RELEASE })
    }

    @Test fun the_lab_build_suffix_does_not_change_the_release() {
        assertEquals("0.10.3", LabRelease.of("0.10.3-lab"))
        assertEquals("0.10.3", LabRelease.of("0.10.3"))
        assertEquals(LabRelease.forUpdate(LabScenarios.all, "0.10.3").map { it.id }, LabRelease.forUpdate(LabScenarios.all, "0.10.3-lab").map { it.id })
        assertTrue(LabRelease.forUpdate(LabScenarios.all, "9.9.9").isEmpty())
    }

    @Test fun progress_reads_two_of_four() {
        assertEquals("2 of 4", LabRelease.progress(LabSession(listOf("a", "b", "c", "d"), index = 1)))
        assertEquals("1 of 1", LabRelease.progress(LabSession(listOf("a"))))
    }

    @Test fun this_release_has_the_four_glass_checks_with_their_own_seeds() {
        val update = LabRelease.forUpdate(LabScenarios.all, "0.10.3")
        assertEquals(listOf("glass-bottom-bar", "glass-item-sheet", "glass-notification-center", "glass-off"), update.map { it.id })
        assertEquals(4, LabRelease.screens(update))
        // only the last one turns glass off; only the center has bell entries
        assertEquals(listOf(false, false, false, true), update.map { it.build(real).glassOff })
        val bells = scenario("glass-notification-center").build(real).bells
        assertEquals(5, bells.size)
        assertTrue(bells.count { !it.read } >= 3 && bells.any { it.read })
        assertTrue(update.filter { it.id != "glass-notification-center" }.all { it.build(real).bells.isEmpty() })
        assertEquals(listOf<UiTarget>(UiTarget.NotificationCenter), scenario("glass-notification-center").targets(LabScenarios.BASE_DAY))
        assertEquals(UiTarget.History(UpcomingType.ALL, scrolled = true), scenario("glass-bottom-bar").targets(LabScenarios.BASE_DAY).single())
        assertEquals(scenario("glass-bottom-bar").targets(LabScenarios.BASE_DAY), scenario("glass-off").targets(LabScenarios.BASE_DAY))
        assertEquals(UiTarget.CalendarDay(LabScenarios.BASE_DAY.plusDays(5), "Dentist"), scenario("glass-item-sheet").targets(LabScenarios.BASE_DAY).single())
    }

    @Test fun the_scrolled_history_has_enough_rows_to_scroll_under_the_bar() {
        val seed = scenario("glass-bottom-bar").build(real)
        assertTrue(rows(seed, scenario("glass-bottom-bar").today(real)).size > 200)
    }
}
