package com.cashflow.app.engine

import java.time.LocalDate

data class WhatIfResult(
    /** True when the change makes at least one day newly negative or makes a negative day worse. */
    val wouldCauseNegative: Boolean,
    /** Days negative with the change that were not negative before, or whose balance got worse. */
    val newNegativeDays: List<LocalDate>,
    /** First day that closes negative with the change (whether or not it was negative before). */
    val firstNegativeDate: LocalDate?,
    val lowestBalance: Long,
    val lowestBalanceDate: LocalDate?,
    /** First planned PENDING expense (existing or new) whose day closes negative with the change. */
    val firstNegativePlannedExpense: Transaction?,
)

/**
 * "What if I save this?": re-runs the forecast with [newTransactions] and/or the occurrences of
 * [newRule] added, over today .. today + [horizonDays], and compares with the current forecast.
 * Pure: the engine itself is not modified.
 */
fun BalanceEngine.whatIf(
    newTransactions: List<Transaction> = emptyList(),
    newRule: RecurringRule? = null,
    horizonDays: Int = 180,
): WhatIfResult {
    val end = today.plusDays(horizonDays.toLong())
    val before = forecast(today, end).associate { it.date to it.closing }

    val ruleRows = if (newRule != null && RuleMaintenance.isActive(newRule)) {
        RuleMaintenance.materialize(newRule, emptyList(), today).filter { !it.date.isAfter(end) }
    } else {
        emptyList()
    }
    val changed = BalanceEngine(settings, transactions + newTransactions + ruleRows, today)
    val after = changed.forecast(today, end)
    val afterClosing = after.associate { it.date to it.closing }

    val newNegativeDays = after.filter { day ->
        day.closing < 0 && (before[day.date]?.let { it >= 0 || day.closing < it } ?: true)
    }.map { it.date }
    val lowest = after.minWithOrNull(compareBy<DayForecast> { it.closing }.thenBy { it.date })
    val breaking = changed.transactions
        .filter {
            it.kind == TxKind.EXPENSE && it.status == TxStatus.PENDING && it.isPlanned &&
                !it.date.isBefore(today) && !it.date.isAfter(end) && (afterClosing[it.date] ?: 0) < 0
        }
        .minWithOrNull(compareBy<Transaction> { it.date }.thenBy { it.id })

    return WhatIfResult(
        wouldCauseNegative = newNegativeDays.isNotEmpty(),
        newNegativeDays = newNegativeDays,
        firstNegativeDate = after.firstOrNull { it.closing < 0 }?.date,
        lowestBalance = lowest?.closing ?: settings.startingBalance,
        lowestBalanceDate = lowest?.date,
        firstNegativePlannedExpense = breaking,
    )
}
