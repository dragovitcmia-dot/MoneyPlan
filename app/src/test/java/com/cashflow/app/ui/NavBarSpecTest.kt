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
}
