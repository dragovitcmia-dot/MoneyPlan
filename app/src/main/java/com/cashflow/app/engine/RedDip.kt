package com.cashflow.app.engine

import java.time.LocalDate

/** One day that goes red because of the new item. */
data class DipDay(val date: LocalDate, val closing: Long, /** The balance before the item (null if there was none to compare). */ val before: Long?)

/** What the warning sheet shows before an item that would put money below zero is saved. */
data class RedDip(
    /** Days that are newly negative, or more negative than before, in date order. */
    val days: List<DipDay>,
    /** The lowest balance after the item, and the day it happens. */
    val lowest: Long,
    val lowestDate: LocalDate,
    /** How much is missing at the lowest point (always > 0). */
    val shortfall: Long,
)

/**
 * "Red-dip warning": applies the item (a single row, or the next 90 days of a repeating payment) to the forecast and
 * reports the days that become negative or get more negative. Null when nothing does. Only used when saving;
 * browsing the calendar never warns.
 */
object RedDipCheck {
    const val HORIZON_DAYS = 90

    fun check(engine: BalanceEngine, entry: NewEntry): RedDip? {
        val today = engine.today
        val end = today.plusDays(HORIZON_DAYS.toLong())
        val before = engine.forecast(today, end).associate { it.date to it.closing }
        val after = LiveCalendar.engineWith(engine, entry).forecast(today, end)
        val days = after.filter { day ->
            day.closing < 0 && (before[day.date]?.let { it >= 0 || day.closing < it } ?: true)
        }.map { DipDay(it.date, it.closing, before[it.date]) }
        if (days.isEmpty()) return null
        val lowest = days.minWith(compareBy<DipDay> { it.closing }.thenBy { it.date })
        return RedDip(days, lowest.closing, lowest.date, shortfall = -lowest.closing)
    }
}

/** Quick name suggestions for the Name step: the names used most recently, newest first, without repeats. */
object NameSuggestions {
    fun recent(transactions: List<Transaction>, kind: TxKind, limit: Int = 6): List<String> =
        transactions.asSequence()
            .filter { it.kind == kind && it.description.isNotBlank() }
            .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.id })
            .map { it.description.trim() }
            .distinctBy { it.lowercase() }
            .take(limit)
            .toList()
}
