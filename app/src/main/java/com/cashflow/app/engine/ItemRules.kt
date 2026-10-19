package com.cashflow.app.engine

import java.time.LocalDate

/** What the one item sheet offers for one item. [history] means the sheet shows only the plain "cannot be changed" line. */
data class ItemActionSet(
    val accept: Boolean,
    val change: Boolean,
    val moveDate: Boolean,
    val markWaiting: Boolean,
    /** The "Repeating payment" menu (Edit series, Extend, End series, Delete series). */
    val series: Boolean,
    val history: Boolean,
) {
    val none get() = !accept && !change && !moveDate && !markWaiting && !series
}

/**
 * Pure rules of the item sheet, Change and Move date, reconciled with the permanent-history rule (HistoryGuard):
 *  - an accepted (CONFIRMED) item, and a rejected one dated before today, is history: no action at all;
 *  - a WAITING item that is overdue is NOT history yet: it stays until the user answers it, so it can be accepted and moved to today or later;
 *  - a rejected (SKIPPED) item dated today or later can be put back to waiting;
 *  - a waiting item dated today or later can be changed and moved; one that is due (today or earlier) can also be accepted.
 */
object ItemRules {
    fun actionsFor(tx: Transaction, today: LocalDate): ItemActionSet {
        val series = tx.recurringRuleId != null
        return when (tx.status) {
            TxStatus.CONFIRMED -> HISTORY
            TxStatus.SKIPPED ->
                if (tx.date.isBefore(today)) HISTORY else ItemActionSet(accept = false, change = false, moveDate = false, markWaiting = true, series = series, history = false)
            TxStatus.PENDING ->
                ItemActionSet(accept = !tx.date.isAfter(today), change = true, moveDate = true, markWaiting = false, series = series, history = false)
        }
    }

    private val HISTORY = ItemActionSet(accept = false, change = false, moveDate = false, markWaiting = false, series = false, history = true)

    /** The earliest day any picker that moves or edits a waiting item offers: today, whatever the item's current date. */
    fun earliestDate(today: LocalDate): LocalDate = today

    /** A day the picker lets the user choose: today or later. */
    fun isSelectable(day: LocalDate, today: LocalDate): Boolean = !day.isBefore(earliestDate(today))

    /** Moving a waiting item to [newDate]: today or later, and a different day than it has now. */
    fun canMoveTo(tx: Transaction, newDate: LocalDate, today: LocalDate): Boolean =
        actionsFor(tx, today).moveDate && isSelectable(newDate, today) && newDate != tx.date

    /** The moved copy of [tx] (only its date changes; occurrenceDate and the series link stay), or null when the move is not allowed. */
    fun applyMove(tx: Transaction, newDate: LocalDate, today: LocalDate): Transaction? =
        if (canMoveTo(tx, newDate, today)) tx.copy(date = newDate) else null

    /**
     * Saving a changed amount, name or date of the STORED row: the row must be changeable, and a changed date must be today or later
     * (keeping an overdue item's own date is fine).
     */
    fun canSaveChange(stored: Transaction, newDate: LocalDate, today: LocalDate): Boolean =
        actionsFor(stored, today).change && (newDate == stored.date || isSelectable(newDate, today))

    /** "TUE 13 OCT 2026 · Waiting · Every Sunday until 25 DEC 2026": plain text, no chips, no explanation. */
    fun meta(tx: Transaction, rule: RecurringRule?): String {
        val parts = mutableListOf(AppDates.withWeekday(tx.date))
        parts += when (tx.status) {
            TxStatus.PENDING -> "Waiting"
            TxStatus.CONFIRMED -> "Done"
            TxStatus.SKIPPED -> "Skipped"
        }
        if (rule != null) {
            val every = SeriesText.schedule(rule.pattern)
            parts += rule.endDate?.let { "$every until ${AppDates.format(it)}" } ?: every
        }
        return parts.joinToString(" · ")
    }

    /** The subtitle of a list row: "WED 14 OCT 2026 · in 3 days · Every day". A repeating item names its schedule, nothing else is special. */
    fun rowSubtitle(date: LocalDate, relative: String, rule: RecurringRule?): String =
        listOfNotNull(AppDates.withWeekday(date), relative, rule?.let { SeriesText.schedule(it.pattern) }).joinToString(" · ")

    /** The caption of the Move date picker: only for an item of a series. */
    fun pickerCaption(tx: Transaction): String? = if (tx.recurringRuleId != null) "Only this day moves" else null

    const val HISTORY_LINE = "This is history and cannot be changed"
}
