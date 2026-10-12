package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class CalendarGridTest {
    private fun grid(y: Int, m: Int, first: DayOfWeek, minRows: Int = 0) = CalendarGrid.weeks(YearMonth.of(y, m), first, minRows)

    @Test fun october_2026_starts_on_thursday_so_monday_first_has_three_leading_days() {
        val g = grid(2026, 10, DayOfWeek.MONDAY)
        assertEquals(5, g.size)
        assertEquals(d(2026, 9, 28), g.first().first())
        assertEquals(listOf(d(2026, 9, 28), d(2026, 9, 29), d(2026, 9, 30), d(2026, 10, 1)), g.first().take(4))
        assertEquals(d(2026, 11, 1), g.last().last())
    }

    @Test fun october_2026_with_sunday_first_has_four_leading_days() {
        val g = grid(2026, 10, DayOfWeek.SUNDAY)
        assertEquals(d(2026, 9, 27), g.first().first())
        assertEquals(DayOfWeek.SUNDAY, g.first().first().dayOfWeek)
        assertEquals(d(2026, 10, 31), g.last().last())
        assertEquals(5, g.size)
    }

    @Test fun every_row_has_seven_consecutive_days_and_every_day_of_the_month_appears_once() {
        for (first in listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY)) {
            for (m in 1..12) {
                val ym = YearMonth.of(2027, m)
                val g = CalendarGrid.weeks(ym, first)
                val days = g.flatten()
                assertEquals(first, g.first().first().dayOfWeek)
                assertTrue(g.all { it.size == 7 })
                days.zipWithNext().forEach { (a, b) -> assertEquals(a.plusDays(1), b) }
                assertEquals((1..ym.lengthOfMonth()).map { ym.atDay(it) }, days.filter { YearMonth.from(it) == ym })
            }
        }
    }

    @Test fun february_in_a_non_leap_year_can_fit_exactly_four_weeks() {
        // Feb 2027 starts on a Monday and has 28 days.
        val g = grid(2027, 2, DayOfWeek.MONDAY)
        assertEquals(4, g.size)
        assertEquals(d(2027, 2, 1), g.first().first())
        assertEquals(d(2027, 2, 28), g.last().last())
    }

    @Test fun leap_february_has_29_days_and_31_day_months_can_need_six_rows() {
        val feb = grid(2028, 2, DayOfWeek.MONDAY).flatten().filter { it.monthValue == 2 }
        assertEquals(29, feb.size)
        // May 2026 starts on Friday: Monday-first needs 4 leading days + 31 = 35 -> 5 rows; Aug 2026 (Saturday) + 31 = 5 leading + 31 = 36 -> 6 rows.
        assertEquals(5, grid(2026, 5, DayOfWeek.MONDAY).size)
        assertEquals(6, grid(2026, 8, DayOfWeek.MONDAY).size)
        assertEquals(30, grid(2026, 4, DayOfWeek.MONDAY).flatten().count { it.monthValue == 4 })
    }

    @Test fun fixed_rows_pads_with_following_days() {
        val g = grid(2027, 2, DayOfWeek.MONDAY, minRows = CalendarGrid.FIXED_ROWS)
        assertEquals(6, g.size)
        assertEquals(d(2027, 3, 1), g[4].first())
        assertEquals(d(2027, 3, 14), g.last().last())
    }

    @Test fun year_boundary_shows_the_neighbouring_years_days() {
        val dec = grid(2026, 12, DayOfWeek.MONDAY)
        assertEquals(d(2026, 11, 30), dec.first().first())
        assertEquals(d(2027, 1, 3), dec.last().last())
        val jan = grid(2027, 1, DayOfWeek.MONDAY)
        assertEquals(d(2026, 12, 28), jan.first().first())
        assertEquals(d(2027, 1, 31), jan.flatten().last { it.monthValue == 1 })
    }

    @Test fun weekday_headers_start_with_the_first_day_of_week() {
        assertEquals(DayOfWeek.entries, CalendarGrid.weekdays(DayOfWeek.MONDAY))
        assertEquals(listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), CalendarGrid.weekdays(DayOfWeek.SUNDAY))
    }

    @Test fun pager_pages_map_to_months_and_back() {
        assertEquals(YearMonth.of(2020, 1), MonthPages.monthOf(0))
        assertEquals(YearMonth.of(2021, 1), MonthPages.monthOf(12))
        for (ym in listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 12), YearMonth.of(2027, 1))) {
            assertEquals(ym, MonthPages.monthOf(MonthPages.pageOf(ym)))
        }
        assertEquals(MonthPages.pageOf(YearMonth.of(2026, 10)) + 1, MonthPages.pageOf(YearMonth.of(2026, 11)))
        assertEquals(0, MonthPages.pageOf(YearMonth.of(1999, 1))) // clamped
        assertEquals(MonthPages.COUNT - 1, MonthPages.pageOf(YearMonth.of(2100, 1)))
    }

    @Test fun every_grid_date_is_a_real_local_date() {
        val g = grid(2026, 10, DayOfWeek.MONDAY)
        assertTrue(g.flatten().all { it is LocalDate })
    }

    @Test fun a_month_always_has_five_or_six_rows() {
        for (month in 1..12) {
            val rows = CalendarGrid.weeks(YearMonth.of(2027, month), DayOfWeek.MONDAY, CalendarGrid.MIN_ROWS).size
            assertTrue("month $month has $rows rows", rows == 5 || rows == 6)
        }
    }

    @Test fun row_height_divides_the_available_height_exactly() {
        // small phone (about 300 dp for the grid) and large phone (about 640 dp), 5 and 6 rows
        assertEquals(60f, CalendarGrid.rowHeight(300f, 5), 0.001f)
        assertEquals(50f, CalendarGrid.rowHeight(300f, 6), 0.001f)
        assertEquals(128f, CalendarGrid.rowHeight(640f, 5), 0.001f)
        assertEquals(640f / 6, CalendarGrid.rowHeight(640f, 6), 0.001f)
        for (rows in 5..6) for (h in listOf(300f, 480f, 640f, 900f)) {
            assertEquals(h, CalendarGrid.rowHeight(h, rows) * rows, 0.001f) // the rows add up to the space: no dead space
        }
    }

    @Test fun row_height_of_nothing_is_zero() {
        assertEquals(0f, CalendarGrid.rowHeight(0f, 6), 0f)
        assertEquals(0f, CalendarGrid.rowHeight(-5f, 6), 0f)
        assertEquals(0f, CalendarGrid.rowHeight(300f, 0), 0f)
    }

    @Test fun cells_always_keep_the_balance_only_the_amounts_are_dropped_on_short_rows() {
        assertEquals(CellMode.FULL, CalendarCells.mode(rowHeight = 100f, fullHeight = 92f))
        assertEquals(CellMode.FULL, CalendarCells.mode(rowHeight = 92f, fullHeight = 92f))
        assertEquals(CellMode.COMPACT, CalendarCells.mode(rowHeight = 60f, fullHeight = 92f))
        // There is no mode without the balance (and no dots mode any more).
        assertEquals(listOf("FULL", "COMPACT"), CellMode.entries.map { it.name })
    }
}
