package com.cashflow.app.engine

import java.time.LocalDate

fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)
fun tnd(v: Long): Long = v * 1000

fun settings(start: LocalDate, balance: Long = 0, autoAccept: Boolean = false) =
    AppSettings(startingBalance = tnd(balance), balanceStartDate = start, autoAcceptIncome = autoAccept)

fun tx(
    date: LocalDate,
    amount: Long,
    kind: TxKind = TxKind.EXPENSE,
    status: TxStatus = TxStatus.PENDING,
    source: TxSource = TxSource.MANUAL,
    id: Long = 0,
    description: String = "",
) = Transaction(
    id = id, date = date, amount = tnd(amount), kind = kind, description = description,
    status = status, source = source,
)

/** One income-source row per day in [from, to], as an income source would materialize them. */
fun dailyIncome(
    amount: Long,
    from: LocalDate,
    to: LocalDate,
    status: TxStatus = TxStatus.PENDING,
    ruleId: Long = 2,
): List<Transaction> = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map {
    Transaction(
        date = it, amount = tnd(amount), kind = TxKind.INCOME, description = "Daily income",
        status = status, source = TxSource.RECURRING, recurringRuleId = ruleId, occurrenceDate = it,
    )
}.toList()

fun rule(
    pattern: RecurrencePattern,
    start: LocalDate,
    end: LocalDate? = null,
    amount: Long = 10,
    id: Long = 1,
    kind: TxKind = TxKind.EXPENSE,
    enabled: Boolean = true,
) = RecurringRule(
    id = id, amount = tnd(amount), kind = kind, description = "rule",
    pattern = pattern, startDate = start, endDate = end, enabled = enabled,
)

fun incomeSource(
    pattern: RecurrencePattern = RecurrencePattern.Daily,
    start: LocalDate,
    amount: Long = 30,
    id: Long = 2,
    enabled: Boolean = true,
) = rule(pattern, start, amount = amount, id = id, kind = TxKind.INCOME, enabled = enabled)
