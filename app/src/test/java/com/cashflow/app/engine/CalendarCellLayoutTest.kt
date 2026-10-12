package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarCellLayoutTest {
    private fun m(available: Float, rows: Int, scale: Float = 1f) = CalendarCellLayout.metrics(available, rows, scale)

    @Test fun there_are_four_lines_in_every_cell() {
        assertEquals(4, CalendarCellLayout.LINES)
        assertEquals(3, CalendarCellContent.SLOTS)
    }

    @Test fun roomy_screens_use_the_normal_sizes_and_do_not_scroll() {
        val metrics = m(600f, 6) // 100 dp rows
        assertEquals(CalendarCellLayout.MONEY_SP, metrics.moneySp, 0f)
        assertEquals(CalendarCellLayout.DAY_SP, metrics.daySp, 0f)
        assertEquals(100f, metrics.rowHeightDp, 0.001f)
        assertFalse(metrics.scrolls)
    }

    @Test fun tight_screens_shrink_the_text_instead_of_dropping_lines() {
        val metrics = m(300f, 6) // 50 dp rows: four lines of at most (50 - 4) / (4 * 1.15) = 10 sp
        assertEquals(10f, metrics.moneySp, 0.001f)
        assertEquals(10f, metrics.daySp, 0.001f)
        assertEquals(50f, metrics.rowHeightDp, 0.001f)
        assertFalse(metrics.scrolls)
        assertTrue(metrics.moneySp >= CalendarCellLayout.MIN_SP)
    }

    @Test fun the_sizes_never_go_below_the_minimum_and_the_screen_scrolls_when_nothing_fits() {
        val metrics = m(200f, 6) // 33 dp rows: even 8 sp needs 4 * 8 * 1.15 + 4 = 40.8 dp
        assertEquals(CalendarCellLayout.MIN_SP, metrics.moneySp, 0f)
        assertEquals(CalendarCellLayout.MIN_SP, metrics.daySp, 0f)
        assertTrue(metrics.scrolls)
        assertEquals(CalendarCellLayout.neededHeightDp(8f, 1f), metrics.rowHeightDp, 0.001f)
        // large text on a normal screen: the rows keep the height the smallest text needs
        val big = m(400f, 6, scale = 2f)
        assertTrue(big.scrolls)
        assertEquals(CalendarCellLayout.neededHeightDp(8f, 2f), big.rowHeightDp, 0.001f)
    }

    @Test fun five_rows_get_taller_cells_than_six() {
        assertTrue(m(500f, 5).rowHeightDp > m(500f, 6).rowHeightDp)
    }

    @Test fun the_layout_is_a_pure_function_of_its_inputs() {
        val first = m(432f, 6, 1.3f)
        assertEquals(first, m(432f, 6, 1.3f))
        // asking for another size in between changes nothing about the next answer
        m(100f, 5, 2f)
        assertEquals(first, m(432f, 6, 1.3f))
    }

    @Test fun degenerate_inputs_do_not_crash() {
        assertTrue(m(0f, 6).scrolls)
        assertTrue(m(500f, 0).scrolls)
        assertEquals(m(500f, 6, 1f), m(500f, 6, 0f)) // an invalid scale counts as 1
    }

    @Test fun fit_size_shrinks_long_numbers_to_the_cell_width_but_never_below_the_minimum() {
        // 43 dp wide cell: "-380" (4 chars) fits at 13 sp: 43 / (4 * 0.6) = 17.9
        assertEquals(13f, CalendarCellLayout.fitSp("-380", 43f, 13f, 1f), 0f)
        // "-1.2k" 5 chars at scale 1: 43 / 3 = 14.3 -> 13
        assertEquals(13f, CalendarCellLayout.fitSp("-1.2k", 43f, 13f, 1f), 0f)
        // a long number in a narrow cell at big text shrinks
        val shrunk = CalendarCellLayout.fitSp("-12.5k", 43f, 13f, 1.5f) // 43 / (6 * 0.6 * 1.5) = 7.96 -> floor 8
        assertEquals(CalendarCellLayout.MIN_SP, shrunk, 0f)
        val mid = CalendarCellLayout.fitSp("-1234", 43f, 13f, 1.3f) // 43 / (5 * 0.6 * 1.3) = 11.03
        assertEquals(11.03f, mid, 0.01f)
        assertEquals(13f, CalendarCellLayout.fitSp("", 43f, 13f, 1f), 0f)
    }

    @Test fun every_money_line_fits_or_is_at_the_minimum() {
        for (text in listOf("0", "-75", "+30", "1.2k", "-1.2k", "145", "-28", "12M", "-12M")) for (scale in listOf(1f, 1.3f, 2f)) {
            val sp = CalendarCellLayout.fitSp(text, 43f, CalendarCellLayout.MONEY_SP, scale)
            assertTrue(sp >= CalendarCellLayout.MIN_SP && sp <= CalendarCellLayout.MONEY_SP)
        }
    }
}
