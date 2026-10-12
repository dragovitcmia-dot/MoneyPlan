package com.cashflow.app.engine

import java.time.LocalDate

/** The one suggestion of the Postpone sheet (the date picker covers everything else). */
enum class PostponeChoice { TOMORROW, IN_3_DAYS, IN_1_WEEK, NEXT_WEEK }

/**
 * Pure rules for "Postpone": moving ONE waiting item to a later day. Only the item's effective [Transaction.date]
 * changes. Its [Transaction.occurrenceDate] and rule link stay as they are, so a postponed occurrence of a
 * repeating payment is still recognised as already generated (it is never created again) and the other
 * occurrences of the series are not touched.
 */
object Postpone {
    fun dateFor(choice: PostponeChoice, today: LocalDate): LocalDate = when (choice) {
        PostponeChoice.TOMORROW -> today.plusDays(1)
        PostponeChoice.IN_3_DAYS -> today.plusDays(3)
        PostponeChoice.IN_1_WEEK, PostponeChoice.NEXT_WEEK -> today.plusDays(7)
    }

    /** Only a waiting (PENDING) item can be postponed. */
    fun canPostpone(tx: Transaction): Boolean = tx.status == TxStatus.PENDING

    /** A new date must lie after the item's current date: that is what "postpone" means. */
    fun isValidDate(tx: Transaction, newDate: LocalDate): Boolean = canPostpone(tx) && newDate.isAfter(tx.date)

    /**
     * The single suggestion for an item, from its nature: a daily payment moves to tomorrow, a weekly or every-2-weeks
     * one in 3 days, a monthly or yearly one in 1 week, a one-time item (no repeating pattern) next week.
     */
    fun suggestionFor(pattern: RecurrencePattern?): PostponeChoice = when (pattern) {
        null -> PostponeChoice.NEXT_WEEK
        RecurrencePattern.Daily -> PostponeChoice.TOMORROW
        is RecurrencePattern.Weekly, is RecurrencePattern.WeeklyDays -> PostponeChoice.IN_3_DAYS
        is RecurrencePattern.EveryNDays -> if (pattern.n <= 14) PostponeChoice.IN_3_DAYS else PostponeChoice.IN_1_WEEK
        RecurrencePattern.MonthFirst, RecurrencePattern.MonthLast, is RecurrencePattern.DayOfMonth, is RecurrencePattern.Yearly -> PostponeChoice.IN_1_WEEK
    }

    /**
     * The suggested new date: the suggestion counted from today, or from the item's own date when that is later, so
     * the suggestion always moves the item to a LATER day. Null when the item cannot be postponed.
     */
    fun suggestedDate(tx: Transaction, pattern: RecurrencePattern?, today: LocalDate): LocalDate? {
        if (!canPostpone(tx)) return null
        return dateFor(suggestionFor(pattern), maxOf(today, tx.date))
    }

    /** The earliest date the picker should offer: the day after the item's current date. */
    fun earliestDate(tx: Transaction): LocalDate = tx.date.plusDays(1)

    /** The postponed copy of [tx], or null when the move is not allowed (not waiting, or not a later day). */
    fun apply(tx: Transaction, newDate: LocalDate): Transaction? =
        if (isValidDate(tx, newDate)) tx.copy(date = newDate) else null
}

/**
 * Holds the item that waits for the user's "Are you sure?" before it is rejected. A swipe or a button first
 * [request]s the rejection; only [confirm] hands the item back to be rejected.
 */
data class ConfirmGate<T : Any>(val pending: T? = null) {
    fun request(item: T): ConfirmGate<T> = ConfirmGate(item)

    fun cancel(): ConfirmGate<T> = ConfirmGate(null)

    /** The item to reject now (null when nothing was requested) and the cleared gate. */
    fun confirm(): Pair<ConfirmGate<T>, T?> = ConfirmGate<T>(null) to pending
}
