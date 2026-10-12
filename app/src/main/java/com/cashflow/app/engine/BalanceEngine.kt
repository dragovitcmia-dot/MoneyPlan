package com.cashflow.app.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure balance model. One instance is an immutable snapshot of (settings, transactions, today); create a new
 * one when any of them changes.
 *
 * The core rule: nothing is added or deducted unless the user accepts it.
 *  - **Available** = starting balance + accepted (CONFIRMED) income - accepted expenses dated up to today.
 *    PENDING and SKIPPED rows never change it, overdue or not.
 *  - **History** (days before today): start + accepted items dated up to and including that day.
 *  - **Forecast** (today and later): today's opening is Available minus the PENDING expenses dated before today
 *    (assumed still to be paid, so they count as due today); overdue PENDING income is NOT counted. Then every
 *    PENDING item (income and expenses) is applied on its own date. Accepted items dated after today (which
 *    can only come from moving an accepted item) are applied on their date too.
 *
 * Rows dated before [AppSettings.balanceStartDate] are outside the ledger and ignored everywhere.
 */
class BalanceEngine(
    val settings: AppSettings,
    val transactions: List<Transaction>,
    val today: LocalDate,
) {
    private val start = settings.balanceStartDate
    private val ledger: List<Transaction> = this.transactions.filter { it.date >= start }
    private val byDate: Map<LocalDate, List<Transaction>> = ledger.groupBy { it.date }

    /** Accepted money only, up to and including today. */
    fun available(): Long {
        var balance = settings.startingBalance
        for (tx in ledger) {
            if (tx.status == TxStatus.CONFIRMED && tx.date <= today) balance += signed(tx)
        }
        return balance
    }

    /** PENDING items dated today or earlier, oldest first: what the user can accept or reject. */
    fun toConfirm(): List<Transaction> =
        ledger.filter { it.status == TxStatus.PENDING && it.date <= today }
            .sortedWith(compareBy<Transaction> { it.date }.thenBy { it.id })

    fun toConfirmCount(): Int = toConfirm().size

    /** Day-by-day history and forecast; days before balanceStartDate are not part of the ledger. */
    fun forecast(fromDate: LocalDate, toDate: LocalDate): List<DayForecast> =
        simulate(toDate).filter { it.date >= fromDate }

    fun negativeDays(
        fromDate: LocalDate = today,
        toDate: LocalDate = today.plusDays(Recurrence.HORIZON_DAYS),
    ): List<LocalDate> = forecast(fromDate, toDate).filter { it.closing < 0 }.map { it.date }

    /** Earliest PENDING expense dated today or later (ties: lowest id). */
    fun nextExpense(): NextExpense? {
        val tx = ledger
            .filter { it.kind == TxKind.EXPENSE && it.status == TxStatus.PENDING && it.date >= today }
            .minWithOrNull(compareBy<Transaction> { it.date }.thenBy { it.id }) ?: return null
        return NextExpense(
            date = tx.date,
            daysAway = ChronoUnit.DAYS.between(today, tx.date).toInt(),
            amount = tx.amount,
            description = tx.description,
            transactionId = tx.id,
        )
    }

    private fun signed(tx: Transaction) = if (tx.kind == TxKind.INCOME) tx.amount else -tx.amount

    private fun simulate(to: LocalDate): List<DayForecast> {
        if (to < start) return emptyList()
        val days = ArrayList<DayForecast>()
        var balance = settings.startingBalance // closing of the previous day
        var date = start
        while (!date.isAfter(to)) {
            val items = byDate[date].orEmpty()
            var income = 0L
            var expenses = 0L
            var accepted = 0L
            var pending = 0L
            for (tx in items) {
                if (tx.status == TxStatus.SKIPPED) continue
                if (tx.kind == TxKind.INCOME) income += tx.amount else expenses += tx.amount
                if (tx.status == TxStatus.CONFIRMED) accepted += signed(tx) else pending += signed(tx)
            }
            val opening: Long
            val closing: Long
            when {
                date.isBefore(today) -> { // history: accepted items only
                    opening = balance
                    closing = opening + accepted
                }
                date == today -> { // Available (accepted items up to today) minus what is overdue and still to be paid
                    opening = balance + accepted - overdueExpenses()
                    closing = opening + pending
                }
                else -> { // forecast
                    opening = balance
                    closing = opening + pending + accepted
                }
            }
            days += DayForecast(date, opening, income, expenses, closing, items)
            balance = closing
            date = date.plusDays(1)
        }
        return days
    }

    private val overdue: Long by lazy {
        ledger.filter { it.kind == TxKind.EXPENSE && it.status == TxStatus.PENDING && it.date < today }.sumOf { it.amount }
    }

    private fun overdueExpenses() = overdue
}
