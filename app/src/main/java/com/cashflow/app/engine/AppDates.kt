package com.cashflow.app.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one place dates are turned into text. Always English (the phone's language does not matter), always
 * "05 OCT 2026": day, three-letter UPPERCASE month, year. Months are never spelled out.
 */
object AppDates {
    val LOCALE: Locale = Locale.ENGLISH

    /** First day of the calendar week. Set once at start from the phone's region; defaults to Monday. */
    @Volatile
    var firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY

    private val day = DateTimeFormatter.ofPattern("dd", LOCALE)
    private val month = DateTimeFormatter.ofPattern("MMM", LOCALE)
    private val weekday = DateTimeFormatter.ofPattern("EEE", LOCALE)

    private fun mon(date: LocalDate) = date.format(month).uppercase(LOCALE)

    /** "05 OCT 2026". */
    fun format(date: LocalDate): String = "${date.format(day)} ${mon(date)} ${date.year}"

    /** "05 OCT" (no year), for tight places where the year is the current one; other years keep the year. */
    fun formatShort(date: LocalDate, today: LocalDate): String =
        if (date.year == today.year) "${date.format(day)} ${mon(date)}" else format(date)

    /** "MON 05 OCT 2026". */
    fun withWeekday(date: LocalDate): String = "${date.format(weekday).uppercase(LOCALE)} ${format(date)}"

    /** "OCT 2026" (month headers). */
    fun monthYear(ym: YearMonth): String = "${ym.atDay(1).format(month).uppercase(LOCALE)} ${ym.year}"

    /** "Mon" (weekday names are shown in English too). */
    fun weekdayShort(d: DayOfWeek): String = d.getDisplayName(java.time.format.TextStyle.SHORT, LOCALE)

    /** "Mon, Thu" in week order starting on Monday. */
    fun weekdaysText(days: Set<DayOfWeek>): String = DayOfWeek.entries.filter { it in days }.joinToString(", ") { weekdayShort(it) }

    /** The seven weekdays in the order the calendar shows them: starting at [firstDayOfWeek] (the phone's region). */
    fun weekOrder(first: DayOfWeek = firstDayOfWeek): List<DayOfWeek> = List(7) { first.plus(it.toLong()) }

    /** "M" (one letter, for the round day buttons). */
    fun weekdayLetter(d: DayOfWeek): String = weekdayShort(d).take(1).uppercase(LOCALE)

    /** "Monday" (spoken names). */
    fun weekdayFull(d: DayOfWeek): String = d.getDisplayName(java.time.format.TextStyle.FULL, LOCALE)
}
