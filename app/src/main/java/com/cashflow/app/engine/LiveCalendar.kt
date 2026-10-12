package com.cashflow.app.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** One cell of the live calendar in the wizard. */
data class LiveDay(
    val date: LocalDate,
    val inMonth: Boolean,
    /** Past days cannot be chosen. */
    val enabled: Boolean,
    /** Projected closing balance of the day WITH the item applied; null before the balance's start date. */
    val closing: Long?,
    val negative: Boolean,
    val selected: Boolean,
    val isToday: Boolean = false,
)

/** The line under the grid: what the balance is on the chosen day and the lowest it gets after it. */
data class LiveSummary(
    val date: LocalDate,
    val balanceOnDay: Long,
    val lowestAfter: Long,
    val lowestDate: LocalDate,
)

data class LiveMonth(val month: YearMonth, val weeks: List<List<LiveDay>>, val summary: LiveSummary?)

/**
 * The live calendar of the wizard: a month grid whose balances are the forecast WITH the item applied on the
 * selected day. Pure; the engine is not modified.
 */
object LiveCalendar {
    /** How far after the chosen day the "lowest balance" looks. */
    const val SUMMARY_HORIZON_DAYS = 90L

    /**
     * [entryAt] gives the entry as it would be saved on a given day (null when it cannot be built): for Later its
     * date, for a repeating payment its first day. [selected] is the chosen day; without one nothing is applied.
     */
    fun project(
        engine: BalanceEngine,
        month: YearMonth,
        firstDayOfWeek: DayOfWeek,
        selected: LocalDate?,
        entryAt: (LocalDate) -> NewEntry?,
    ): LiveMonth {
        val today = engine.today
        val applied = selected?.let { entryAt(it) }?.let { engineWith(engine, it) } ?: engine
        val grid = CalendarGrid.weeks(month, firstDayOfWeek, CalendarGrid.MIN_ROWS)
        val from = grid.first().first()
        val to = grid.last().last()
        val closing = applied.forecast(from, to).associate { it.date to it.closing }
        val weeks = grid.map { week ->
            week.map { date ->
                val c = closing[date]
                LiveDay(
                    date = date,
                    inMonth = YearMonth.from(date) == month,
                    enabled = !date.isBefore(today),
                    closing = c,
                    negative = c != null && c < 0,
                    selected = date == selected,
                    isToday = date == today,
                )
            }
        }
        val summary = selected?.let { day ->
            val after = applied.forecast(day, day.plusDays(SUMMARY_HORIZON_DAYS))
            val here = after.firstOrNull() ?: return@let null
            val low = after.minWithOrNull(compareBy<DayForecast> { it.closing }.thenBy { it.date }) ?: here
            LiveSummary(day, here.closing, low.closing, low.date)
        }
        return LiveMonth(month, weeks, summary)
    }

    /** The engine with [entry] added the way saving would add it (one row, or a series' occurrences). */
    fun engineWith(engine: BalanceEngine, entry: NewEntry): BalanceEngine {
        val tx = EntryPlanner.transactionFor(entry, engine.today)
        val rule = EntryPlanner.ruleFor(entry)
        val rows = tx?.let { listOf(it) } ?: rule?.let { RuleMaintenance.materialize(it, emptyList(), engine.today) }.orEmpty()
        return BalanceEngine(engine.settings, engine.transactions + rows, engine.today)
    }
}
