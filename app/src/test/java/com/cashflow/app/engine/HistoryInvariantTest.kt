package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Random

/**
 * A small in-memory ledger that applies the same pure plans the repository executes (the guarded rule changes, the
 * delete-series plan, the item guard), so the permanent-history rule can be checked over many random sequences.
 */
private class Ledger(var today: LocalDate, val start: LocalDate) {
    val rules = mutableListOf<RecurringRule>()
    var rows = mutableListOf<Transaction>()
    private var nextId = 1L
    private var nextRule = 1L

    fun addRule(r: RecurringRule): RecurringRule {
        val rule = r.copy(id = nextRule++)
        rules += rule
        maintain()
        return rule
    }

    fun addRow(t: Transaction): Transaction {
        val row = t.copy(id = nextId++)
        rows += row
        return row
    }

    fun rowsOf(ruleId: Long) = rows.filter { it.recurringRuleId == ruleId }

    fun maintain() {
        for (rule in rules.toList()) {
            if (!RuleMaintenance.isActive(rule)) continue
            for (t in RuleMaintenance.materialize(rule, rowsOf(rule.id), today)) rows += t.copy(id = nextId++)
        }
    }

    /** What the repository's apply(change) does: guard the deletions, never insert a duplicate occurrence. */
    fun apply(change: RuleChange) {
        val rowsOfRule = rowsOf(change.rule.id)
        val guarded = HistoryGuard.guarded(change, rowsOfRule, today)
        rules.replaceAll { if (it.id == change.rule.id) change.rule else it }
        rows.removeAll { it.id in guarded.deleteIds }
        for (t in HistoryGuard.freshInserts(guarded, rows.filter { it.recurringRuleId == change.rule.id })) rows += t.copy(id = nextId++)
    }

    fun deleteSeries(id: Long): Triple<RecurringRule, DeleteSeriesPlan, List<Transaction>>? {
        val rule = rules.firstOrNull { it.id == id } ?: return null
        val plan = SeriesOps.planDelete(rule, rowsOf(id), today)
        val removed = rows.filter { it.id in plan.removeIds }
        rows = SeriesOps.applyDelete(rows, plan).toMutableList()
        rules.remove(rule)
        return Triple(rule, plan, removed)
    }

    fun undoDeleteSeries(d: Triple<RecurringRule, DeleteSeriesPlan, List<Transaction>>) {
        rules += d.first
        rows = SeriesOps.undoDelete(rows, d.second, d.third, d.first.id).toMutableList()
    }

    fun endSeries(id: Long, from: LocalDate) {
        val rule = rules.firstOrNull { it.id == id } ?: return
        apply(RuleMaintenance.endFrom(rule, rowsOf(id), from, today))
    }

    fun stop(id: Long) {
        val rule = rules.firstOrNull { it.id == id } ?: return
        apply(RuleMaintenance.stop(rule, rowsOf(id), today))
    }

    fun edit(new: RecurringRule) {
        val old = rules.firstOrNull { it.id == new.id } ?: return
        apply(RuleMaintenance.edit(old, new, rowsOf(old.id), today))
    }

    fun extend(id: Long, choice: ExtendChoice) {
        val rule = rules.firstOrNull { it.id == id } ?: return
        SeriesExtend.apply(rule, rowsOf(id), choice, today)?.let(::apply)
    }

    /** Swipe-delete / item delete: refused for history, otherwise removed (manual) or skipped (generated). */
    fun deleteItem(id: Long): Boolean {
        val row = rows.firstOrNull { it.id == id } ?: return false
        if (HistoryGuard.isHistory(row, today)) return false
        when (val outcome = ItemEdits.delete(row)) {
            is DeleteOutcome.Remove -> rows.removeAll { it.id == outcome.id }
            is DeleteOutcome.MarkSkipped -> rows.replaceAll { if (it.id == outcome.id) it.copy(status = TxStatus.SKIPPED) else it }
        }
        return true
    }

    fun deleteThisAndFuture(id: Long) {
        val row = rows.firstOrNull { it.id == id } ?: return
        val rule = rules.firstOrNull { it.id == row.recurringRuleId } ?: return
        apply(Deletions.planThisAndFuture(row, rule, rowsOf(rule.id), today).change)
    }

    fun reject(id: Long) {
        val row = rows.firstOrNull { it.id == id } ?: return
        if (ItemEdits.canAnswer(row, today) && !row.date.isBefore(today)) rows.replaceAll { if (it.id == id) it.copy(status = TxStatus.SKIPPED) else it }
    }

    fun accept(id: Long) {
        val row = rows.firstOrNull { it.id == id } ?: return
        if (ItemEdits.canAnswer(row, today) && !row.date.isBefore(today)) rows.replaceAll { if (it.id == id) it.copy(status = TxStatus.CONFIRMED) else it }
    }

    fun postpone(id: Long, to: LocalDate) {
        val row = rows.firstOrNull { it.id == id } ?: return
        if (row.date.isBefore(today)) return
        Postpone.apply(row, to)?.let { moved -> rows.replaceAll { if (it.id == id) moved else it } }
    }

    fun history(): List<Transaction> = rows.filter { HistoryGuard.isHistory(it, today) }

    fun key(t: Transaction) = listOf(t.date, t.amount, t.status, t.description, t.kind)

    /** Closing balance of every day before today (history), from the balance engine. */
    fun pastBalances(): List<Long> {
        val engine = BalanceEngine(AppSettings(startingBalance = 100_000, balanceStartDate = start), rows, today)
        return engine.forecast(start, today.minusDays(1)).map { it.closing }
    }
}

class HistoryInvariantTest {
    private val today0 = d(2026, 10, 12)

    private fun ledger(): Ledger = Ledger(today0.minusDays(45), today0.minusDays(60))

    private fun patterns(rnd: Random): RecurrencePattern = when (rnd.nextInt(8)) {
        0 -> RecurrencePattern.Daily
        1 -> RecurrencePattern.Weekly(DayOfWeek.of(rnd.nextInt(7) + 1))
        2 -> RecurrencePattern.WeeklyDays(setOf(DayOfWeek.MONDAY, DayOfWeek.of(rnd.nextInt(7) + 1)))
        3 -> RecurrencePattern.MonthFirst
        4 -> RecurrencePattern.MonthLast
        5 -> RecurrencePattern.DayOfMonth(rnd.nextInt(31) + 1)
        6 -> RecurrencePattern.EveryNDays(rnd.nextInt(20) + 2, today0.minusDays(50))
        else -> RecurrencePattern.Daily
    }

    @Test fun random_sequences_of_series_operations_never_touch_history() {
        repeat(250) { seed ->
            val rnd = Random(seed.toLong())
            val l = ledger()
            repeat(rnd.nextInt(3) + 1) {
                val start = l.today.minusDays(rnd.nextInt(40).toLong())
                val end = if (rnd.nextBoolean()) start.plusDays(rnd.nextInt(120).toLong() + 10) else null
                l.addRule(
                    rule(patterns(rnd), start, end = end, amount = rnd.nextInt(50) + 5L, kind = if (rnd.nextBoolean()) TxKind.EXPENSE else TxKind.INCOME),
                )
            }
            // some one-off rows, past and future
            repeat(rnd.nextInt(6)) {
                l.addRow(tx(l.today.plusDays(rnd.nextInt(60) - 30L), rnd.nextInt(80) + 1L, description = "one-off $it"))
            }
            // the user accepted a random part of what is already due: that is history now
            for (r in l.rows.toList()) if (r.date.isBefore(l.today) && rnd.nextInt(3) == 0) {
                l.rows.replaceAll { if (it.id == r.id) it.copy(status = TxStatus.CONFIRMED) else it }
            }
            val deleted = ArrayDeque<Triple<RecurringRule, DeleteSeriesPlan, List<Transaction>>>()

            repeat(10) { step ->
                val before = l.history().map { it.id to l.key(it) }
                val pastBefore = l.pastBalances()
                val todayBefore = l.today
                val ids = l.rules.map { it.id }
                val rowIds = l.rows.map { it.id }
                val label = "seed $seed step $step"
                when (rnd.nextInt(11)) {
                    0 -> ids.randomOrNull(rnd)?.let { id -> l.deleteSeries(id)?.let(deleted::addLast) }
                    1 -> ids.randomOrNull(rnd)?.let { l.endSeries(it, l.today.plusDays(rnd.nextInt(20) - 5L)) }
                    2 -> ids.randomOrNull(rnd)?.let { l.stop(it) }
                    3 -> l.rules.randomOrNull(rnd)?.let { l.edit(it.copy(amount = it.amount + 7_000, pattern = patterns(rnd), endDate = it.endDate)) }
                    4 -> ids.randomOrNull(rnd)?.let { l.extend(it, ExtendChoice.QUICK[rnd.nextInt(ExtendChoice.QUICK.size)]) }
                    5 -> rowIds.randomOrNull(rnd)?.let { l.deleteItem(it) }
                    6 -> rowIds.randomOrNull(rnd)?.let { l.deleteThisAndFuture(it) }
                    7 -> rowIds.randomOrNull(rnd)?.let { l.reject(it) }
                    8 -> rowIds.randomOrNull(rnd)?.let { l.accept(it) }
                    9 -> rowIds.randomOrNull(rnd)?.let { l.postpone(it, l.today.plusDays(rnd.nextInt(12) + 1L)) }
                    else -> deleted.removeLastOrNull()?.let { if (l.rules.none { r -> r.id == it.first.id }) l.undoDeleteSeries(it) }
                }
                l.maintain()
                // the ledger's day moves on now and then: yesterday's waiting rows become history by date
                if (rnd.nextInt(6) == 0) {
                    l.today = l.today.plusDays(1)
                    l.maintain()
                }
                if (l.today == todayBefore) {
                    for ((id, key) in before) {
                        val now = l.rows.firstOrNull { it.id == id }
                        assertNotNull("$label: history row $id vanished", now)
                        assertEquals("$label: history row $id changed", key, l.key(now!!))
                    }
                    assertEquals("$label: past balances changed", pastBefore, l.pastBalances())
                } else {
                    // after the day moved on only rows that were history BEFORE must still be there unchanged
                    for ((id, key) in before) assertEquals("$label: history row $id changed", key, l.rows.first { it.id == id }.let(l::key))
                }
            }
        }
    }

    private fun <T> List<T>.randomOrNull(rnd: Random): T? = if (isEmpty()) null else this[rnd.nextInt(size)]

    // ---- targeted paths ------------------------------------------------------------------------------------

    private val today = d(2026, 10, 12)

    private fun seeded(): Ledger {
        val l = Ledger(today, today.minusDays(30))
        val r = l.addRule(rule(RecurrencePattern.Daily, today.minusDays(5), id = 0, amount = 10).copy(description = "Coffee"))
        // days before today were answered: three accepted, one rejected, one still waiting
        l.rows.replaceAll {
            when {
                it.recurringRuleId != r.id -> it
                it.date == today.minusDays(5) || it.date == today.minusDays(4) || it.date == today.minusDays(3) -> it.copy(status = TxStatus.CONFIRMED)
                it.date == today.minusDays(2) -> it.copy(status = TxStatus.SKIPPED)
                else -> it
            }
        }
        return l
    }

    private fun pastKeys(l: Ledger) = l.rows.filter { it.date.isBefore(l.today) }.map { it.id to l.key(it) }.sortedBy { it.first }

    @Test fun delete_series_keeps_the_past_as_standalone_items_with_everything_unchanged() {
        val l = seeded()
        val past = pastKeys(l)
        val balances = l.pastBalances()
        l.deleteSeries(1)
        assertEquals(past, pastKeys(l))
        assertTrue(l.rows.filter { it.date.isBefore(today) }.all { it.recurringRuleId == null })
        assertEquals(balances, l.pastBalances())
        assertTrue(l.rows.none { it.date.isAfter(today) }) // the waiting future rows are gone
    }

    @Test fun delete_series_leaves_todays_and_overdue_waiting_rows_in_place() {
        val l = seeded()
        val waiting = l.rows.filter { it.status == TxStatus.PENDING && !it.date.isAfter(today) }.map { it.id }
        assertTrue(waiting.isNotEmpty())
        l.deleteSeries(1)
        assertTrue(waiting.all { id -> l.rows.any { it.id == id && it.status == TxStatus.PENDING } })
    }

    @Test fun end_series_only_removes_waiting_rows_after_the_chosen_day() {
        val l = seeded()
        val past = pastKeys(l)
        l.endSeries(1, today.plusDays(10))
        assertEquals(past, pastKeys(l))
        assertTrue(l.rows.none { it.date.isAfter(today.plusDays(9)) && it.recurringRuleId == 1L })
        assertTrue(l.rows.any { it.date == today.plusDays(9) })
        l.endSeries(1, today.minusDays(20)) // an "end" far in the past must not delete anything
        assertEquals(past, pastKeys(l))
        assertTrue(l.rows.any { it.status == TxStatus.CONFIRMED })
    }

    @Test fun stop_series_removes_nothing_that_is_due() {
        val l = seeded()
        val due = l.rows.filter { !it.date.isAfter(today) }.map { it.id to l.key(it) }
        l.stop(1)
        assertEquals(due, l.rows.filter { !it.date.isAfter(today) }.map { it.id to l.key(it) })
    }

    @Test fun edit_this_and_future_regenerates_only_waiting_future_rows() {
        val l = seeded()
        val past = pastKeys(l)
        val old = l.rules.single()
        l.edit(old.copy(amount = 99_000, description = "Espresso", pattern = RecurrencePattern.Weekly(DayOfWeek.MONDAY)))
        assertEquals(past, pastKeys(l))
        assertTrue(l.rows.filter { it.date.isAfter(today) }.all { it.amount == 99_000L && it.description == "Espresso" })
    }

    @Test fun changing_the_schedule_type_never_regenerates_or_rewrites_accepted_days() {
        val l = seeded()
        val accepted = l.rows.filter { it.status == TxStatus.CONFIRMED }.map { it.id to l.key(it) }
        l.edit(l.rules.single().copy(pattern = RecurrencePattern.MonthLast))
        assertEquals(accepted, l.rows.filter { it.status == TxStatus.CONFIRMED }.map { it.id to l.key(it) })
    }

    @Test fun extend_adds_future_rows_and_touches_nothing_else() {
        val l = seeded()
        l.edit(l.rules.single().copy(endDate = today.plusDays(5)))
        val before = l.rows.map { it.id to l.key(it) }.toSet()
        l.extend(1, ExtendChoice.Months(1))
        val after = l.rows.map { it.id to l.key(it) }.toSet()
        assertTrue(after.containsAll(before))
        assertTrue(l.rows.all { r -> r.occurrenceDate == null || l.rows.count { it.occurrenceDate == r.occurrenceDate && it.recurringRuleId == r.recurringRuleId } == 1 })
    }

    @Test fun extending_an_ended_series_does_not_resurrect_the_days_it_was_off() {
        val l = Ledger(today, today.minusDays(60))
        l.addRule(rule(RecurrencePattern.Daily, today.minusDays(40), end = today.minusDays(20), id = 0))
        val before = l.rows.size
        l.extend(1, ExtendChoice.Months(1))
        assertTrue(l.rows.drop(before).all { !it.date.isBefore(today) })
    }

    @Test fun swipe_delete_this_one_skips_a_future_occurrence_and_never_removes_it() {
        val l = seeded()
        val future = l.rows.first { it.date == today.plusDays(3) }
        assertTrue(l.deleteItem(future.id))
        assertEquals(TxStatus.SKIPPED, l.rows.first { it.id == future.id }.status)
        assertEquals(1, l.rows.count { it.occurrenceDate == future.occurrenceDate }) // the uniqueness of the occurrence stays
    }

    @Test fun swipe_delete_this_and_all_future_ends_the_series_and_keeps_the_past() {
        val l = seeded()
        val past = pastKeys(l)
        val target = l.rows.first { it.date == today.plusDays(4) }
        l.deleteThisAndFuture(target.id)
        assertEquals(past, pastKeys(l))
        assertTrue(l.rows.none { it.recurringRuleId == 1L && it.date.isAfter(today.plusDays(3)) })
    }

    @Test fun item_delete_is_refused_for_confirmed_rows_and_for_rows_before_today() {
        val l = seeded()
        val confirmed = l.rows.first { it.status == TxStatus.CONFIRMED }
        val overdue = l.rows.first { it.status == TxStatus.PENDING && it.date.isBefore(today) }
        val manualPast = l.addRow(tx(today.minusDays(3), 10, status = TxStatus.CONFIRMED, description = "kept"))
        assertFalse(l.deleteItem(confirmed.id))
        assertFalse(l.deleteItem(overdue.id))
        assertFalse(l.deleteItem(manualPast.id))
        assertEquals(3, l.rows.count { it.id in setOf(confirmed.id, overdue.id, manualPast.id) })
    }

    @Test fun a_waiting_manual_row_dated_today_or_later_can_still_be_deleted() {
        val l = seeded()
        val manual = l.addRow(tx(today.plusDays(2), 20, description = "plan"))
        assertTrue(l.deleteItem(manual.id))
        assertTrue(l.rows.none { it.id == manual.id })
    }

    @Test fun reject_and_postpone_only_act_on_waiting_rows_from_today_on() {
        val l = seeded()
        val past = pastKeys(l)
        val future = l.rows.first { it.date == today.plusDays(2) }
        l.reject(future.id)
        l.postpone(future.id, today.plusDays(9))
        assertEquals(past, pastKeys(l))
        l.rows.filter { it.date.isBefore(today) }.forEach { l.postpone(it.id, today.plusDays(30)); l.reject(it.id) }
        assertEquals(past, pastKeys(l))
    }

    @Test fun the_history_guard_filters_a_deletion_that_would_touch_history() {
        val rows = listOf(
            tx(today.minusDays(1), 1, id = 1), tx(today, 1, status = TxStatus.CONFIRMED, id = 2),
            tx(today, 1, id = 3), tx(today.plusDays(2), 1, id = 4),
        )
        assertEquals(listOf(3L, 4L), HistoryGuard.deletable(rows, listOf(1, 2, 3, 4), today))
        val change = RuleChange(rule(RecurrencePattern.Daily, today), listOf(1, 2, 3, 4), emptyList())
        assertEquals(listOf(3L, 4L), HistoryGuard.guarded(change, rows, today).deleteIds)
    }

    @Test fun duplicate_occurrences_are_never_inserted() {
        val r = rule(RecurrencePattern.Daily, today, id = 1)
        val existing = listOf(r.occurrenceFor(today).copy(id = 1))
        val change = RuleChange(r, emptyList(), listOf(r.occurrenceFor(today), r.occurrenceFor(today.plusDays(1))))
        assertEquals(listOf(today.plusDays(1)), HistoryGuard.freshInserts(change, existing).map { it.date })
    }

    @Test fun undo_of_delete_series_relinks_the_rows_and_does_not_overwrite_what_changed_meanwhile() {
        val l = seeded()
        val d = l.deleteSeries(1)!!
        // meanwhile the user answers the overdue row from the detached copy
        val overdue = l.rows.first { it.status == TxStatus.PENDING && it.date.isBefore(today) }
        l.rows.replaceAll { if (it.id == overdue.id) it.copy(status = TxStatus.CONFIRMED, amount = 12_340) else it }
        l.undoDeleteSeries(d)
        val back = l.rows.first { it.id == overdue.id }
        assertEquals(TxStatus.CONFIRMED, back.status)
        assertEquals(12_340L, back.amount)
        assertEquals(1L, back.recurringRuleId)
        assertEquals(1, l.rules.size)
        assertTrue(l.rows.any { it.date.isAfter(today) && it.recurringRuleId == 1L }) // the waiting future rows returned
    }

    @Test fun undo_of_delete_series_does_not_duplicate_rows() {
        val l = seeded()
        val count = l.rows.size
        val d = l.deleteSeries(1)!!
        l.undoDeleteSeries(d)
        assertEquals(count, l.rows.size)
        assertEquals(count, l.rows.map { it.id }.toSet().size)
    }

    @Test fun undo_of_end_series_and_of_this_and_future_bring_the_removed_waiting_rows_back_only() {
        val l = seeded()
        val rule = l.rules.single()
        val target = l.rows.first { it.date == today.plusDays(4) }
        val plan = Deletions.planThisAndFuture(target, rule, l.rowsOf(1), today)
        val before = l.rows.map { it.id to l.key(it) }.toSet()
        l.apply(plan.change)
        val ledger = Deletions.undo(plan, LedgerState(l.rows, l.rules))
        assertEquals(before, ledger.rows.map { it.id to l.key(it) }.toSet())
        assertTrue(plan.removed.all { HistoryGuard.deletable(before.let { l.rows + plan.removed }, listOf(it.id), today).isNotEmpty() })
    }

    @Test fun auto_accept_only_confirms_waiting_recurring_income_and_never_touches_confirmed_rows() {
        val income = rule(RecurrencePattern.Daily, today.minusDays(3), kind = TxKind.INCOME, id = 1, amount = 30)
        val rows = listOf(
            income.occurrenceFor(today.minusDays(2)).copy(id = 1, status = TxStatus.CONFIRMED, amount = 12_000),
            income.occurrenceFor(today.minusDays(1)).copy(id = 2),
            income.occurrenceFor(today).copy(id = 3),
            tx(today, 5, id = 4),
        )
        val settings = AppSettings(startingBalance = 0, balanceStartDate = today.minusDays(10), autoAcceptIncome = true)
        val changed = applyAutoIncome(today.atTime(13, 0), rows, settings)
        assertEquals(setOf(2L, 3L), changed.map { it.id }.toSet())
        assertTrue(changed.all { it.status == TxStatus.CONFIRMED })
    }

    // ---- accept undo ---------------------------------------------------------------------------------------

    @Test fun undo_after_accept_restores_the_waiting_row_within_the_window() {
        val before = tx(today, 75, id = 1, description = "Rent")
        val token = AcceptUndo.token(before, nowMillis = 1_000)
        val current = before.copy(status = TxStatus.CONFIRMED)
        val restored = AcceptUndo.restore(current, token, 1_000 + 5_000)!!
        assertEquals(TxStatus.PENDING, restored.status)
        assertEquals(before.amount, restored.amount)
    }

    @Test fun undo_after_an_accept_with_an_edited_amount_restores_the_planned_amount() {
        val before = tx(today, 75, id = 1)
        val token = AcceptUndo.token(before, amount = 71_000, nowMillis = 0)
        val current = before.copy(status = TxStatus.CONFIRMED, amount = 71_000)
        assertEquals(75_000L, AcceptUndo.restore(current, token, 3_000)!!.amount)
    }

    @Test fun undo_of_a_variable_amount_series_accept_restores_the_occurrence_only() {
        val series = rule(RecurrencePattern.Daily, today, id = 5, amount = 75)
        val before = series.occurrenceFor(today).copy(id = 9)
        val token = AcceptUndo.token(before, amount = 70_000, nowMillis = 0)
        val restored = AcceptUndo.restore(before.copy(status = TxStatus.CONFIRMED, amount = 70_000), token, 100)!!
        assertEquals(75_000L, restored.amount)
        assertEquals(5L, restored.recurringRuleId)
        assertEquals(today, restored.occurrenceDate)
    }

    @Test fun undo_after_done_restores_name_and_date_too() {
        val before = tx(today, 20, id = 1, description = "Tea")
        val token = AcceptUndo.token(before, amount = 22_000, description = "Green tea", date = today.minusDays(1), nowMillis = 0)
        val current = before.copy(status = TxStatus.CONFIRMED, amount = 22_000, description = "Green tea", date = today.minusDays(1))
        val restored = AcceptUndo.restore(current, token, 10)!!
        assertEquals("Tea", restored.description)
        assertEquals(today, restored.date)
    }

    @Test fun undo_is_refused_when_the_window_is_over_or_the_row_changed_again() {
        val before = tx(today, 75, id = 1)
        val token = AcceptUndo.token(before, nowMillis = 0)
        val confirmed = before.copy(status = TxStatus.CONFIRMED)
        assertNull(AcceptUndo.restore(confirmed, token, AcceptUndo.WINDOW_MILLIS + 1))
        assertNull(AcceptUndo.restore(confirmed.copy(amount = 1_000), token, 100)) // edited since
        assertNull(AcceptUndo.restore(before, token, 100)) // already waiting again
        assertNull(AcceptUndo.restore(null, token, 100)) // deleted
        assertNotNull(AcceptUndo.restore(confirmed, token, AcceptUndo.WINDOW_MILLIS))
    }

    // ---- Upcoming: filters and taps -------------------------------------------------------------------------

    private fun engineWith(vararg items: Transaction) = BalanceEngine(settings(today.minusDays(5), 100), items.toList(), today)

    private val daily = incomeSource(RecurrencePattern.Daily, today, id = 2, amount = 30)

    @Test fun the_tabs_filter_the_occurrence_list_by_type() {
        val oneOffExpense = tx(today.plusDays(2), 10, id = 1)
        val seriesExpense = rule(RecurrencePattern.Weekly(DayOfWeek.FRIDAY), today, id = 3).occurrenceFor(today.plusDays(3)).copy(id = 2)
        val oneOffIncome = tx(today.plusDays(4), 20, TxKind.INCOME, id = 3)
        val engine = engineWith(oneOffExpense, seriesExpense, oneOffIncome)
        val rules = listOf(rule(RecurrencePattern.Weekly(DayOfWeek.FRIDAY), today, id = 3))
        fun ids(t: UpcomingType) = Upcoming.build(engine, rules, t, DayOfWeek.MONDAY).groups.flatMap { it.rows }.map { it.tx.id }.toSet()
        assertEquals(setOf(1L, 2L, 3L), ids(UpcomingType.ALL))
        assertEquals(setOf(1L, 2L), ids(UpcomingType.EXPENSES))
        assertEquals(setOf(3L), ids(UpcomingType.INCOME))
    }

    @Test fun the_view_state_keeps_the_tab_when_the_series_toggle_flips_and_the_other_way_round() {
        val start = UpcomingView()
        assertEquals(UpcomingType.ALL, start.type)
        assertFalse(start.showSeries)
        val income = start.withType(UpcomingType.INCOME)
        val series = income.toggleSeries()
        assertTrue(series.showSeries)
        assertEquals(UpcomingType.INCOME, series.type)
        assertEquals(UpcomingType.EXPENSES, series.withType(UpcomingType.EXPENSES).type)
        assertTrue(series.withType(UpcomingType.EXPENSES).showSeries)
        assertEquals(income, series.toggleSeries())
        assertTrue(start.withSeries(true).showSeries)
        assertTrue(start.withSeries(true).withSeries(true).showSeries)
    }

    @Test fun the_series_view_is_filtered_by_the_selected_tab() {
        val exp = rule(RecurrencePattern.Daily, today, id = 1)
        val inc = incomeSource(RecurrencePattern.Weekly(DayOfWeek.MONDAY), today, id = 2)
        fun ids(view: UpcomingView) = SeriesList.build(listOf(exp, inc), emptyList(), SeriesContext(), today, view.type).map { it.rule.id }.toSet()
        assertEquals(setOf(1L, 2L), ids(UpcomingView(UpcomingType.ALL, true)))
        assertEquals(setOf(1L), ids(UpcomingView(UpcomingType.EXPENSES, true)))
        assertEquals(setOf(2L), ids(UpcomingView(UpcomingType.INCOME, true)))
    }

    @Test fun the_series_list_follows_the_type_filter() {
        val exp = rule(RecurrencePattern.Daily, today, id = 1)
        val inc = incomeSource(RecurrencePattern.Daily, today, id = 2)
        fun ids(t: UpcomingType) = SeriesList.build(listOf(exp, inc), emptyList(), SeriesContext(), today, t).map { it.rule.id }.toSet()
        assertEquals(setOf(1L, 2L), ids(UpcomingType.ALL))
        assertEquals(setOf(1L), ids(UpcomingType.EXPENSES))
        assertEquals(setOf(2L), ids(UpcomingType.INCOME))
    }

    @Test fun every_row_of_the_list_maps_to_a_tap_that_opens_its_details() {
        val rules = listOf(daily, rule(RecurrencePattern.Weekly(DayOfWeek.FRIDAY), today, id = 3))
        val rows = listOf(
            tx(today.minusDays(2), 10, id = 1), tx(today, 5, id = 2), tx(today.plusDays(1), 5, id = 3), tx(today.plusDays(90), 5, id = 4),
            rules[1].occurrenceFor(today.plusDays(3)).copy(id = 5), rules[1].occurrenceFor(today.plusDays(100)).copy(id = 6),
            daily.occurrenceFor(today).copy(id = 7), daily.occurrenceFor(today.plusDays(1)).copy(id = 8), daily.occurrenceFor(today.plusDays(110)).copy(id = 9),
        )
        val model = Upcoming.build(engineWith(*rows.toTypedArray()), rules, UpcomingType.ALL, DayOfWeek.MONDAY)
        val listed = model.groups.flatMap { it.rows }
        assertTrue(listed.isNotEmpty())
        for (row in listed) assertEquals(UpcomingTap.OpenDetails(row.tx.id), Upcoming.tapFor(row))
        // the far-future occurrence of a series is listed and tappable, a future daily income is summarized in the pinned row
        assertTrue(listed.any { it.tx.id == 6L })
        assertTrue(listed.none { it.tx.id in setOf(8L, 9L) })
    }

    @Test fun the_pinned_daily_income_row_is_tappable_too() {
        val model = Upcoming.build(engineWith(daily.occurrenceFor(today).copy(id = 1)), listOf(daily), UpcomingType.ALL, DayOfWeek.MONDAY)
        assertEquals(UpcomingTap.OpenSeries(2), Upcoming.tapForDaily(model))
        val two = listOf(daily, incomeSource(RecurrencePattern.Daily, today, id = 4))
        assertEquals(UpcomingTap.ShowRepeating, Upcoming.tapForDaily(Upcoming.build(engineWith(), two, UpcomingType.ALL, DayOfWeek.MONDAY)))
        assertNull(Upcoming.tapForDaily(Upcoming.build(engineWith(), emptyList(), UpcomingType.ALL, DayOfWeek.MONDAY)))
        assertNull(Upcoming.tapForDaily(Upcoming.build(engineWith(), two, UpcomingType.EXPENSES, DayOfWeek.MONDAY)))
        assertNotNull(Upcoming.tapForDaily(Upcoming.build(engineWith(), two, UpcomingType.INCOME, DayOfWeek.MONDAY)))
    }

    @Test fun a_future_item_opens_details_with_accept_off_and_postpone_on() {
        val a = ItemActionMap.forItem(TxStatus.PENDING, today.plusDays(40), today, belongsToSeries = true)
        assertFalse(a.accept)
        assertTrue(a.postpone && a.seriesEdit)
    }
}
