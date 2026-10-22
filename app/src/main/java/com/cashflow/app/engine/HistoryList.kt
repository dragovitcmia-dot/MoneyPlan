package com.cashflow.app.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** One day of the History list: its confirmed items (newest first) and what was spent and received that day. */
data class HistoryDay(val date: LocalDate, val items: List<Transaction>, val spent: Long, val received: Long)

/** The three numbers of the totals strip: confirmed items only, never affected by the tab or the search. */
data class HistoryTotals(val spentThisWeek: Long, val spentThisMonth: Long, val receivedThisMonth: Long)

/** What the list asks the data layer for: confirmed rows from [from] on (month windows), or every confirmed row whose name matches [query]. */
data class HistoryWindow(val from: LocalDate, val query: String)

/**
 * Pure rules of the History tab: only CONFIRMED items count (waiting, skipped and rejected ones never appear), grouped by day newest first,
 * filtered by the tab (All, Expenses, Income) and by a case-insensitive "name contains" search, with week and month totals from the clock's day.
 */
object HistoryList {
    /** Waiting, skipped and rejected items are not history. */
    fun confirmedOnly(rows: List<Transaction>): List<Transaction> = rows.filter { it.status == TxStatus.CONFIRMED }

    /** Case-insensitive "name contains"; a blank query matches everything. */
    fun matches(tx: Transaction, query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || tx.description.contains(q, ignoreCase = true)
    }

    private fun inTab(tx: Transaction, tab: UpcomingType) = when (tab) {
        UpcomingType.ALL -> true
        UpcomingType.EXPENSES -> tx.kind == TxKind.EXPENSE
        UpcomingType.INCOME -> tx.kind == TxKind.INCOME
    }

    /** Confirmed rows in the active tab whose name contains the query. */
    fun filter(rows: List<Transaction>, tab: UpcomingType, query: String): List<Transaction> =
        rows.filter { it.status == TxStatus.CONFIRMED && inTab(it, tab) && matches(it, query) }

    /** Newest day first; inside a day the newest row (highest id) first. Totals are over ALL of the day's rows given. */
    fun groupByDay(rows: List<Transaction>): List<HistoryDay> =
        rows.groupBy { it.date }.entries.sortedByDescending { it.key }.map { (date, list) ->
            val sorted = list.sortedByDescending { it.id }
            HistoryDay(
                date = date,
                items = sorted,
                spent = sorted.filter { it.kind == TxKind.EXPENSE }.sumOf { it.amount },
                received = sorted.filter { it.kind == TxKind.INCOME }.sumOf { it.amount },
            )
        }

    /** Monday of the week that contains [today] (the week runs Monday to Sunday, like the Calendar). */
    fun weekStart(today: LocalDate): LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun weekEnd(today: LocalDate): LocalDate = weekStart(today).plusDays(6)

    fun monthStart(today: LocalDate): LocalDate = today.withDayOfMonth(1)

    fun monthEnd(today: LocalDate): LocalDate = today.with(TemporalAdjusters.lastDayOfMonth())

    /** The earliest day the totals strip needs: the start of the month or of the week, whichever comes first. */
    fun totalsFrom(today: LocalDate): LocalDate = minOf(weekStart(today), monthStart(today))

    fun totals(rows: List<Transaction>, today: LocalDate): HistoryTotals {
        val confirmed = confirmedOnly(rows)
        val ws = weekStart(today)
        val we = weekEnd(today)
        val ms = monthStart(today)
        val me = monthEnd(today)
        fun spent(from: LocalDate, to: LocalDate) =
            confirmed.filter { it.kind == TxKind.EXPENSE && !it.date.isBefore(from) && !it.date.isAfter(to) }.sumOf { it.amount }
        return HistoryTotals(
            spentThisWeek = spent(ws, we),
            spentThisMonth = spent(ms, me),
            receivedThisMonth = confirmed.filter { it.kind == TxKind.INCOME && !it.date.isBefore(ms) && !it.date.isAfter(me) }.sumOf { it.amount },
        )
    }

    /** The net of a result list: income minus expenses (so a list of expenses only has a negative total). */
    fun netTotal(rows: List<Transaction>): Long = rows.sumOf { if (it.kind == TxKind.INCOME) it.amount else -it.amount }

    /** "3 results, total -75 TND" / "1 result, total 25 TND". */
    fun resultsLine(rows: List<Transaction>): String {
        val n = rows.size
        val net = netTotal(rows)
        val shown = (if (net < 0) "-" else "") + Money.formatDisplay(if (net < 0) -net else net)
        return "$n ${if (n == 1) "result" else "results"}, total $shown"
    }

    // ---- month windows: the list loads two months at first and one more each time the end is reached ------------------------------

    const val INITIAL_MONTHS = 2

    /** First day of the window that covers [months] calendar months ending with [today]'s month. */
    fun windowFrom(today: LocalDate, months: Int): LocalDate = monthStart(today).minusMonths((months.coerceAtLeast(1) - 1).toLong())

    /** True when the window already reaches the earliest confirmed day (or there is none): nothing more to load. */
    fun exhausted(from: LocalDate, earliest: LocalDate?): Boolean = earliest == null || !earliest.isBefore(from)

    /** Flat list for a lazy column: a day header then its rows, day after day. */
    sealed interface Entry {
        data class Header(val day: HistoryDay) : Entry
        data class Row(val tx: Transaction) : Entry
    }

    fun entries(days: List<HistoryDay>): List<Entry> = days.flatMap { day -> listOf<Entry>(Entry.Header(day)) + day.items.map { Entry.Row(it) } }
}
