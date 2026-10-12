package com.cashflow.app.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class TxKind { EXPENSE, INCOME }
enum class TxStatus { PENDING, CONFIRMED, SKIPPED }
enum class TxSource { MANUAL, RECURRING }

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
    /** false = immediate expense, created already CONFIRMED. */
    val isPlanned: Boolean = true,
) {
    init {
        require(amount > 0) { "amount must be positive, was $amount" }
    }
}

sealed interface RecurrencePattern {
    data object Daily : RecurrencePattern

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
    /** Also the lower bound for generation: editing/enabling moves it to today so nothing is backfilled. */
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val enabled: Boolean = true,
    /** Stored only; used by the notifications step. */
    val confirmTime: LocalTime = LocalTime.NOON,
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
    /**
     * When true, income that comes from a repeating income series is accepted by itself on its day (after
     * [reminderTime]); see [AutoAccept]. One-off income and all expenses always wait for the user.
     */
    val autoAcceptIncome: Boolean = false,
    /** The "Remind me at" time; also the time of day from which income is auto-accepted. */
    val reminderTime: LocalTime = LocalTime.NOON,
) {
    companion object {
        fun default(today: LocalDate) = AppSettings(startingBalance = 0, balanceStartDate = today)
    }
}

/**
 * One day of the balance model. For days before today it is history (start + accepted items up to that day).
 * From today on it is a forecast: today's Available, minus overdue pending expenses, plus every pending item on
 * its date.
 */
data class DayForecast(
    val date: LocalDate,
    /**
     * Balance before this day's own pending items: the previous day's closing; for today, Available minus
     * the overdue pending expenses that are assumed to be paid today.
     */
    val opening: Long,
    /** Non-skipped income dated this day (accepted or pending). */
    val income: Long,
    /** Non-skipped expenses dated this day (accepted or pending). */
    val expenses: Long,
    /** The balance at the end of the day under the rules above. */
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
