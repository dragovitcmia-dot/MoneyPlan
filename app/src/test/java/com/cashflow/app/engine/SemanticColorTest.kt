package com.cashflow.app.engine

import com.cashflow.app.ui.theme.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticColorTest {
    @Test fun an_items_kind_decides_its_color() {
        assertEquals(ColorToken.INCOME, SemanticColor.ofKind(TxKind.INCOME))
        assertEquals(ColorToken.EXPENSE, SemanticColor.ofKind(TxKind.EXPENSE))
        assertEquals(ColorToken.INCOME, SemanticColor.ofRepeatIcon(TxKind.INCOME))
        assertEquals(ColorToken.EXPENSE, SemanticColor.ofRepeatIcon(TxKind.EXPENSE))
    }

    @Test fun the_accept_check_is_neutral_for_expenses_and_income_alike() {
        assertEquals(ColorToken.NEUTRAL, SemanticColor.ofConfirmation(TxKind.INCOME))
        assertEquals(ColorToken.NEUTRAL, SemanticColor.ofConfirmation(TxKind.EXPENSE))
        // green stays for money coming in: the item's own amount and icon still follow its kind
        assertEquals(ColorToken.INCOME, SemanticColor.ofKind(TxKind.INCOME))
    }

    @Test fun a_signed_change_is_green_in_red_out() {
        assertEquals(ColorToken.INCOME, SemanticColor.ofChange(30_000))
        assertEquals(ColorToken.EXPENSE, SemanticColor.ofChange(-1))
        assertEquals(ColorToken.NEUTRAL, SemanticColor.ofChange(0))
    }

    @Test fun balances_are_plain_or_red_and_never_green() {
        assertEquals(ColorToken.NEUTRAL, SemanticColor.ofBalance(180_000))
        assertEquals(ColorToken.NEUTRAL, SemanticColor.ofBalance(0))
        assertEquals(ColorToken.EXPENSE, SemanticColor.ofBalance(-1))
        for (v in listOf(-500_000L, -1L, 0L, 1L, 9_999_999L)) assertTrue(SemanticColor.ofBalance(v) != ColorToken.INCOME)
    }

    @Test fun the_upcoming_tabs_are_neutral_red_green() {
        assertEquals(ColorToken.NEUTRAL, SemanticColor.ofTab(UpcomingType.ALL))
        assertEquals(ColorToken.EXPENSE, SemanticColor.ofTab(UpcomingType.EXPENSES))
        assertEquals(ColorToken.INCOME, SemanticColor.ofTab(UpcomingType.INCOME))
    }

    @Test fun controls_that_are_not_money_are_neutral() {
        assertEquals(ColorToken.NEUTRAL, SemanticColor.accent)
    }

    @Test fun the_spacing_scale_is_4_8_12_16_24_and_the_card_is_one_style() {
        assertEquals(listOf(4, 8, 12, 16, 24), Tokens.SPACING_DP)
        assertEquals(12, Tokens.CARD_CORNER_DP)
        assertTrue(Tokens.CARD_PADDING_DP in Tokens.SPACING_DP)
        assertEquals(56, Tokens.ROW_MIN_HEIGHT_DP)
        assertTrue(Tokens.CELL_CORNER_MIN_DP >= 2 && Tokens.CELL_CORNER_MAX_DP <= 4)
    }

    @Test fun motion_has_two_speeds_and_the_type_scale_has_five_sizes_none_below_16() {
        assertEquals(150, Tokens.MOTION_FAST_MS)
        assertEquals(250, Tokens.MOTION_STANDARD_MS)
        val sizes = listOf(Tokens.HERO_SP, Tokens.TITLE_SP, Tokens.BODY_SP, Tokens.LABEL_SP, Tokens.CAPTION_SP)
        assertTrue(sizes.all { it >= 16 })
        assertTrue(sizes.zipWithNext().all { (a, b) -> a >= b })
    }
}
