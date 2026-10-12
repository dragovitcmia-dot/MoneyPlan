package com.cashflow.app.ui

import com.cashflow.app.ui.theme.Contrast
import com.cashflow.app.ui.theme.Palette
import com.cashflow.app.ui.theme.ThemePalette
import com.cashflow.app.ui.theme.graphicPairs
import com.cashflow.app.ui.theme.textPairs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG contrast of every text/background pair the UI uses. Thresholds are fixed: 4.5, or 3 for large text. */
class ContrastTest {
    private fun check(name: String, palette: ThemePalette) {
        val failures = palette.textPairs().filter { Contrast.ratio(it.foreground, it.background) < it.required }
            .map { "${it.name}: %.2f < ${it.required}".format(Contrast.ratio(it.foreground, it.background)) }
        assertTrue("$name theme has low-contrast pairs:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun dark_theme_text_pairs_meet_wcag_aa() = check("dark", Palette.dark)

    @Test fun light_theme_text_pairs_meet_wcag_aa() = check("light", Palette.light)

    private fun checkGraphics(name: String, palette: ThemePalette) {
        val failures = palette.graphicPairs().filter { Contrast.ratio(it.foreground, it.background) < it.required }
            .map { "${it.name}: %.2f < ${it.required}".format(Contrast.ratio(it.foreground, it.background)) }
        assertTrue("$name theme has low-contrast graphics:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun dark_theme_calendar_graphics_meet_3_to_1() = checkGraphics("dark", Palette.dark)

    @Test fun light_theme_calendar_graphics_meet_3_to_1() = checkGraphics("light", Palette.light)

    @Test fun every_calendar_cell_text_pair_is_in_the_text_check_for_both_themes() {
        for (p in listOf(Palette.dark, Palette.light)) {
            val names = p.textPairs().map { it.name }
            assertTrue(names.count { it.startsWith("calendar ") } >= 10)
            assertTrue(p.graphicPairs().size >= 21)
            assertTrue(p.graphicPairs().any { it.name.startsWith("accept check") } && p.graphicPairs().any { it.name.startsWith("reject cross") })
        }
        assertEquals(3.0, Contrast.GRAPHIC, 0.0)
    }

    @Test fun white_text_on_the_kind_fills_reaches_4_5_to_1_in_both_themes() {
        for (p in listOf(Palette.dark, Palette.light)) {
            assertEquals(0xFFFFFFFFL, p.buttonLabel)
            assertTrue(Contrast.ratio(p.buttonLabel, p.expenseFill) >= 4.5)
            assertTrue(Contrast.ratio(p.buttonLabel, p.incomeFill) >= 4.5)
        }
    }

    @Test fun the_kind_fills_are_the_same_in_both_themes_and_strongly_saturated() {
        assertEquals(Palette.dark.expenseFill, Palette.light.expenseFill)
        assertEquals(Palette.dark.incomeFill, Palette.light.incomeFill)
        // Pure hues: red has no green/blue, green has no red/blue.
        assertEquals(0L, Palette.EXPENSE_RED and 0xFFFFL)
        assertEquals(0L, Palette.INCOME_GREEN and 0xFF00FFL)
        assertTrue(((Palette.EXPENSE_RED shr 16) and 0xFF) >= 0xD9) // at least as red as the starting #D92D20
        assertTrue(((Palette.INCOME_GREEN shr 8) and 0xFF) >= 0x85) // at least as green as the starting #12853B
    }

    @Test fun thresholds_were_not_lowered() {
        assertEquals(4.5, Contrast.NORMAL_TEXT, 0.0)
        assertEquals(3.0, Contrast.LARGE_TEXT, 0.0)
        assertTrue(Palette.dark.textPairs().any { it.large } && Palette.dark.textPairs().any { !it.large })
    }

    @Test fun contrast_math_matches_known_values() {
        assertEquals(21.0, Contrast.ratio(0xFF000000, 0xFFFFFFFF), 0.001)
        assertEquals(1.0, Contrast.ratio(0xFF336699, 0xFF336699), 0.001)
        assertEquals(4.54, Contrast.ratio(0xFF767676, 0xFFFFFFFF), 0.01) // the classic #767676 on white
    }

    @Test fun keypad_uses_the_measured_samsung_style_colors() {
        assertEquals(0xFF010101L, Palette.dark.keypadBackground)
        assertEquals(0xFF2D2D2FL, Palette.dark.keyDigit)
        assertEquals(0xFF171719L, Palette.dark.keyAction)
        assertEquals(0xFF474749L, Palette.dark.keyPressed)
        assertEquals(0xFFE9E9ECL, Palette.light.keypadBackground)
        assertEquals(0xFFFFFFFFL, Palette.light.keyDigit)
        assertEquals(0xFFD3D3D8L, Palette.light.keyAction)
        assertEquals(0xFFC8C8CEL, Palette.light.keyPressed)
        assertEquals(0xFF1C1C1EL, Palette.light.keyText)
    }

    @Test fun keys_stand_out_from_the_keypad_background_by_fill_alone() {
        // No borders: shape comes only from fill contrast and the gaps between keys.
        for (p in listOf(Palette.dark, Palette.light)) {
            assertTrue(Contrast.ratio(p.keyDigit, p.keypadBackground) >= 1.15)
            assertTrue(Contrast.ratio(p.keyAction, p.keypadBackground) >= 1.1)
            assertTrue(Contrast.ratio(p.keyAction, p.keyDigit) >= 1.1) // backspace reads as a different key
            assertTrue(Contrast.ratio(p.keyPressed, p.keyDigit) >= 1.1) // pressed is visibly different
        }
    }
}
