package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.YearMonth

/** The one item sheet (actionsFor), the date pickers (today to forever), and the Now expense that must be trackable. */
class ItemRulesTest {
    private val today = d(2026, 10, 14)

    private fun item(date: java.time.LocalDate, status: TxStatus = TxStatus.PENDING, series: Boolean = false, kind: TxKind = TxKind.EXPENSE) =
        tx(date, 20, kind = kind, status = status, id = 1, description = "Rent").copy(recurringRuleId = if (series) 7L else null)

    // ---- actionsFor: the matrix --------------------------------------------------------------------------

    @Test fun waiting_in_the_future_changes_and_moves_but_does_not_accept() {
        val a = ItemRules.actionsFor(item(today.plusDays(3)), today)
        assertEquals(ItemActionSet(accept = false, change = true, moveDate = true, markWaiting = false, series = false, history = false), a)
    }

    @Test fun waiting_and_due_today_accepts_changes_and_moves() {
        assertEquals(ItemActionSet(accept = true, change = true, moveDate = true, markWaiting = false, series = false, history = false), ItemRules.actionsFor(item(today), today))
    }

    @Test fun overdue_waiting_is_not_history_yet_it_accepts_changes_and_moves() {
        val a = ItemRules.actionsFor(item(today.minusDays(4)), today)
        assertTrue(a.accept && a.change && a.moveDate)
        assertFalse(a.history)
    }

    @Test fun confirmed_today_and_confirmed_past_are_history_with_nothing_to_do() {
        for (date in listOf(today, today.minusDays(9), today.plusDays(2))) {
            val a = ItemRules.actionsFor(item(date, TxStatus.CONFIRMED), today)
            assertTrue("$date", a.history && a.none)
            assertFalse(a.markWaiting) // the permanent-history guard: an accepted row never goes back to waiting from the sheet
        }
    }

    @Test fun rejected_in_the_past_is_history_and_rejected_from_today_can_go_back_to_waiting() {
        assertTrue(ItemRules.actionsFor(item(today.minusDays(1), TxStatus.SKIPPED), today).let { it.history && it.none })
        for (date in listOf(today, today.plusDays(5))) {
            val a = ItemRules.actionsFor(item(date, TxStatus.SKIPPED), today)
            assertTrue(a.markWaiting)
            assertFalse(a.history || a.accept || a.change || a.moveDate)
        }
    }

    @Test fun a_series_member_gets_the_series_menu_only_while_it_is_not_history() {
        assertTrue(ItemRules.actionsFor(item(today.plusDays(7), series = true), today).series)
        assertTrue(ItemRules.actionsFor(item(today, series = true), today).series)
        assertTrue(ItemRules.actionsFor(item(today.minusDays(2), series = true), today).series) // overdue and waiting
        assertTrue(ItemRules.actionsFor(item(today.plusDays(2), TxStatus.SKIPPED, series = true), today).series)
        assertFalse(ItemRules.actionsFor(item(today.minusDays(2), TxStatus.CONFIRMED, series = true), today).series)
        assertFalse(ItemRules.actionsFor(item(today.plusDays(7), series = false), today).series) // one-time
    }

    @Test fun income_and_expense_are_offered_the_same_actions() {
        for (date in listOf(today.minusDays(1), today, today.plusDays(1))) for (status in TxStatus.entries) {
            assertEquals(
                ItemRules.actionsFor(item(date, status, kind = TxKind.EXPENSE), today),
                ItemRules.actionsFor(item(date, status, kind = TxKind.INCOME), today),
            )
        }
    }

    // ---- the date pickers: today to forever --------------------------------------------------------------

    @Test fun a_payment_on_today_plus_2_can_move_to_today() {
        val tx = item(today.plusDays(2))
        assertEquals(today, ItemRules.earliestDate(today))
        assertTrue(ItemRules.canMoveTo(tx, today, today))
        assertEquals(today, ItemRules.applyMove(tx, today, today)!!.date)
        assertTrue(ItemRules.canMoveTo(tx, today.plusDays(1), today)) // earlier than the item's date, still fine
        assertTrue(ItemRules.canMoveTo(tx, today.plusYears(30), today)) // forever
    }

    @Test fun an_overdue_item_can_move_to_today_or_later() {
        val tx = item(today.minusDays(6))
        assertTrue(ItemRules.canMoveTo(tx, today, today))
        assertTrue(ItemRules.canMoveTo(tx, today.plusDays(40), today))
        assertEquals(today, ItemRules.applyMove(tx, today, today)!!.date)
    }

    @Test fun yesterday_and_every_earlier_day_stay_disabled() {
        val tx = item(today.plusDays(2))
        assertFalse(ItemRules.isSelectable(today.minusDays(1), today))
        assertTrue(ItemRules.isSelectable(today, today))
        for (back in listOf(1L, 2L, 30L, 400L)) {
            assertFalse(ItemRules.canMoveTo(tx, today.minusDays(back), today))
            assertNull(ItemRules.applyMove(tx, today.minusDays(back), today))
        }
        assertFalse(ItemRules.canMoveTo(item(today.minusDays(6)), today.minusDays(1), today))
    }

    @Test fun moving_to_the_items_own_day_is_not_a_move_and_history_never_moves() {
        assertFalse(ItemRules.canMoveTo(item(today.plusDays(2)), today.plusDays(2), today))
        for (status in listOf(TxStatus.CONFIRMED, TxStatus.SKIPPED)) assertNull(ItemRules.applyMove(item(today.plusDays(2), status), today.plusDays(5), today))
    }

    @Test fun a_move_changes_only_the_date_and_keeps_the_series_link_and_occurrence() {
        val sunday = rule(RecurrencePattern.Weekly(DayOfWeek.SUNDAY), d(2026, 10, 1), id = 7)
        val row = sunday.occurrenceFor(d(2026, 10, 18)).copy(id = 3)
        val moved = ItemRules.applyMove(row, today, today)!!
        assertEquals(row.copy(date = today), moved)
        assertEquals(d(2026, 10, 18), moved.occurrenceDate)
        assertEquals(7L, moved.recurringRuleId)
    }

    @Test fun after_a_move_the_forecast_and_the_calendar_follow_at_once() {
        val start = d(2026, 10, 1)
        val tx = tx(today.plusDays(2), 30, id = 1)
        val moved = ItemRules.applyMove(tx, today, today)!!
        val engine = BalanceEngine(settings(start, 100), listOf(moved), today)
        val month = CalendarSummaries.month(engine, emptyList(), YearMonth.of(2026, 10), DayOfWeek.MONDAY)
        assertEquals(tnd(70), month.day(today)!!.closing)
        assertEquals(0L, month.day(today.plusDays(2))!!.expenses)
        assertEquals(listOf(moved), engine.toConfirm()) // it is due today now: Home asks about it
        val upcoming = Upcoming.build(engine, emptyList(), UpcomingType.ALL, DayOfWeek.MONDAY)
        assertEquals(UpcomingGroupKey.Waiting, upcoming.groups.single().key)
    }

    @Test fun saving_a_change_needs_a_changeable_row_and_a_date_from_today_on() {
        val overdue = item(today.minusDays(3))
        assertTrue(ItemRules.canSaveChange(overdue, overdue.date, today)) // keeping its own date
        assertTrue(ItemRules.canSaveChange(overdue, today, today))
        assertFalse(ItemRules.canSaveChange(overdue, today.minusDays(1), today))
        assertFalse(ItemRules.canSaveChange(item(today.minusDays(3), TxStatus.CONFIRMED), today, today))
        assertFalse(ItemRules.canSaveChange(item(today.minusDays(3), TxStatus.SKIPPED), today, today))
    }

    @Test fun the_move_picker_says_only_this_day_moves_for_a_series_item_and_nothing_otherwise() {
        assertEquals("Only this day moves", ItemRules.pickerCaption(item(today.plusDays(2), series = true)))
        assertNull(ItemRules.pickerCaption(item(today.plusDays(2), series = false)))
    }

    // ---- the one meta line --------------------------------------------------------------------------------

    @Test fun the_meta_line_is_plain_text_with_date_status_and_schedule() {
        val sunday = rule(RecurrencePattern.Weekly(DayOfWeek.SUNDAY), d(2026, 10, 1), end = d(2026, 12, 25), id = 7)
        val row = sunday.occurrenceFor(d(2026, 10, 18))
        assertEquals("SUN 18 OCT 2026 · Waiting · Every Sun until 25 DEC 2026", ItemRules.meta(row, sunday))
        assertEquals("TUE 13 OCT 2026 · Waiting", ItemRules.meta(item(d(2026, 10, 13)), null))
        assertEquals("TUE 13 OCT 2026 · Done", ItemRules.meta(item(d(2026, 10, 13), TxStatus.CONFIRMED), null))
        assertEquals("TUE 13 OCT 2026 · Skipped", ItemRules.meta(item(d(2026, 10, 13), TxStatus.SKIPPED), null))
        val daily = rule(RecurrencePattern.Daily, d(2026, 10, 1), id = 8)
        assertEquals("WED 14 OCT 2026 · Waiting · Every day", ItemRules.meta(daily.occurrenceFor(today), daily))
        assertEquals("This is history and cannot be changed", ItemRules.HISTORY_LINE)
    }

    // ---- an expense entered as "Now" is trackable ---------------------------------------------------------

    private fun now(amount: Long, date: java.time.LocalDate? = null) =
        EntryPlanner.transactionFor(NewEntry.Now(TxKind.EXPENSE, tnd(amount), "Coffee", date), today)!!.copy(id = 50L + amount)

    @Test fun a_now_expense_is_confirmed_today_and_counted_in_the_day_line_the_day_list_and_the_day_balance() {
        val start = d(2026, 10, 1)
        val coffee = now(12)
        assertEquals(TxStatus.CONFIRMED, coffee.status)
        assertEquals(today, coffee.date)
        val engine = BalanceEngine(settings(start, 100), listOf(coffee), today)
        val day = CalendarSummaries.month(engine, emptyList(), YearMonth.of(2026, 10), DayOfWeek.MONDAY).day(today)!!
        assertEquals(tnd(12), day.expenses) // the day's expense line
        assertTrue(day.hasExpenses)
        assertEquals(listOf("Coffee"), day.items.map { it.tx.description }) // the day list, with name and amount
        assertEquals(tnd(12), day.items.single().tx.amount)
        assertEquals(tnd(88), day.closing) // the day balance
        assertEquals(tnd(88), engine.available())
        assertEquals("-12", CalendarCellContent.of(day).expense!!.text)
    }

    @Test fun spent_today_adds_up_the_accepted_expenses_of_today_only() {
        val start = d(2026, 10, 1)
        val rows = listOf(
            now(12), now(8), now(30, today.minusDays(1)), // yesterday does not count
            tx(today, 5, id = 80), // waiting today: not spent yet
            tx(today, 6, status = TxStatus.SKIPPED, id = 81),
            tx(today, 40, kind = TxKind.INCOME, status = TxStatus.CONFIRMED, id = 82), // money in is not spent
            tx(today, 9, status = TxStatus.CONFIRMED, id = 83),
        )
        val engine = BalanceEngine(settings(start, 500), rows, today)
        assertEquals(tnd(29), SpentToday.of(engine))
        val day = CalendarSummaries.month(engine, emptyList(), YearMonth.of(2026, 10), DayOfWeek.MONDAY).day(today)!!
        // the calendar's expense line also counts the waiting 5 (it is planned for today); the accepted part equals Home's line
        assertEquals(tnd(29), day.items.filter { it.tx.kind == TxKind.EXPENSE && it.tx.status == TxStatus.CONFIRMED }.sumOf { it.tx.amount })
        assertEquals(0L, SpentToday.of(BalanceEngine(settings(start, 500), emptyList(), today)))
    }

    @Test fun spent_today_ignores_rows_before_the_ledger_starts() {
        val engine = BalanceEngine(settings(today.plusDays(1), 100), listOf(now(12)), today)
        assertEquals(0L, SpentToday.of(engine))
    }

    @Test fun a_now_expense_entered_for_a_past_day_counts_on_that_day_not_today() {
        val start = d(2026, 10, 1)
        val old = now(20, today.minusDays(2))
        val engine = BalanceEngine(settings(start, 100), listOf(old), today)
        assertEquals(0L, SpentToday.of(engine))
        val month = CalendarSummaries.month(engine, emptyList(), YearMonth.of(2026, 10), DayOfWeek.MONDAY)
        assertEquals(tnd(20), month.day(today.minusDays(2))!!.expenses)
        assertEquals(tnd(80), month.day(today.minusDays(2))!!.closing)
    }

    @Test fun the_item_move_picker_never_offers_a_row_for_history() {
        assertNotNull(ItemRules.applyMove(item(today.plusDays(1)), today, today))
        assertNull(ItemRules.applyMove(item(today.plusDays(1), TxStatus.CONFIRMED), today, today))
    }
}
