package com.cashflow.app.ui

import com.cashflow.app.ui.components.NavBarSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavBarSpecTest {
    @Test fun every_item_is_a_full_height_target_of_at_least_48dp() {
        assertEquals(64, NavBarSpec.BAR_HEIGHT_DP)
        assertTrue(NavBarSpec.BAR_HEIGHT_DP >= NavBarSpec.MIN_TARGET_DP)
        assertEquals(26, NavBarSpec.ICON_DP)
    }

    @Test fun four_equal_cells_are_at_least_a_fifth_of_the_screen_even_on_the_narrowest_phone() {
        for (widthDp in listOf(320.0, 360.0, 411.0, 600.0)) {
            val cell = NavBarSpec.cellWidthDp(widthDp, 4)
            assertEquals(widthDp / 4, cell, 1e-9)
            assertTrue("$widthDp dp", cell >= widthDp * NavBarSpec.MIN_WIDTH_SHARE)
        }
    }

    @Test fun five_equal_cells_are_each_a_fifth_and_at_least_48dp_wide_down_to_240dp() {
        assertEquals(5, com.cashflow.app.engine.TabIndex.COUNT)
        assertEquals(listOf(0, 1, 2, 3, 4), listOf(
            com.cashflow.app.engine.TabIndex.HOME, com.cashflow.app.engine.TabIndex.CALENDAR, com.cashflow.app.engine.TabIndex.UPCOMING,
            com.cashflow.app.engine.TabIndex.HISTORY, com.cashflow.app.engine.TabIndex.SETTINGS,
        ))
        for (widthDp in listOf(240.0, 320.0, 360.0, 411.0, 600.0)) {
            val cell = NavBarSpec.cellWidthDp(widthDp, 5)
            assertEquals(widthDp / 5, cell, 1e-9)
            assertTrue("$widthDp dp: $cell", cell >= widthDp * NavBarSpec.MIN_WIDTH_SHARE - 1e-9)
            assertTrue("$widthDp dp: $cell", cell >= NavBarSpec.MIN_ITEM_WIDTH_DP)
        }
    }
}
