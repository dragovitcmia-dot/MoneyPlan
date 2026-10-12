package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.YearMonth
import java.util.Locale

class AppDatesTest {
    @Test fun dates_are_day_three_letter_uppercase_month_year() {
        assertEquals("05 OCT 2026", AppDates.format(d(2026, 10, 5)))
        assertEquals("31 DEC 2026", AppDates.format(d(2026, 12, 31)))
        assertEquals("01 JAN 2027", AppDates.format(d(2027, 1, 1)))
        assertEquals("29 FEB 2028", AppDates.format(d(2028, 2, 29)))
    }

    @Test fun every_month_is_a_three_letter_english_abbreviation() {
        val expected = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        for (m in 1..12) assertEquals(expected[m - 1], AppDates.format(d(2026, m, 15)).split(" ")[1])
    }

    @Test fun the_phones_language_does_not_matter() {
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale("es", "ES"), Locale.FRENCH, Locale("ar", "TN"), Locale.GERMAN)) {
                Locale.setDefault(locale)
                assertEquals("05 OCT 2026", AppDates.format(d(2026, 10, 5)))
                assertEquals("MON 05 OCT 2026", AppDates.withWeekday(d(2026, 10, 5)))
                assertEquals("OCT 2026", AppDates.monthYear(YearMonth.of(2026, 10)))
                assertEquals("Mon", AppDates.weekdayShort(DayOfWeek.MONDAY))
                // Spanish would say "ene", "dic", "lun": none of that may leak in
                assertEquals("DEC", AppDates.format(d(2026, 12, 1)).split(" ")[1])
                assertEquals("JAN", AppDates.format(d(2027, 1, 1)).split(" ")[1])
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun the_year_is_left_out_only_in_the_short_form_and_only_for_the_current_year() {
        val today = d(2026, 10, 15)
        assertEquals("05 OCT", AppDates.formatShort(d(2026, 10, 5), today))
        assertEquals("05 OCT 2027", AppDates.formatShort(d(2027, 10, 5), today))
        assertEquals("05 OCT 2025", AppDates.formatShort(d(2025, 10, 5), today))
        assertEquals("05 OCT 2026", AppDates.format(d(2026, 10, 5))) // the normal form always has the year
    }

    @Test fun weekday_names_and_lists_are_english_in_week_order() {
        assertEquals("TUE 10 NOV 2026", AppDates.withWeekday(d(2026, 11, 10)))
        assertEquals("Mon, Thu", AppDates.weekdaysText(setOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY)))
        assertEquals("Mon, Tue, Wed, Thu, Fri, Sat, Sun", AppDates.weekdaysText(DayOfWeek.entries.toSet()))
        assertEquals("", AppDates.weekdaysText(emptySet()))
    }

    @Test fun no_month_is_ever_spelled_out() {
        for (m in 1..12) {
            val text = AppDates.format(d(2026, m, 1)) + AppDates.monthYear(YearMonth.of(2026, m)) + AppDates.withWeekday(d(2026, m, 1))
            for (full in listOf("JANUARY", "FEBRUARY", "MARCH", "APRIL", "JUNE", "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER")) {
                assertFalse(text.uppercase().contains(full))
            }
        }
        assertTrue(AppDates.LOCALE == Locale.ENGLISH)
    }
}
