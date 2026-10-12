package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek

class EntryProjectionTest {
    private val start = d(2026, 10, 1)
    private val today = d(2026, 10, 10)
    private val engine = BalanceEngine(settings(start, 100), emptyList(), today)

    @Test fun a_now_expense_shows_the_available_amount_after_it() {
        val r = EntryProjection.resultingBalance(engine, NewEntry.Now(TxKind.EXPENSE, tnd(30), "x"))!!
        assertEquals(today, r.date)
        assertEquals(tnd(70), r.balance)
        assertEquals(tnd(130), EntryProjection.resultingBalance(engine, NewEntry.Now(TxKind.INCOME, tnd(30), "x"))!!.balance)
    }

    @Test fun a_later_item_shows_the_balance_on_its_day() {
        val r = EntryProjection.resultingBalance(engine, NewEntry.Planned(TxKind.EXPENSE, tnd(150), "x", d(2026, 10, 20)))!!
        assertEquals(d(2026, 10, 20), r.date)
        assertEquals(tnd(-50), r.balance)
    }

    @Test fun a_repeating_payment_shows_the_balance_on_its_first_occurrence() {
        val sunday = NewEntry.Recurring(TxKind.EXPENSE, tnd(60), "x", RecurrencePattern.Weekly(DayOfWeek.SUNDAY), d(2026, 10, 10))
        val r = EntryProjection.resultingBalance(engine, sunday)!! // the first Sunday on or after Oct 10 is Oct 11
        assertEquals(d(2026, 10, 11), r.date)
        assertEquals(tnd(40), r.balance)
    }

    @Test fun engine_with_adds_the_rows_the_way_saving_would() {
        val applied = EntryProjection.engineWith(engine, NewEntry.Recurring(TxKind.EXPENSE, tnd(10), "x", RecurrencePattern.Daily, d(2026, 10, 11)))
        assertEquals(tnd(90), applied.forecast(d(2026, 10, 11), d(2026, 10, 11)).single().closing)
        assertEquals(tnd(80), applied.forecast(d(2026, 10, 12), d(2026, 10, 12)).single().closing)
        assertEquals(engine.available(), applied.available()) // waiting items do not change Available
        assertNull(EntryProjection.resultingBalance(engine, NewEntry.Planned(TxKind.EXPENSE, tnd(1), "x", d(2026, 9, 1))))
    }
}
