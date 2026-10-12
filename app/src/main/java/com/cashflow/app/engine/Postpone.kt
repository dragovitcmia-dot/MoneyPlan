package com.cashflow.app.engine

import java.time.LocalDate

/** The quick choices of the Postpone menu (a date picker covers everything else). */
enum class PostponeChoice { TOMORROW, IN_3_DAYS, NEXT_WEEK }

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
        PostponeChoice.NEXT_WEEK -> today.plusDays(7)
    }

    /** Only a waiting (PENDING) item can be postponed. */
    fun canPostpone(tx: Transaction): Boolean = tx.status == TxStatus.PENDING

    /** A new date must lie after the item's current date: that is what "postpone" means. */
    fun isValidDate(tx: Transaction, newDate: LocalDate): Boolean = canPostpone(tx) && newDate.isAfter(tx.date)

    /** The quick choices that really move [tx] later (e.g. "Tomorrow" is not offered for an item already dated later). */
    fun choices(tx: Transaction, today: LocalDate): List<PostponeChoice> =
        if (!canPostpone(tx)) emptyList() else PostponeChoice.entries.filter { isValidDate(tx, dateFor(it, today)) }

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
