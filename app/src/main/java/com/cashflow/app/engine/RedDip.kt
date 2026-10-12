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
        val after = EntryProjection.engineWith(engine, entry).forecast(today, end)
        val days = after.filter { day ->
            day.closing < 0 && (before[day.date]?.let { it >= 0 || day.closing < it } ?: true)
        }.map { DipDay(it.date, it.closing, before[it.date]) }
        if (days.isEmpty()) return null
        val lowest = days.minWith(compareBy<DipDay> { it.closing }.thenBy { it.date })
        return RedDip(days, lowest.closing, lowest.date, shortfall = -lowest.closing)
    }
}

/** The names offered above the Name field: the ones used most often. */
object NameSuggestions {
    /**
     * The [limit] most frequently used names of [kind] (compared without regard to case), most used first; equally used
     * names are ordered by how recently they were used. Blank names are ignored.
     */
    fun topFrequent(transactions: List<Transaction>, kind: TxKind, limit: Int = 3): List<String> {
        val used = transactions.filter { it.kind == kind && it.description.isNotBlank() }
        data class Stat(var count: Int, var latest: Transaction, val label: String)
        val stats = LinkedHashMap<String, Stat>()
        for (t in used.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.id })) {
            val key = t.description.trim().lowercase()
            val stat = stats[key]
            if (stat == null) stats[key] = Stat(1, t, t.description.trim()) else stat.count++
        }
        return stats.values
            .sortedWith(compareByDescending<Stat> { it.count }.thenByDescending { it.latest.date }.thenByDescending { it.latest.id })
            .take(limit)
            .map { it.label }
    }
}
