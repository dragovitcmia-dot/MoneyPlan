package com.cashflow.app.engine

import java.time.LocalDate

/** The balance a new item leads to: [balance] on [date]. */
data class ResultingBalance(val date: LocalDate, val balance: Long)

/** Applies a not-yet-saved [NewEntry] to the forecast, the way saving would. Pure; the engine is not modified. */
object EntryProjection {
    /** The engine with [entry] added: one row, or the occurrences of a repeating payment. */
    fun engineWith(engine: BalanceEngine, entry: NewEntry): BalanceEngine {
        val tx = EntryPlanner.transactionFor(entry, engine.today)
        val rule = EntryPlanner.ruleFor(entry)
        val rows = tx?.let { listOf(it) } ?: rule?.let { RuleMaintenance.materialize(it, emptyList(), engine.today) }.orEmpty()
        return BalanceEngine(engine.settings, engine.transactions + rows, engine.today)
    }

    /**
     * The balance after saving [entry]: for "Now" the Available amount today; for a later item the closing balance on
     * its day; for a repeating payment the closing balance on its first day. Null when there is nothing to show.
     */
    fun resultingBalance(engine: BalanceEngine, entry: NewEntry): ResultingBalance? {
        val applied = engineWith(engine, entry)
        val today = engine.today
        val date = when (entry) {
            is NewEntry.Now -> return ResultingBalance(entry.date ?: today, applied.available())
            is NewEntry.Planned -> entry.date
            is NewEntry.Recurring -> EntryPlanner.ruleFor(entry)?.let { r ->
                Recurrence.occurrences(r, r.startDate, r.startDate.plusYears(2)).firstOrNull()
            } ?: entry.startDate
        }
        val day = applied.forecast(date, date).firstOrNull() ?: return null
        return ResultingBalance(date, day.closing)
    }
}
