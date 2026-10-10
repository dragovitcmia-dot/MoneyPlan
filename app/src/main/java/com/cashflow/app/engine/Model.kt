package com.cashflow.app.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class TxKind { EXPENSE, INCOME }
enum class TxStatus { PENDING, CONFIRMED, SKIPPED }
enum class TxSource { MANUAL, RECURRING, DAILY_INCOME }

/** All amounts are Long millimes (1 TND = 1000). */
data class Transaction(
    val id: Long = 0,
    val date: LocalDate,
    val amount: Long,
    val kind: TxKind,
    val description: String = "",
    val status: TxStatus,
    val source: TxSource,
    val recurringRuleId: Long? = null,
    /**
     * For RECURRING rows: the rule date this row was generated for. It never changes when the
     * row's [date] is edited, so a moved occurrence is not regenerated.
     */
    val occurrenceDate: LocalDate? = null,
    val autoApplied: Boolean = false,
    /** false = immediate expense, created already CONFIRMED. */
    val isPlanned: Boolean = true,
) {
    init {
        require(amount > 0) { "amount must be positive, was $amount" }
    }
}

sealed interface RecurrencePattern {
    data class Weekly(val dayOfWeek: DayOfWeek) : RecurrencePattern

    /** Every [n] days, in phase with [anchorDate] (which may lie before the rule's startDate). */
    data class EveryNDays(val n: Int, val anchorDate: LocalDate) : RecurrencePattern {
        init {
            require(n >= 1) { "n must be >= 1" }
        }
    }

    data object MonthFirst : RecurrencePattern
    data object MonthLast : RecurrencePattern

    /** Clamps to the last day of shorter months (31 in February = Feb 28/29). */
    data class DayOfMonth(val day: Int) : RecurrencePattern {
        init {
            require(day in 1..31) { "day must be 1..31" }
        }
    }
}

data class RecurringRule(
    val id: Long = 0,
    val amount: Long,
    val kind: TxKind,
    val description: String,
    val pattern: RecurrencePattern,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
) {
    init {
        require(amount > 0) { "amount must be positive, was $amount" }
    }

    /** The PENDING row this rule materializes for [date]. */
    fun occurrenceFor(date: LocalDate): Transaction = Transaction(
        date = date,
        amount = amount,
        kind = kind,
        description = description,
        status = TxStatus.PENDING,
        source = TxSource.RECURRING,
        recurringRuleId = id,
        occurrenceDate = date,
        isPlanned = true,
    )
}

data class AppSettings(
    val startingBalance: Long,
    val balanceStartDate: LocalDate,
    val dailyIncomeAmount: Long,
    val dailyIncomeTime: LocalTime = LocalTime.NOON,
    val reminderTime: LocalTime = LocalTime.NOON,
) {
    init {
        require(dailyIncomeAmount >= 0) { "dailyIncomeAmount must be >= 0" }
    }

    companion object {
        fun default(today: LocalDate) = AppSettings(
            startingBalance = 0,
            balanceStartDate = today,
            dailyIncomeAmount = 0,
        )
    }
}

data class DayForecast(
    val date: LocalDate,
    /** Balance at the start of the day, daily income already added (except on the start day). */
    val opening: Long,
    /** Daily income credited into [opening] (0 on the start day). */
    val income: Long,
    /** One-off income dated this day, credited after [opening]. */
    val extraIncome: Long,
    val expenses: Long,
    /** opening + extraIncome - expenses. */
    val closing: Long,
    /** Every stored row dated this day, SKIPPED ones included; only non-skipped ones are summed. */
    val items: List<Transaction>,
)

data class NextExpense(
    val date: LocalDate,
    val daysAway: Int,
    val amount: Long,
    val description: String,
    val transactionId: Long,
)
