package com.cashflow.app.engine

import java.time.LocalDate

/** What the user entered in the Add expense / Add income composer. */
sealed interface NewEntry {
    val kind: TxKind
    val amount: Long
    val description: String

    /**
     * Already happened: stored as a CONFIRMED immediate transaction dated today, or dated [date] when
     * it is entered for a past day of the calendar.
     */
    data class Now(
        override val kind: TxKind,
        override val amount: Long,
        override val description: String,
        val date: LocalDate? = null,
    ) : NewEntry

    /** One-time, planned for [date] (today or earlier will show up in "To confirm"). */
    data class Planned(
        override val kind: TxKind,
        override val amount: Long,
        override val description: String,
        val date: LocalDate,
    ) : NewEntry

    data class Recurring(
        override val kind: TxKind,
        override val amount: Long,
        override val description: String,
        val pattern: RecurrencePattern,
        val startDate: LocalDate,
        val endDate: LocalDate? = null,
    ) : NewEntry
}

object EntryPlanner {
    fun defaultDescription(kind: TxKind) = if (kind == TxKind.EXPENSE) "Expense" else "Income"

    /** Every schedule is available for expenses and income alike (daily, weekly, every 2 weeks, monthly, yearly, ...). */
    @Suppress("UNUSED_PARAMETER")
    fun isPatternAllowed(kind: TxKind, pattern: RecurrencePattern): Boolean = true

    /** A human-readable problem with the entry, or null when it can be saved. */
    fun validate(entry: NewEntry): String? {
        if (entry.amount <= 0) return "Enter an amount greater than zero."
        if (entry is NewEntry.Recurring) {
            if (!isPatternAllowed(entry.kind, entry.pattern)) return "That schedule isn't available for this type."
            if (entry.endDate != null && entry.endDate.isBefore(entry.startDate)) return "The end date is before the start date."
        }
        return null
    }

    private fun descriptionOf(entry: NewEntry) = entry.description.trim().ifEmpty { defaultDescription(entry.kind) }

    /** The transaction for a [NewEntry.Now] or [NewEntry.Planned]; null for recurring entries. */
    fun transactionFor(entry: NewEntry, today: LocalDate): Transaction? = when (entry) {
        is NewEntry.Now -> Transaction(
            date = entry.date ?: today, amount = entry.amount, kind = entry.kind, description = descriptionOf(entry),
            status = TxStatus.CONFIRMED, source = TxSource.MANUAL, isPlanned = false,
        )
        is NewEntry.Planned -> Transaction(
            date = entry.date, amount = entry.amount, kind = entry.kind, description = descriptionOf(entry),
            status = TxStatus.PENDING, source = TxSource.MANUAL, isPlanned = true,
        )
        is NewEntry.Recurring -> null
    }

    /** The rule (expense rule or income source) for a [NewEntry.Recurring]; null otherwise. */
    fun ruleFor(entry: NewEntry): RecurringRule? {
        if (entry !is NewEntry.Recurring) return null
        return RecurringRule(
            amount = entry.amount,
            kind = entry.kind,
            description = descriptionOf(entry),
            pattern = entry.pattern,
            startDate = entry.startDate,
            endDate = entry.endDate,
            enabled = true,
        )
    }
}
