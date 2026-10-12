package com.cashflow.app.debug

import com.cashflow.app.engine.AppSettings
import com.cashflow.app.engine.RecurrencePattern
import com.cashflow.app.engine.RecurringRule
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import com.cashflow.app.engine.TxSource
import com.cashflow.app.engine.TxStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * The reference scenario from the user's spreadsheet. Pure data, shared by the debug
 * "Load sample data" action and the unit tests.
 *
 * Note: the sheet lists "Oct 11 75" and also "75 every Sunday from Oct 11". Oct 11, 2026 is a
 * Sunday and the sheet's closing balances only work if that is ONE expense, so Oct 11 is not
 * listed again among the one-offs; it comes from [sundayRule].
 */
object ReferenceScenario {
    val startDate: LocalDate = LocalDate.of(2026, 10, 10)

    val settings = AppSettings(
        startingBalance = 180_000,
        balanceStartDate = startDate,
        reminderTime = LocalTime.NOON,
    )

    /** One DAILY income source of 30; it applies from the day after [startDate]. */
    val dailyIncomeSource = RecurringRule(
        id = 2,
        amount = 30_000,
        kind = TxKind.INCOME,
        description = "Daily income",
        pattern = RecurrencePattern.Daily,
        startDate = startDate.plusDays(1),
        enabled = true,
        confirmTime = LocalTime.NOON,
    )

    /** (month, day, TND) of every non-Sunday-rule expense. */
    private val oneOffs = listOf(
        Triple(10, 12, 128), Triple(10, 13, 67), Triple(10, 14, 30), Triple(10, 15, 20),
        Triple(10, 16, 20), Triple(10, 24, 75), Triple(10, 31, 25), Triple(11, 10, 380),
        Triple(11, 12, 63), Triple(11, 28, 220), Triple(11, 30, 25), Triple(12, 12, 63),
        Triple(12, 31, 25),
    )

    /**
     * A second repeating INCOME series with an end date, for the Settings screenshots. It starts after the
     * scenario's last day (Dec 31, 2026), so it changes none of the scenario's closings.
     */
    val endingIncomeSource = RecurringRule(
        id = 3,
        amount = 100_000,
        kind = TxKind.INCOME,
        description = "Freelance income",
        pattern = RecurrencePattern.MonthFirst,
        startDate = LocalDate.of(2027, 1, 1),
        endDate = LocalDate.of(2027, 6, 30),
        enabled = true,
        confirmTime = LocalTime.NOON,
    )

    /** Oct 11 as a separate one-off: only used to show that listing it twice breaks the sheet. */
    val oct11OneOff = oneOff(LocalDate.of(2026, 10, 11), 75)

    val oneOffExpenses: List<Transaction> =
        oneOffs.map { (m, d, tnd) -> oneOff(LocalDate.of(2026, m, d), tnd.toLong()) }

    val sundayRule = RecurringRule(
        id = 1,
        amount = 75_000,
        kind = TxKind.EXPENSE,
        description = "Sunday expense",
        pattern = RecurrencePattern.Weekly(DayOfWeek.SUNDAY),
        startDate = LocalDate.of(2026, 10, 11),
        endDate = LocalDate.of(2026, 12, 27),
    )

    private fun oneOff(date: LocalDate, tnd: Long) = Transaction(
        date = date,
        amount = tnd * 1000,
        kind = TxKind.EXPENSE,
        description = "Expense ${date.monthValue}/${date.dayOfMonth}",
        status = TxStatus.PENDING,
        source = TxSource.MANUAL,
        isPlanned = true,
    )
}
