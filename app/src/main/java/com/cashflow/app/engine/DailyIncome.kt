package com.cashflow.app.engine

import java.time.LocalDate

data class CatchUpPlan(val inserts: List<Transaction>, val updates: List<Transaction>) {
    val isEmpty get() = inserts.isEmpty() && updates.isEmpty()
}

/**
 * Daily income rows (source DAILY_INCOME) exist for every day after the balance start date up to
 * today. A day that has ended without an answer is auto-applied; today's row stays PENDING.
 * Only DAILY_INCOME rows are ever touched: pending expenses stay pending and keep their date.
 */
object DailyIncome {
    fun catchUp(settings: AppSettings, dailyIncomeRows: List<Transaction>, today: LocalDate): CatchUpPlan {
        val amount = settings.dailyIncomeAmount
        val byDate = dailyIncomeRows.groupBy { it.date }
        val inserts = ArrayList<Transaction>()
        val updates = ArrayList<Transaction>()
        var day = settings.balanceStartDate.plusDays(1)
        while (!day.isAfter(today)) {
            val row = byDate[day]?.firstOrNull()
            val ended = day.isBefore(today)
            if (row == null) {
                if (amount > 0) inserts += newRow(day, amount, if (ended) TxStatus.CONFIRMED else TxStatus.PENDING, ended)
            } else if (ended && row.status == TxStatus.PENDING) {
                updates += row.copy(status = TxStatus.CONFIRMED, autoApplied = true)
            }
            day = day.plusDays(1)
        }
        return CatchUpPlan(inserts, updates)
    }

    private fun newRow(date: LocalDate, amount: Long, status: TxStatus, autoApplied: Boolean) = Transaction(
        date = date,
        amount = amount,
        kind = TxKind.INCOME,
        description = "Daily income",
        status = status,
        source = TxSource.DAILY_INCOME,
        autoApplied = autoApplied,
        isPlanned = true,
    )
}
