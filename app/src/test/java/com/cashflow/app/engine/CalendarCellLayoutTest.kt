package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarCellLayoutTest {
    private fun row(h: Float, scale: Float = 1f) = CalendarCellLayout.forRow(h, scale)

    private val dayRow = 11f * 1.15f // 12.65 dp at scale 1

    @Test fun a_cell_is_a_day_row_plus_three_equal_slots() {
        assertEquals(3, CalendarCellLayout.SLOTS)
        assertEquals(CalendarCellContent.SLOTS, CalendarCellLayout.SLOTS)
    }

    @Test fun roomy_cells_use_the_biggest_sizes_and_the_slots_fill_the_whole_cell() {
        val m = row(100f)
        assertEquals(16f, m.balanceSp, 0f)
        assertEquals(12f, m.moneySp, 0f)
        assertEquals(11f, m.daySp, 0f)
        assertFalse(m.scrolls)
        assertEquals(dayRow, m.dayRowDp, 0.001f)
        assertEquals((100f - 4f - dayRow) / 3, m.slotDp, 0.001f)
        // day row + three slots + padding add up to the cell: nothing is left empty
        assertEquals(100f, CalendarCellLayout.PAD_DP + m.dayRowDp + 3 * m.slotDp, 0.001f)
        assertEquals(100f, m.rowHeightDp, 0.001f)
    }

    @Test fun the_balance_is_never_smaller_than_the_expense_and_income_lines() {
        for (h in listOf(30f, 45f, 50f, 60f, 70f, 80f, 100f, 140f)) for (scale in listOf(0.85f, 1f, 1.3f, 1.5f, 2f)) {
            val m = row(h, scale)
            assertTrue("$h/$scale", m.balanceSp >= m.moneySp)
            assertTrue("$h/$scale", m.moneySp >= CalendarCellLayout.MIN_SP)
            assertTrue("$h/$scale", m.balanceSp <= CalendarCellLayout.BALANCE_SP && m.moneySp <= CalendarCellLayout.MONEY_SP)
        }
    }

    @Test fun medium_cells_shrink_the_balance_first_and_keep_the_smaller_lines_at_their_maximum() {
        val m = row(60f) // slot (60 - 4 - 12.65) / 3 = 14.45 dp: 12.57 sp
        assertEquals(12.57f, m.balanceSp, 0.01f)
        assertEquals(12f, m.moneySp, 0f)
    }

    @Test fun tight_cells_shrink_every_line_to_what_fits_a_slot() {
        val m = row(50f) // slot 11.12 dp: 9.67 sp
        assertEquals(9.67f, m.balanceSp, 0.01f)
        assertEquals(9.67f, m.moneySp, 0.01f)
        assertFalse(m.scrolls)
    }

    @Test fun text_scale_makes_the_sizes_smaller_for_the_same_cell() {
        val normal = row(100f, 1f)
        val big = row(100f, 2f) // day row 25.3: slot 23.57 dp: 10.25 sp
        assertEquals(10.25f, big.balanceSp, 0.01f)
        assertEquals(10.25f, big.moneySp, 0.01f)
        assertTrue(big.balanceSp < normal.balanceSp)
    }

    @Test fun the_sizes_never_go_below_the_minimum_and_the_grid_scrolls_when_nothing_fits() {
        val m = row(40f) // slot 7.78 dp: 6.8 sp, below 8
        assertTrue(m.scrolls)
        assertEquals(CalendarCellLayout.MIN_SP, m.balanceSp, 0f)
        assertEquals(CalendarCellLayout.MIN_SP, m.moneySp, 0f)
        assertEquals(CalendarCellLayout.neededHeightDp(1f), m.rowHeightDp, 0.001f)
        assertEquals(4f + dayRow + 3 * 8f * 1.15f, m.rowHeightDp, 0.001f) // 44.25 dp
        val big = row(120f, 2f) // slot 30.2 dp ... at scale 2 the minimum needs 4 + 25.3 + 55.2
        assertFalse(big.scrolls)
        val huge = row(70f, 2f)
        assertTrue(huge.scrolls)
        assertEquals(CalendarCellLayout.neededHeightDp(2f), huge.rowHeightDp, 0.001f)
    }

    @Test fun five_rows_get_taller_cells_than_six() {
        val five = CalendarCellLayout.metrics(500f, 5, 1f)
        val six = CalendarCellLayout.metrics(500f, 6, 1f)
        assertTrue(five.rowHeightDp > six.rowHeightDp)
        assertTrue(five.slotDp > six.slotDp)
    }

    @Test fun the_layout_is_a_pure_function_of_its_inputs() {
        val first = CalendarCellLayout.metrics(432f, 6, 1.3f)
        assertEquals(first, CalendarCellLayout.metrics(432f, 6, 1.3f))
        CalendarCellLayout.metrics(100f, 5, 2f)
        assertEquals(first, CalendarCellLayout.metrics(432f, 6, 1.3f))
    }

    @Test fun degenerate_inputs_do_not_crash() {
        assertTrue(row(0f).scrolls)
        assertTrue(CalendarCellLayout.metrics(500f, 0, 1f).scrolls)
        assertEquals(row(100f, 1f), row(100f, 0f)) // an invalid scale counts as 1
    }

    @Test fun fit_size_shrinks_long_numbers_to_the_cell_width_but_never_below_the_minimum() {
        // cell width 43 dp: "-380" (4 chars) at 16 sp: 43 / (4 * 0.6) = 17.9 -> 16
        assertEquals(16f, CalendarCellLayout.fitSp("-380", 43f, 16f, 1f), 0f)
        // "-1.2k" 5 chars at scale 1: 43 / 3 = 14.33
        assertEquals(14.33f, CalendarCellLayout.fitSp("-1.2k", 43f, 16f, 1f), 0.01f)
        assertEquals(CalendarCellLayout.MIN_SP, CalendarCellLayout.fitSp("-12.5k", 43f, 16f, 1.5f), 0f) // 43 / 5.4 = 7.96 -> 8
        assertEquals(11.03f, CalendarCellLayout.fitSp("-1234", 43f, 13f, 1.3f), 0.01f)
        assertEquals(12f, CalendarCellLayout.fitSp("", 43f, 12f, 1f), 0f)
    }

    @Test fun every_money_line_fits_or_is_at_the_minimum() {
        for (text in listOf("0", "-75", "+30", "1.2k", "-1.2k", "145", "-28", "12M", "-12M")) for (scale in listOf(1f, 1.3f, 2f)) {
            for (max in listOf(CalendarCellLayout.BALANCE_SP, CalendarCellLayout.MONEY_SP)) {
                val sp = CalendarCellLayout.fitSp(text, 43f, max, scale)
                assertTrue(sp >= CalendarCellLayout.MIN_SP && sp <= max)
            }
        }
    }
}
