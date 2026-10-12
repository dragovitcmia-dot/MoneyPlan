package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The core rule: nothing is added or deducted unless the user accepts it.
 * Expected numbers are worked out by hand in each test, never taken from the engine.
 */
class BalanceEngineTest {
    private val start = d(2026, 10, 10)

    private fun engine(today: LocalDate, txs: List<Transaction> = emptyList(), balance: Long = 100) =
        BalanceEngine(settings(start, balance), txs, today)

    /** Daily income of 30 on every day after the start day through [to]. */
    private fun income30(to: LocalDate, status: TxStatus = TxStatus.PENDING) = dailyIncome(30, start.plusDays(1), to, status)

    private fun closing(e: BalanceEngine, date: LocalDate) = e.forecast(date, date).single().closing

    // ---- day semantics (today = the start day, everything pending: a pure forecast) ----------

    @Test fun start_day_with_nothing_on_it_opens_and_closes_at_the_starting_balance() {
        val day = engine(start, income30(d(2026, 10, 15))).forecast(start, start).single()
        assertEquals(tnd(100), day.opening)
        assertEquals(0L, day.income)
        assertEquals(tnd(100), day.closing)
    }

    @Test fun pending_income_is_added_on_its_own_date() {
        val days = engine(start, income30(d(2026, 10, 12))).forecast(start, start.plusDays(2))
        assertEquals(listOf(100L, 130L, 160L).map(::tnd), days.map { it.closing })
        assertEquals(tnd(100), days[1].opening) // the previous day's closing; the income lands during the day
        assertEquals(tnd(30), days[1].income)
    }

    @Test fun pending_expense_on_the_start_day_reduces_the_start_day_closing() {
        val e = engine(start, listOf(tx(start, 40)) + income30(d(2026, 10, 12)))
        assertEquals(tnd(60), e.forecast(start, start).single().closing)
    }

    @Test fun income_and_expense_of_one_day_are_both_applied() {
        val e = engine(start, income30(d(2026, 10, 12)) + tx(d(2026, 10, 11), 50, kind = TxKind.INCOME))
        val day = e.forecast(d(2026, 10, 11), d(2026, 10, 11)).single()
        assertEquals(tnd(100), day.opening)
        assertEquals(tnd(80), day.income) // 30 daily + 50 one-off
        assertEquals(tnd(180), day.closing)
    }

    @Test fun items_list_every_row_of_the_day_including_skipped() {
        val a = tx(d(2026, 10, 11), 5)
        val b = tx(d(2026, 10, 11), 7, status = TxStatus.SKIPPED)
        val day = engine(start, listOf(a, b)).forecast(d(2026, 10, 11), d(2026, 10, 11)).single()
        assertEquals(listOf(a, b), day.items)
        assertEquals(tnd(5), day.expenses)
    }

    @Test fun forecast_window_is_clamped_to_the_balance_start_date() {
        val days = engine(start).forecast(start.minusDays(5), start.plusDays(1))
        assertEquals(listOf(start, start.plusDays(1)), days.map { it.date })
        assertTrue(engine(start).forecast(start.minusDays(5), start.minusDays(1)).isEmpty())
    }

    // ---- Available ---------------------------------------------------------------------------

    @Test fun available_counts_only_accepted_rows_up_to_today() {
        val today = d(2026, 10, 15)
        val txs = listOf(
            tx(d(2026, 10, 11), 10, status = TxStatus.CONFIRMED),
            tx(d(2026, 10, 12), 20, status = TxStatus.PENDING),
            tx(d(2026, 10, 13), 30, status = TxStatus.SKIPPED),
            tx(d(2026, 10, 14), 5, kind = TxKind.INCOME, status = TxStatus.CONFIRMED),
            tx(d(2026, 10, 20), 999, status = TxStatus.CONFIRMED), // future-dated: takes effect on its date
            tx(d(2026, 10, 9), 999, status = TxStatus.CONFIRMED), // before the balance start
        )
        assertEquals(tnd(100 - 10 + 5), engine(today, txs).available())
    }

    @Test fun available_counts_accepted_income_rows_only() {
        val rows = dailyIncome(30, d(2026, 10, 11), d(2026, 10, 12), TxStatus.CONFIRMED) +
            dailyIncome(30, d(2026, 10, 13), d(2026, 10, 13), TxStatus.SKIPPED) +
            dailyIncome(30, d(2026, 10, 14), d(2026, 10, 14), TxStatus.PENDING)
        assertEquals(tnd(160), engine(d(2026, 10, 14), rows).available())
    }

    @Test fun nothing_is_income_without_a_stored_row() {
        assertEquals(tnd(100), engine(d(2026, 10, 20)).available())
    }

    @Test fun today_before_start_date_gives_the_starting_balance() {
        val e = engine(start.minusDays(3), listOf(tx(start, 10, status = TxStatus.CONFIRMED)))
        assertEquals(tnd(100), e.available())
    }

    @Test fun pending_rows_never_change_available_even_when_overdue() {
        val today = d(2026, 10, 14)
        val txs = listOf(
            tx(d(2026, 10, 10), 10), tx(d(2026, 10, 12), 25), tx(d(2026, 10, 14), 7), tx(d(2026, 10, 13), 99),
            tx(d(2026, 10, 11), 40, kind = TxKind.INCOME), tx(d(2026, 10, 15), 40, kind = TxKind.INCOME),
        )
        assertEquals(tnd(100), engine(today, txs).available())
    }

    @Test fun skipped_rows_have_no_effect_anywhere() {
        val txs = listOf(
            tx(d(2026, 10, 11), 50, status = TxStatus.SKIPPED),
            tx(d(2026, 10, 14), 50, status = TxStatus.SKIPPED),
            tx(d(2026, 10, 13), 50, TxKind.INCOME, TxStatus.SKIPPED),
        )
        val e = engine(d(2026, 10, 12), txs)
        assertEquals(tnd(100), e.available())
        assertTrue(e.forecast(start, d(2026, 10, 20)).all { it.closing == tnd(100) })
        assertNull(e.nextExpense())
        assertEquals(0, e.toConfirmCount())
    }

    @Test fun accepting_an_item_changes_available_by_exactly_its_amount() {
        val today = d(2026, 10, 14)
        val expense = tx(d(2026, 10, 12), 37, id = 1)
        val income = tx(d(2026, 10, 13), 60, TxKind.INCOME, id = 2)
        val before = engine(today, listOf(expense, income)).available()
        assertEquals(tnd(100), before)
        val acceptedExpense = engine(today, listOf(expense.copy(status = TxStatus.CONFIRMED), income)).available()
        assertEquals(before - tnd(37), acceptedExpense)
        val acceptedBoth = engine(today, listOf(expense.copy(status = TxStatus.CONFIRMED), income.copy(status = TxStatus.CONFIRMED))).available()
        assertEquals(before - tnd(37) + tnd(60), acceptedBoth)
    }

    @Test fun rejecting_an_item_changes_available_not_at_all() {
        val today = d(2026, 10, 14)
        val rows = listOf(tx(d(2026, 10, 12), 37, id = 1), tx(d(2026, 10, 13), 60, TxKind.INCOME, id = 2))
        val rejected = rows.map { it.copy(status = TxStatus.SKIPPED) }
        assertEquals(engine(today, rows).available(), engine(today, rejected).available())
        assertEquals(tnd(100), engine(today, rejected).available())
    }

    // ---- forecast from today (the worked example: today = Oct 14, nothing accepted) ----------

    /** Start Oct 10 with 180. Overdue and pending: expenses 128 (Oct 12) and 67 (Oct 13), income 30 on Oct 11, 12, 13. */
    private fun overdueScenario(): List<Transaction> =
        listOf(tx(d(2026, 10, 12), 128, id = 1), tx(d(2026, 10, 13), 67, id = 2)) +
            dailyIncome(30, d(2026, 10, 11), d(2026, 10, 13)).mapIndexed { i, t -> t.copy(id = 10L + i) }

    private val oct14 = d(2026, 10, 14)

    private fun scenarioEngine(extra: List<Transaction> = emptyList()) =
        BalanceEngine(settings(start, 180), overdueScenario() + extra, oct14)

    @Test fun nothing_accepted_available_is_the_starting_balance() {
        assertEquals(tnd(180), scenarioEngine().available())
    }

    @Test fun forecast_opening_today_is_available_minus_overdue_pending_expenses_only() {
        // 180 - (128 + 67) = -15. The three overdue pending incomes (90) are NOT counted.
        val today = scenarioEngine().forecast(oct14, oct14).single()
        assertEquals(tnd(-15), today.opening)
        assertEquals(tnd(-15), today.closing) // nothing is dated today
    }

    @Test fun pending_items_dated_today_and_later_are_applied_on_their_dates() {
        val e = scenarioEngine(listOf(tx(oct14, 30, id = 20), tx(oct14, 30, TxKind.INCOME, id = 21), tx(d(2026, 10, 15), 20, id = 22)))
        assertEquals(tnd(-15), closing(e, oct14)) // -15 - 30 + 30
        assertEquals(tnd(-35), closing(e, d(2026, 10, 15))) // -15 - 20
    }

    @Test fun overdue_pending_income_is_excluded_while_overdue_pending_expense_is_included() {
        val onlyIncome = BalanceEngine(settings(start, 180), dailyIncome(30, d(2026, 10, 11), d(2026, 10, 13)), oct14)
        assertEquals(tnd(180), closing(onlyIncome, oct14))
        val onlyExpense = BalanceEngine(settings(start, 180), listOf(tx(d(2026, 10, 12), 128)), oct14)
        assertEquals(tnd(52), closing(onlyExpense, oct14))
    }

    @Test fun accepting_an_overdue_expense_moves_it_from_the_forecast_adjustment_into_available() {
        val accepted = overdueScenario().map { if (it.id == 1L) it.copy(status = TxStatus.CONFIRMED) else it }
        val e = BalanceEngine(settings(start, 180), accepted, oct14)
        assertEquals(tnd(180 - 128), e.available()) // 52
        assertEquals(tnd(-15), e.forecast(oct14, oct14).single().opening) // 52 - 67
    }

    @Test fun rejecting_an_overdue_expense_removes_it_from_the_forecast_and_leaves_available() {
        val rejected = overdueScenario().map { if (it.id == 1L) it.copy(status = TxStatus.SKIPPED) else it }
        val e = BalanceEngine(settings(start, 180), rejected, oct14)
        assertEquals(tnd(180), e.available())
        assertEquals(tnd(180 - 67), e.forecast(oct14, oct14).single().opening) // 113
    }

    @Test fun accepting_overdue_income_raises_available_and_so_the_forecast() {
        val accepted = overdueScenario().map { if (it.id == 10L) it.copy(status = TxStatus.CONFIRMED) else it }
        val e = BalanceEngine(settings(start, 180), accepted, oct14)
        assertEquals(tnd(210), e.available())
        assertEquals(tnd(-15 + 30), e.forecast(oct14, oct14).single().opening)
    }

    // ---- history (days before today) -----------------------------------------------------------

    @Test fun past_days_show_history_from_accepted_items_only() {
        val rows = listOf(
            tx(d(2026, 10, 11), 10, status = TxStatus.CONFIRMED, id = 1),
            tx(d(2026, 10, 12), 20, id = 2), // pending: not history
            tx(d(2026, 10, 12), 8, status = TxStatus.CONFIRMED, id = 3),
            tx(d(2026, 10, 13), 6, kind = TxKind.INCOME, status = TxStatus.CONFIRMED, id = 4),
            tx(d(2026, 10, 13), 50, status = TxStatus.SKIPPED, id = 5),
        )
        val days = engine(oct14, rows).forecast(start, d(2026, 10, 13))
        assertEquals(listOf(100L, 90L, 82L, 88L).map(::tnd), days.map { it.closing })
    }

    @Test fun history_and_available_agree_on_the_day_before_today_plus_todays_accepted_items() {
        val rows = listOf(tx(d(2026, 10, 12), 10, status = TxStatus.CONFIRMED), tx(oct14, 5, status = TxStatus.CONFIRMED))
        val e = engine(oct14, rows)
        assertEquals(tnd(90), closing(e, d(2026, 10, 13)))
        assertEquals(tnd(85), e.available())
        assertEquals(tnd(85), closing(e, oct14)) // nothing is pending, so the forecast starts right at Available
    }

    @Test fun forecast_closing_today_equals_available_when_nothing_is_pending() {
        val rows = dailyIncome(30, d(2026, 10, 11), d(2026, 10, 12), TxStatus.CONFIRMED) +
            tx(d(2026, 10, 11), 10, status = TxStatus.CONFIRMED)
        val e = engine(d(2026, 10, 12), rows)
        assertEquals(tnd(150), e.available())
        assertEquals(e.available(), closing(e, d(2026, 10, 12)))
    }

    @Test fun an_accepted_item_dated_after_today_takes_effect_on_its_date() {
        val e = engine(d(2026, 10, 12), listOf(tx(d(2026, 10, 15), 40, status = TxStatus.CONFIRMED)))
        assertEquals(tnd(100), e.available())
        assertEquals(tnd(100), closing(e, d(2026, 10, 14)))
        assertEquals(tnd(60), closing(e, d(2026, 10, 15)))
    }

    // ---- nextExpense / negativeDays --------------------------------------------------------

    @Test fun next_expense_is_the_earliest_pending_expense_from_today() {
        val txs = listOf(
            tx(d(2026, 10, 10), 1, id = 1), // overdue, not "next"
            tx(d(2026, 10, 12), 2, status = TxStatus.CONFIRMED, id = 2),
            tx(d(2026, 10, 13), 3, status = TxStatus.SKIPPED, id = 3),
            tx(d(2026, 10, 13), 4, TxKind.INCOME, id = 4),
            tx(d(2026, 10, 20), 5, id = 5, description = "later"),
            tx(d(2026, 10, 15), 6, id = 6, description = "rent"),
        )
        val next = engine(d(2026, 10, 12), txs).nextExpense()!!
        assertEquals(d(2026, 10, 15), next.date)
        assertEquals(3, next.daysAway)
        assertEquals(tnd(6), next.amount)
        assertEquals("rent", next.description)
        assertEquals(6L, next.transactionId)
    }

    @Test fun next_expense_today_is_zero_days_away() {
        assertEquals(0, engine(d(2026, 10, 12), listOf(tx(d(2026, 10, 12), 8))).nextExpense()!!.daysAway)
    }

    @Test fun next_expense_ties_on_a_date_pick_the_lowest_id() {
        val txs = listOf(tx(d(2026, 10, 15), 8, id = 9, description = "b"), tx(d(2026, 10, 15), 9, id = 4, description = "a"))
        assertEquals("a", engine(d(2026, 10, 12), txs).nextExpense()!!.description)
    }

    @Test fun no_upcoming_expenses() {
        assertNull(engine(d(2026, 10, 12)).nextExpense())
        assertNull(engine(d(2026, 10, 12), listOf(tx(d(2026, 10, 11), 5))).nextExpense())
    }

    @Test fun negative_days_are_days_with_negative_closing_from_today() {
        val rows = income30(d(2026, 10, 20))
        assertTrue(engine(start, rows + tx(d(2026, 10, 12), 150)).negativeDays().isEmpty())
        // 100, 130, -90, -60, -30, 0, 30
        val e2 = engine(start, rows + tx(d(2026, 10, 12), 250))
        assertEquals(listOf(d(2026, 10, 12), d(2026, 10, 13), d(2026, 10, 14)), e2.negativeDays())
    }

    @Test fun negative_days_only_look_forward_from_today_by_default() {
        // The 250 expense is overdue and pending, so it is assumed to be paid today: 100 - 250 = -150 today.
        val e = engine(d(2026, 10, 14), listOf(tx(d(2026, 10, 12), 250)))
        assertEquals(d(2026, 10, 14), e.negativeDays(toDate = d(2026, 10, 16)).first())
    }
}
