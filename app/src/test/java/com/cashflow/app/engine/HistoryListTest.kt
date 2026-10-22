package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class HistoryListTest {
    private fun row(date: LocalDate, tnd: Long, name: String, kind: TxKind = TxKind.EXPENSE, status: TxStatus = TxStatus.CONFIRMED, id: Long = 0, rule: Long? = null) =
        Transaction(
            id = id, date = date, amount = tnd * 1000, kind = kind, description = name, status = status,
            source = if (rule != null) TxSource.RECURRING else TxSource.MANUAL, recurringRuleId = rule, occurrenceDate = if (rule != null) date else null,
        )

    // Wednesday 21 OCT 2026: the week is Mon 19 .. Sun 25 OCT, the month is OCT.
    private val today = LocalDate.of(2026, 10, 21)

    // ---- only confirmed items ---------------------------------------------------------------------------

    @Test fun waiting_skipped_and_rejected_items_are_never_history() {
        val rows = listOf(
            row(today, 10, "Done", id = 1),
            row(today, 20, "Waiting", status = TxStatus.PENDING, id = 2),
            row(today, 30, "Rejected", status = TxStatus.SKIPPED, id = 3),
            row(today, 40, "Pay", kind = TxKind.INCOME, id = 4),
        )
        assertEquals(listOf("Done", "Pay"), HistoryList.confirmedOnly(rows).map { it.description })
        assertEquals(listOf("Done", "Pay"), HistoryList.filter(rows, UpcomingType.ALL, "").map { it.description })
        assertTrue(HistoryList.groupByDay(HistoryList.confirmedOnly(rows)).single().items.none { it.status != TxStatus.CONFIRMED })
    }

    @Test fun now_expenses_and_accepted_series_occurrences_are_included() {
        val now = row(today, 12, "Coffee", id = 1).copy(isPlanned = false)
        val series = row(today.minusDays(1), 75, "Gym", id = 2, rule = 7)
        assertEquals(2, HistoryList.filter(listOf(now, series), UpcomingType.ALL, "").size)
    }

    // ---- grouping and day totals ------------------------------------------------------------------------

    @Test fun days_run_newest_first_and_rows_newest_first_within_a_day() {
        val rows = listOf(
            row(LocalDate.of(2026, 10, 19), 5, "a", id = 1),
            row(LocalDate.of(2026, 10, 20), 7, "b", id = 2),
            row(LocalDate.of(2026, 10, 20), 9, "c", id = 5),
            row(LocalDate.of(2026, 9, 30), 3, "d", id = 3),
        )
        val days = HistoryList.groupByDay(rows)
        assertEquals(listOf(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 19), LocalDate.of(2026, 9, 30)), days.map { it.date })
        assertEquals(listOf("c", "b"), days.first().items.map { it.description })
    }

    @Test fun a_day_header_carries_spent_and_received() {
        val d = LocalDate.of(2026, 10, 20)
        val day = HistoryList.groupByDay(
            listOf(row(d, 12, "Coffee", id = 1), row(d, 8, "Snack", id = 2), row(d, 100, "Refund", TxKind.INCOME, id = 3)),
        ).single()
        assertEquals(20_000L, day.spent)
        assertEquals(100_000L, day.received)
        assertEquals("TUE 20 OCT 2026", AppDates.withWeekday(day.date))
    }

    @Test fun an_empty_list_has_no_days_and_no_entries() {
        assertTrue(HistoryList.groupByDay(emptyList()).isEmpty())
        assertTrue(HistoryList.entries(emptyList()).isEmpty())
    }

    @Test fun the_flat_list_is_a_header_then_its_rows_day_after_day() {
        val days = HistoryList.groupByDay(
            listOf(row(LocalDate.of(2026, 10, 20), 1, "a", id = 1), row(LocalDate.of(2026, 10, 20), 1, "b", id = 2), row(LocalDate.of(2026, 10, 19), 1, "c", id = 3)),
        )
        val kinds = HistoryList.entries(days).map { it::class.simpleName }
        assertEquals(listOf("Header", "Row", "Row", "Header", "Row"), kinds)
    }

    // ---- week and month totals ----------------------------------------------------------------------------

    @Test fun the_week_runs_monday_to_sunday() {
        assertEquals(LocalDate.of(2026, 10, 19), HistoryList.weekStart(today))
        assertEquals(LocalDate.of(2026, 10, 25), HistoryList.weekEnd(today))
        for (offset in 0..6) assertEquals(LocalDate.of(2026, 10, 19), HistoryList.weekStart(LocalDate.of(2026, 10, 19).plusDays(offset.toLong())))
        assertEquals(DayOfWeek.MONDAY, HistoryList.weekStart(LocalDate.of(2026, 10, 18)).dayOfWeek) // a Sunday belongs to the week before
        assertEquals(LocalDate.of(2026, 10, 12), HistoryList.weekStart(LocalDate.of(2026, 10, 18)))
    }

    @Test fun week_boundaries_monday_in_sunday_out() {
        val rows = listOf(
            row(LocalDate.of(2026, 10, 18), 100, "last sunday", id = 1), // the week before
            row(LocalDate.of(2026, 10, 19), 10, "monday", id = 2),
            row(LocalDate.of(2026, 10, 25), 20, "sunday", id = 3),
            row(LocalDate.of(2026, 10, 26), 400, "next monday", id = 4),
        )
        val t = HistoryList.totals(rows, today)
        assertEquals(30_000L, t.spentThisWeek)
        assertEquals(530_000L, t.spentThisMonth)
    }

    @Test fun month_boundaries_first_in_last_in_neighbours_out() {
        val rows = listOf(
            row(LocalDate.of(2026, 9, 30), 1000, "sep", id = 1),
            row(LocalDate.of(2026, 10, 1), 5, "first", id = 2),
            row(LocalDate.of(2026, 10, 31), 6, "last", id = 3),
            row(LocalDate.of(2026, 11, 1), 2000, "nov", id = 4),
            row(LocalDate.of(2026, 10, 3), 50, "pay", TxKind.INCOME, id = 5),
            row(LocalDate.of(2026, 9, 29), 60, "old pay", TxKind.INCOME, id = 6),
        )
        val t = HistoryList.totals(rows, today)
        assertEquals(11_000L, t.spentThisMonth)
        assertEquals(50_000L, t.receivedThisMonth)
    }

    @Test fun a_week_that_crosses_the_month_counts_in_both_and_the_window_starts_at_the_earlier_day() {
        val d = LocalDate.of(2026, 11, 2) // Monday 2 NOV: the week is 2..8 NOV, the month is NOV
        val rows = listOf(row(LocalDate.of(2026, 10, 31), 99, "oct", id = 1), row(LocalDate.of(2026, 11, 3), 4, "nov", id = 2))
        val t = HistoryList.totals(rows, d)
        assertEquals(4_000L, t.spentThisWeek)
        assertEquals(4_000L, t.spentThisMonth)
        val wed = LocalDate.of(2026, 7, 1) // Wednesday: the week starts in June
        assertEquals(LocalDate.of(2026, 6, 29), HistoryList.weekStart(wed))
        assertEquals(LocalDate.of(2026, 6, 29), HistoryList.totalsFrom(wed))
        val sevenRows = listOf(row(LocalDate.of(2026, 6, 30), 7, "june", id = 1), row(LocalDate.of(2026, 7, 1), 3, "july", id = 2))
        val tt = HistoryList.totals(sevenRows, wed)
        assertEquals(10_000L, tt.spentThisWeek) // both days are in the week 29 JUN .. 5 JUL
        assertEquals(3_000L, tt.spentThisMonth)
    }

    @Test fun totals_follow_the_clock_and_count_confirmed_items_only() {
        val rows = listOf(
            row(LocalDate.of(2026, 10, 20), 10, "done", id = 1),
            row(LocalDate.of(2026, 10, 20), 500, "waiting", status = TxStatus.PENDING, id = 2),
            row(LocalDate.of(2026, 10, 20), 600, "skipped", status = TxStatus.SKIPPED, id = 3),
            row(LocalDate.of(2026, 10, 20), 70, "pay", TxKind.INCOME, id = 4),
            row(LocalDate.of(2026, 10, 20), 800, "waiting pay", TxKind.INCOME, status = TxStatus.PENDING, id = 5),
        )
        assertEquals(HistoryTotals(10_000, 10_000, 70_000), HistoryList.totals(rows, today))
        // the Lab clock moved a month on: nothing was confirmed that week or month
        assertEquals(HistoryTotals(0, 0, 0), HistoryList.totals(rows, LocalDate.of(2026, 12, 2)))
        assertEquals(HistoryTotals(0, 0, 0), HistoryList.totals(emptyList(), today))
    }

    @Test fun totals_ignore_the_tab_and_the_search_by_taking_the_rows_unfiltered() {
        val rows = listOf(row(today, 10, "Coffee", id = 1), row(today, 70, "Pay", TxKind.INCOME, id = 2))
        val all = HistoryList.totals(rows, today)
        val filtered = HistoryList.filter(rows, UpcomingType.INCOME, "zzz")
        assertTrue(filtered.isEmpty())
        assertEquals(all, HistoryList.totals(rows, today)) // the strip is computed from its own rows, not from the filtered list
    }

    // ---- tabs and search --------------------------------------------------------------------------------------

    private val mixed = listOf(
        row(today, 10, "Phone cover", id = 1),
        row(today, 20, "Coffee", id = 2),
        row(today, 300, "Salary", TxKind.INCOME, id = 3),
        row(today, 5, "COVER letter stamp", id = 4),
        row(today, 80, "Refund cover", TxKind.INCOME, id = 5),
    )

    @Test fun tabs_split_expenses_and_income() {
        assertEquals(5, HistoryList.filter(mixed, UpcomingType.ALL, "").size)
        assertEquals(listOf("Phone cover", "Coffee", "COVER letter stamp"), HistoryList.filter(mixed, UpcomingType.EXPENSES, "").map { it.description })
        assertEquals(listOf("Salary", "Refund cover"), HistoryList.filter(mixed, UpcomingType.INCOME, "").map { it.description })
    }

    @Test fun search_is_case_insensitive_name_contains_combined_with_the_tab() {
        assertEquals(listOf("Phone cover", "COVER letter stamp", "Refund cover"), HistoryList.filter(mixed, UpcomingType.ALL, "cover").map { it.description })
        assertEquals(listOf("Phone cover", "COVER letter stamp"), HistoryList.filter(mixed, UpcomingType.EXPENSES, "COVER").map { it.description })
        assertEquals(listOf("Refund cover"), HistoryList.filter(mixed, UpcomingType.INCOME, "Cover").map { it.description })
        assertEquals(5, HistoryList.filter(mixed, UpcomingType.ALL, "   ").size) // blank = no search
        assertEquals(listOf("Phone cover"), HistoryList.filter(mixed, UpcomingType.ALL, "  phone ").map { it.description }) // trimmed
        assertTrue(HistoryList.filter(mixed, UpcomingType.ALL, "zzz").isEmpty())
    }

    @Test fun the_results_line_gives_the_count_and_the_net_total() {
        assertEquals("1 result, total 80 TND", HistoryList.resultsLine(HistoryList.filter(mixed, UpcomingType.INCOME, "refund")))
        assertEquals("2 results, total -15 TND", HistoryList.resultsLine(HistoryList.filter(mixed, UpcomingType.EXPENSES, "cover")))
        assertEquals("3 results, total 65 TND", HistoryList.resultsLine(HistoryList.filter(mixed, UpcomingType.ALL, "cover"))) // -10 -5 +80
        assertEquals("0 results, total 0 TND", HistoryList.resultsLine(emptyList()))
    }

    // ---- month windows ----------------------------------------------------------------------------------------

    @Test fun the_list_loads_month_windows_as_it_scrolls() {
        assertEquals(LocalDate.of(2026, 10, 1), HistoryList.windowFrom(today, 1))
        assertEquals(LocalDate.of(2026, 9, 1), HistoryList.windowFrom(today, HistoryList.INITIAL_MONTHS))
        assertEquals(LocalDate.of(2026, 8, 1), HistoryList.windowFrom(today, 3))
        assertEquals(LocalDate.of(2025, 11, 1), HistoryList.windowFrom(today, 12))
        assertEquals(LocalDate.of(2026, 10, 1), HistoryList.windowFrom(today, 0)) // never less than the current month
        assertEquals(LocalDate.of(2025, 12, 1), HistoryList.windowFrom(LocalDate.of(2026, 1, 15), 2)) // across a year
    }

    @Test fun nothing_more_to_load_when_the_window_reaches_the_earliest_confirmed_day() {
        val from = LocalDate.of(2026, 9, 1)
        assertTrue(HistoryList.exhausted(from, null))
        assertTrue(HistoryList.exhausted(from, LocalDate.of(2026, 9, 1)))
        assertTrue(HistoryList.exhausted(from, LocalDate.of(2026, 9, 20)))
        assertFalse(HistoryList.exhausted(from, LocalDate.of(2026, 8, 31)))
    }

    @Test fun a_two_thousand_item_list_groups_and_filters_quickly_and_correctly() {
        val start = LocalDate.of(2020, 1, 1)
        val rows = (0 until 2000).map { i -> row(start.plusDays((i / 4).toLong()), (i % 50 + 1).toLong(), if (i % 97 == 0) "Phone cover" else "Item $i", if (i % 5 == 0) TxKind.INCOME else TxKind.EXPENSE, id = i + 1L) }
        val began = System.nanoTime()
        val days = HistoryList.groupByDay(HistoryList.filter(rows, UpcomingType.ALL, ""))
        val entries = HistoryList.entries(days)
        val hits = HistoryList.filter(rows, UpcomingType.ALL, "cover")
        val ms = (System.nanoTime() - began) / 1_000_000
        assertEquals(500, days.size)
        assertEquals(2500, entries.size)
        assertEquals(21, hits.size)
        assertTrue("took $ms ms", ms < 2000)
        assertEquals(start.plusDays(499), days.first().date)
        assertNull(days.map { it.date }.zipWithNext().firstOrNull { (a, b) -> !a.isAfter(b) })
    }
}
