package com.cashflow.app.ui

import com.cashflow.app.ui.theme.Contrast
import com.cashflow.app.ui.theme.Palette
import com.cashflow.app.ui.theme.ThemePalette
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

    @Test fun the_big_button_labels_pass_the_normal_text_threshold_too() {
        for (p in listOf(Palette.dark, Palette.light)) {
            assertTrue(Contrast.ratio(p.buttonLabel, p.expenseFill) >= 4.5)
            assertTrue(Contrast.ratio(p.buttonLabel, p.incomeFill) >= 4.5)
        }
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

    @Test fun keys_have_a_visible_contour_against_the_sheet() {
        // The keypad sheet is the surface color; keys and their contour must stand out from it.
        for (p in listOf(Palette.dark, Palette.light)) {
            assertTrue(Contrast.ratio(p.keyContour, p.surface) >= 1.3)
            assertTrue(Contrast.ratio(p.keyDigit, p.surface) >= 1.05)
        }
    }
}
