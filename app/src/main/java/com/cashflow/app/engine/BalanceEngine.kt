package com.cashflow.app.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure balance model of the user's spreadsheet. One instance is an immutable snapshot of
 * (settings, transactions, today); create a new one when any of them changes.
 *
 * Day semantics: the starting balance is the opening balance of balanceStartDate. Daily income is
 * first added on the next day: opening = previous closing + income; closing = opening + one-off
 * income - expenses. SKIPPED rows have no effect anywhere.
 */
class BalanceEngine(
    val settings: AppSettings,
    transactions: List<Transaction>,
    val today: LocalDate,
) {
    private val start = settings.balanceStartDate
    private val dailyRows: Map<LocalDate, Transaction> =
        transactions.filter { it.source == TxSource.DAILY_INCOME }.groupBy { it.date }.mapValues { it.value.minBy { t -> t.id } }
    private val others: List<Transaction> = transactions.filter { it.source != TxSource.DAILY_INCOME }
    private val othersByDate: Map<LocalDate, List<Transaction>> = others.groupBy { it.date }

    /** Confirmed money only, up to and including today (daily income also when auto-applied). */
    fun currentBalance(): Long {
        var balance = settings.startingBalance
        for (tx in others) {
            if (tx.date < start || tx.date > today || tx.status != TxStatus.CONFIRMED) continue
            balance += signed(tx)
        }
        for ((date, row) in dailyRows) {
            if (date <= start || date > today || !countsAsReceived(row)) continue
            balance += row.amount
        }
        return balance
    }

    /**
     * Balance if every pending item dated today or earlier were settled as planned (pending
     * expenses spent, pending incomes received). Equals the forecast closing balance of today.
     */
    fun balanceAfterPending(): Long = simulate(today).lastOrNull()?.closing ?: settings.startingBalance

    /** Day-by-day forecast; days before balanceStartDate are not part of the ledger. */
    fun forecast(fromDate: LocalDate, toDate: LocalDate): List<DayForecast> =
        simulate(toDate).filter { it.date >= fromDate }

    fun negativeDays(
        fromDate: LocalDate = today,
        toDate: LocalDate = today.plusDays(Recurrence.HORIZON_DAYS),
    ): List<LocalDate> = forecast(fromDate, toDate).filter { it.closing < 0 }.map { it.date }

    /** Earliest PENDING expense dated today or later (ties: lowest id). */
    fun nextExpense(): NextExpense? {
        val tx = others
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

    private fun countsAsReceived(row: Transaction) =
        row.status == TxStatus.CONFIRMED || (row.autoApplied && row.status != TxStatus.SKIPPED)

    /** Daily income on [date]: its stored row if there is one, otherwise the planned amount. */
    private fun dailyIncomeOn(date: LocalDate): Long {
        if (date <= start) return 0
        val row = dailyRows[date] ?: return settings.dailyIncomeAmount
        return if (row.status == TxStatus.SKIPPED) 0 else row.amount
    }

    private fun simulate(to: LocalDate): List<DayForecast> {
        if (to < start) return emptyList()
        val days = ArrayList<DayForecast>()
        var previousClosing = settings.startingBalance
        var date = start
        while (!date.isAfter(to)) {
            val income = dailyIncomeOn(date)
            val opening = previousClosing + income
            val items = othersByDate[date].orEmpty() + listOfNotNull(dailyRows[date].takeIf { date > start })
            var extra = 0L
            var expenses = 0L
            for (tx in othersByDate[date].orEmpty()) {
                if (tx.status == TxStatus.SKIPPED) continue
                if (tx.kind == TxKind.INCOME) extra += tx.amount else expenses += tx.amount
            }
            val closing = opening + extra - expenses
            days += DayForecast(date, opening, income, extra, expenses, closing, items)
            previousClosing = closing
            date = date.plusDays(1)
        }
        return days
    }
}
