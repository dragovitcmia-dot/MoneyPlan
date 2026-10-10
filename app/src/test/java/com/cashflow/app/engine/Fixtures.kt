package com.cashflow.app.engine

import java.time.LocalDate

fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)
fun tnd(v: Long): Long = v * 1000

fun settings(start: LocalDate, balance: Long = 0, income: Long = 0) =
    AppSettings(startingBalance = tnd(balance), balanceStartDate = start, dailyIncomeAmount = tnd(income))

fun tx(
    date: LocalDate,
    amount: Long,
    kind: TxKind = TxKind.EXPENSE,
    status: TxStatus = TxStatus.PENDING,
    source: TxSource = TxSource.MANUAL,
    id: Long = 0,
    description: String = "",
    autoApplied: Boolean = false,
) = Transaction(
    id = id, date = date, amount = tnd(amount), kind = kind, description = description,
    status = status, source = source, autoApplied = autoApplied,
)

fun rule(
    pattern: RecurrencePattern,
    start: LocalDate,
    end: LocalDate? = null,
    amount: Long = 10,
    id: Long = 1,
) = RecurringRule(
    id = id, amount = tnd(amount), kind = TxKind.EXPENSE, description = "rule",
    pattern = pattern, startDate = start, endDate = end,
)
