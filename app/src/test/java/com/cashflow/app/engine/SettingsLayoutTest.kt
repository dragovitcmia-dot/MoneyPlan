package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLayoutTest {
    @Test fun cards_come_in_the_agreed_order_with_developer_tools_last() {
        val l = SettingsPlan.build(startingPointEditable = true, showDeveloperTools = true)
        assertEquals(
            listOf(SettingsCard.INCOME, SettingsCard.REPEATING_PAYMENTS, SettingsCard.STARTING_POINT, SettingsCard.APP_UPDATES, SettingsCard.DEVELOPER_TOOLS),
            l.cards,
        )
    }

    @Test fun developer_tools_can_be_removed_with_one_constant_and_the_rest_keeps_its_order() {
        val l = SettingsPlan.build(showDeveloperTools = false)
        assertEquals(
            listOf(SettingsCard.INCOME, SettingsCard.REPEATING_PAYMENTS, SettingsCard.STARTING_POINT, SettingsCard.APP_UPDATES),
            l.cards,
        )
    }

    @Test fun a_locked_starting_point_is_read_only_with_a_note() {
        val locked = SettingsPlan.build(startingPointEditable = false)
        assertTrue(locked.startingPointReadOnly)
        assertTrue(locked.startingPointNote)
        val open = SettingsPlan.build(startingPointEditable = true)
        assertFalse(open.startingPointReadOnly)
        assertFalse(open.startingPointNote)
    }

    @Test fun the_shipped_defaults_are_editable_starting_point_and_visible_developer_tools() {
        assertTrue(SettingsConfig.STARTING_POINT_EDITABLE)
        assertTrue(SettingsConfig.SHOW_DEVELOPER_TOOLS)
        val l = SettingsPlan.build()
        assertFalse(l.startingPointReadOnly)
        assertEquals(SettingsCard.DEVELOPER_TOOLS, l.cards.last())
    }

    @Test fun there_is_no_regular_income_list_card_any_more() {
        assertEquals(5, SettingsCard.entries.size)
        assertEquals(SettingsCard.INCOME, SettingsPlan.build().cards.first())
    }
}
