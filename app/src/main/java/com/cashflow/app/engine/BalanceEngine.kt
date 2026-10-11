package com.cashflow.app.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure balance model of the user's spreadsheet. One instance is an immutable snapshot of
 * (settings, transactions, today); create a new one when any of them changes.
 *
 * Day semantics: the starting balance is the opening balance of balanceStartDate. Income-source
 * income is added to the day's opening; closing = opening + other income - expenses. SKIPPED rows
 * have no effect anywhere. Nothing is ever confirmed automatically: PENDING rows stay PENDING until
 * the user answers, and only CONFIRMED rows count toward [currentBalance].
 *
 * In [WalletMode.STATIC] pending income-source rows are ignored everywhere.
 */
class BalanceEngine(
    val settings: AppSettings,
    transactions: List<Transaction>,
    val today: LocalDate,
) {
    private val start = settings.balanceStartDate

    private fun isSourceIncome(tx: Transaction) = tx.source == TxSource.RECURRING && tx.kind == TxKind.INCOME

    private val ledger: List<Transaction> = transactions.filter {
        !(settings.walletMode == WalletMode.STATIC && isSourceIncome(it) && it.status == TxStatus.PENDING)
    }
    private val byDate: Map<LocalDate, List<Transaction>> = ledger.groupBy { it.date }

    /** Confirmed money only, up to and including today. */
    fun currentBalance(): Long {
        var balance = settings.startingBalance
        for (tx in ledger) {
            if (tx.status != TxStatus.CONFIRMED || tx.date < start || tx.date > today) continue
            balance += signed(tx)
        }
        return balance
    }

    /**
     * The headline figure: balance if every PENDING item dated today or earlier were settled as
     * planned (pending expenses spent, pending incomes received). Equals today's forecast closing.
     */
    fun available(): Long = simulate(today).lastOrNull()?.closing ?: settings.startingBalance

    /** PENDING items dated today or earlier, oldest first: what the user still has to answer. */
    fun toConfirm(): List<Transaction> =
        ledger.filter { it.status == TxStatus.PENDING && it.date >= start && it.date <= today }
            .sortedWith(compareBy<Transaction> { it.date }.thenBy { it.id })

    fun toConfirmCount(): Int = toConfirm().size

    /** Day-by-day forecast; days before balanceStartDate are not part of the ledger. */
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
        var previousClosing = settings.startingBalance
        var date = start
        while (!date.isAfter(to)) {
            val items = byDate[date].orEmpty()
            var income = 0L
            var extra = 0L
            var expenses = 0L
            for (tx in items) {
                if (tx.status == TxStatus.SKIPPED) continue
                when {
                    tx.kind == TxKind.EXPENSE -> expenses += tx.amount
                    isSourceIncome(tx) -> income += tx.amount
                    else -> extra += tx.amount
                }
            }
            val opening = previousClosing + income
            val closing = opening + extra - expenses
            days += DayForecast(date, opening, income, extra, expenses, closing, items)
            previousClosing = closing
            date = date.plusDays(1)
        }
        return days
    }
}
