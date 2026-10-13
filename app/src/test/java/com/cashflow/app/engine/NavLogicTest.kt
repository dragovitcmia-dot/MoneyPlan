package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavLogicTest {
    @Test fun selecting_a_tab_while_the_center_is_open_closes_it_and_shows_that_tab() {
        for (tab in 0..3) {
            assertEquals(NavState(tab, false), NavLogic.selectTab(NavState(tab = 1, centerOpen = true), tab))
        }
    }

    @Test fun selecting_the_tab_you_are_on_also_leaves_the_center() {
        assertEquals(NavState(2, false), NavLogic.selectTab(NavState(2, true), 2))
    }

    @Test fun selecting_a_tab_with_the_center_closed_just_switches() {
        assertEquals(NavState(3, false), NavLogic.selectTab(NavState(0, false), 3))
    }

    @Test fun the_bell_opens_and_closes_the_center_without_changing_the_tab() {
        val open = NavLogic.toggleCenter(NavState(2, false))
        assertEquals(NavState(2, true), open)
        assertEquals(NavState(2, false), NavLogic.toggleCenter(open))
    }

    @Test fun back_or_the_arrow_closes_the_center_and_keeps_the_tab() {
        assertEquals(NavState(1, false), NavLogic.closeCenter(NavState(1, true)))
        assertFalse(NavLogic.closeCenter(NavState(1, false)).centerOpen)
    }

    @Test fun opening_a_notification_target_leaves_the_center() {
        assertEquals(NavState(1, false), NavLogic.openTab(NavState(0, true), 1))
    }

    @Test fun a_tab_tap_is_never_ignored() {
        for (center in listOf(true, false)) for (from in 0..3) for (to in 0..3) {
            val next = NavLogic.selectTab(NavState(from, center), to)
            assertEquals(to, next.tab)
            assertFalse(next.centerOpen)
        }
        assertTrue(NavLogic.toggleCenter(NavState()).centerOpen)
    }
}
