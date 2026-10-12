package com.cashflow.app.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Pure month-grid generation for the Calendar tab. */
object CalendarGrid {
    const val DAYS_PER_WEEK = 7

    /** Rows shown for every month so the pager page never changes height. */
    const val FIXED_ROWS = 6

    /**
     * The weeks that cover [month]: each row has 7 consecutive days starting on [firstDayOfWeek], with
     * the leading days of the previous month and the trailing days of the next one filling the first
     * and last rows. At least [minRows] rows are returned (extra rows continue into the next month).
     */
    fun weeks(month: YearMonth, firstDayOfWeek: DayOfWeek, minRows: Int = 0): List<List<LocalDate>> {
        val first = month.atDay(1)
        val lead = Math.floorMod(first.dayOfWeek.value - firstDayOfWeek.value, DAYS_PER_WEEK)
        val needed = (lead + month.lengthOfMonth() + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK
        val rows = maxOf(needed, minRows)
        val start = first.minusDays(lead.toLong())
        return List(rows) { r -> List(DAYS_PER_WEEK) { c -> start.plusDays((r * DAYS_PER_WEEK + c).toLong()) } }
    }

    /** The seven weekday columns, in display order. */
    fun weekdays(firstDayOfWeek: DayOfWeek): List<DayOfWeek> = List(DAYS_PER_WEEK) { firstDayOfWeek.plus(it.toLong()) }
}

/** Maps pager pages to months: page 0 is [BASE], one page per month. */
object MonthPages {
    val BASE: YearMonth = YearMonth.of(2020, 1)
    const val COUNT = 12 * 30 // 2020 .. 2049

    fun monthOf(page: Int): YearMonth = BASE.plusMonths(page.toLong())

    fun pageOf(month: YearMonth): Int = ChronoUnit.MONTHS.between(BASE, month).toInt().coerceIn(0, COUNT - 1)
}
