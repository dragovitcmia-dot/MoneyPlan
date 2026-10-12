package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class EntryPlannerTest {
    private val today = d(2026, 10, 15)

    @Test fun now_creates_a_confirmed_immediate_manual_transaction_dated_today() {
        val t = EntryPlanner.transactionFor(NewEntry.Now(TxKind.EXPENSE, tnd(12), "Coffee"), today)!!
        assertEquals(today, t.date)
        assertEquals(TxStatus.CONFIRMED, t.status)
        assertFalse(t.isPlanned)
        assertEquals(TxSource.MANUAL, t.source)
        assertEquals(TxKind.EXPENSE, t.kind)
        assertEquals(tnd(12), t.amount)
        assertNull(t.recurringRuleId)
    }

    @Test fun planned_creates_a_pending_planned_manual_transaction() {
        val t = EntryPlanner.transactionFor(NewEntry.Planned(TxKind.INCOME, tnd(50), "Gift", d(2026, 10, 20)), today)!!
        assertEquals(d(2026, 10, 20), t.date)
        assertEquals(TxStatus.PENDING, t.status)
        assertTrue(t.isPlanned)
        assertEquals(TxSource.MANUAL, t.source)
        assertEquals(TxKind.INCOME, t.kind)
    }

    @Test fun planned_today_or_earlier_is_allowed_and_lands_in_to_confirm() {
        for (date in listOf(today, today.minusDays(3))) {
            val t = EntryPlanner.transactionFor(NewEntry.Planned(TxKind.EXPENSE, tnd(5), "x", date), today)!!
            val e = BalanceEngine(settings(d(2026, 10, 1), 100), listOf(t), today)
            assertEquals(listOf(date), e.toConfirm().map { it.date })
        }
    }

    @Test fun blank_description_gets_a_default() {
        assertEquals("Expense", EntryPlanner.transactionFor(NewEntry.Now(TxKind.EXPENSE, 1, "  "), today)!!.description)
        assertEquals("Income", EntryPlanner.transactionFor(NewEntry.Now(TxKind.INCOME, 1, ""), today)!!.description)
        assertEquals("Rent", EntryPlanner.transactionFor(NewEntry.Now(TxKind.EXPENSE, 1, " Rent "), today)!!.description)
    }

    @Test fun recurring_entries_produce_rules_not_transactions() {
        val e = NewEntry.Recurring(TxKind.EXPENSE, tnd(30), "Gym", RecurrencePattern.Weekly(DayOfWeek.MONDAY), today)
        assertNull(EntryPlanner.transactionFor(e, today))
        val rule = EntryPlanner.ruleFor(e)!!
        assertEquals(TxKind.EXPENSE, rule.kind)
        assertEquals(tnd(30), rule.amount)
        assertEquals("Gym", rule.description)
        assertEquals(today, rule.startDate)
        assertTrue(rule.enabled)
        assertNull(rule.endDate)
    }

    @Test fun recurring_income_makes_an_enabled_income_source_with_default_confirm_time() {
        val e = NewEntry.Recurring(TxKind.INCOME, tnd(30), "", RecurrencePattern.Daily, d(2026, 10, 16), d(2026, 12, 31))
        val rule = EntryPlanner.ruleFor(e)!!
        assertEquals(TxKind.INCOME, rule.kind)
        assertEquals("Income", rule.description)
        assertEquals(java.time.LocalTime.NOON, rule.confirmTime)
        assertEquals(d(2026, 12, 31), rule.endDate)
        assertTrue(RuleMaintenance.isActive(rule))
    }

    @Test fun now_and_planned_have_no_rule() {
        assertNull(EntryPlanner.ruleFor(NewEntry.Now(TxKind.EXPENSE, 1, "")))
        assertNull(EntryPlanner.ruleFor(NewEntry.Planned(TxKind.EXPENSE, 1, "", today)))
    }

    @Test fun validation() {
        assertNotNull(EntryPlanner.validate(NewEntry.Now(TxKind.EXPENSE, 0, "")))
        assertNotNull(EntryPlanner.validate(NewEntry.Planned(TxKind.EXPENSE, -5, "", today)))
        assertNull(EntryPlanner.validate(NewEntry.Now(TxKind.EXPENSE, 1, "")))
        val daily = NewEntry.Recurring(TxKind.EXPENSE, 1, "", RecurrencePattern.Daily, today)
        assertNotNull("daily is income-only", EntryPlanner.validate(daily))
        val everyN = NewEntry.Recurring(TxKind.INCOME, 1, "", RecurrencePattern.EveryNDays(3, today), today)
        assertNotNull("every N days is expense-only", EntryPlanner.validate(everyN))
        val badEnd = NewEntry.Recurring(TxKind.EXPENSE, 1, "", RecurrencePattern.MonthLast, today, today.minusDays(1))
        assertNotNull(EntryPlanner.validate(badEnd))
        assertNull(EntryPlanner.validate(NewEntry.Recurring(TxKind.EXPENSE, 1, "", RecurrencePattern.EveryNDays(3, today), today, today)))
    }

    @Test fun every_n_days_expense_materializes_from_its_start_date() {
        val e = NewEntry.Recurring(TxKind.EXPENSE, tnd(10), "x", RecurrencePattern.EveryNDays(7, d(2026, 10, 20)), d(2026, 10, 20))
        val rows = RuleMaintenance.materialize(EntryPlanner.ruleFor(e)!!, emptyList(), today)
        assertEquals(d(2026, 10, 20), rows.first().date)
        assertEquals(d(2026, 10, 27), rows[1].date)
    }
}
