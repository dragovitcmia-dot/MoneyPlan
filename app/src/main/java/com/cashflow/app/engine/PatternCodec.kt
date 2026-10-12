package com.cashflow.app.engine

import java.time.DayOfWeek
import java.time.LocalDate

/** A pattern as stored in the existing rule columns: a type name, one integer value and an optional anchor date. */
data class EncodedPattern(val type: String, val value: Int?, val anchor: LocalDate?)

/**
 * Encode / decode of [RecurrencePattern] into the columns the database already has (no schema change):
 * - DAILY, MONTH_FIRST, MONTH_LAST: no value
 * - WEEKLY: ISO weekday 1..7
 * - WEEKDAYS: a set of two or more weekdays as a bit mask (bit 0 = Monday ... bit 6 = Sunday)
 * - EVERY_N_DAYS: n, anchored on a date
 * - DAY_OF_MONTH: day 1..31
 * - YEARLY: month * 100 + day
 * The type names are the names of the data layer's PatternType enum.
 */
object PatternCodec {
    fun encode(pattern: RecurrencePattern): EncodedPattern = when (pattern) {
        RecurrencePattern.Daily -> EncodedPattern("DAILY", null, null)
        is RecurrencePattern.Weekly -> EncodedPattern("WEEKLY", pattern.dayOfWeek.value, null)
        is RecurrencePattern.WeeklyDays -> EncodedPattern("WEEKDAYS", maskOf(pattern.days), null)
        is RecurrencePattern.EveryNDays -> EncodedPattern("EVERY_N_DAYS", pattern.n, pattern.anchorDate)
        RecurrencePattern.MonthFirst -> EncodedPattern("MONTH_FIRST", null, null)
        RecurrencePattern.MonthLast -> EncodedPattern("MONTH_LAST", null, null)
        is RecurrencePattern.DayOfMonth -> EncodedPattern("DAY_OF_MONTH", pattern.day, null)
        is RecurrencePattern.Yearly -> EncodedPattern("YEARLY", pattern.month * 100 + pattern.day, null)
    }

    fun decode(type: String, value: Int?, anchor: LocalDate?): RecurrencePattern = when (type) {
        "DAILY" -> RecurrencePattern.Daily
        "WEEKLY" -> RecurrencePattern.Weekly(DayOfWeek.of(value!!))
        "WEEKDAYS" -> daysOf(value!!).let { days -> if (days.size == 1) RecurrencePattern.Weekly(days.first()) else RecurrencePattern.WeeklyDays(days) }
        "EVERY_N_DAYS" -> RecurrencePattern.EveryNDays(value!!, anchor!!)
        "MONTH_FIRST" -> RecurrencePattern.MonthFirst
        "MONTH_LAST" -> RecurrencePattern.MonthLast
        "DAY_OF_MONTH" -> RecurrencePattern.DayOfMonth(value!!)
        "YEARLY" -> RecurrencePattern.Yearly(value!! / 100, value % 100)
        else -> throw IllegalArgumentException("unknown pattern type $type")
    }

    fun maskOf(days: Set<DayOfWeek>): Int = days.fold(0) { mask, d -> mask or (1 shl (d.value - 1)) }

    fun daysOf(mask: Int): Set<DayOfWeek> = DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
}
