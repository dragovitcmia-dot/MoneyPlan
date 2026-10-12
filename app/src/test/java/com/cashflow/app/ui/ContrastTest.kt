package com.cashflow.app.ui

import com.cashflow.app.ui.theme.Contrast
import com.cashflow.app.ui.theme.Palette
import com.cashflow.app.ui.theme.ThemePalette
import com.cashflow.app.ui.theme.blend
import com.cashflow.app.ui.theme.faintDayNumberPairs
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
        assertEquals(0xFF050505L, Palette.dark.keypadBackground)
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

    @Test fun calendar_balances_and_todays_number_reach_4_5_to_1() {
        for (p in listOf(Palette.dark, Palette.light)) {
            val pairs = p.textPairs()
            val balances = pairs.filter { it.name.startsWith("calendar balance") || it.name.startsWith("calendar negative balance") }
            assertTrue(balances.size >= 7)
            for (b in balances) {
                assertTrue("${b.name} is a normal-size text pair", !b.large)
                assertTrue("${b.name}: %.2f".format(Contrast.ratio(b.foreground, b.background)), Contrast.ratio(b.foreground, b.background) >= 4.5)
            }
            val today = pairs.single { it.name.startsWith("today's day number") }
            assertTrue(Contrast.ratio(today.foreground, today.background) >= 4.5)
        }
    }

    /**
     * Day-of-month numbers are faint on purpose (decorative hierarchy: the money is the content), so they are held to
     * 3:1 instead of 4.5:1. They are still clearly weaker than the balances.
     */
    @Test fun faint_day_numbers_reach_3_to_1_but_stay_weaker_than_the_balances() {
        assertEquals(0.55f, Palette.DAY_NUMBER_ALPHA, 0f)
        for (p in listOf(Palette.dark, Palette.light)) {
            val faint = p.faintDayNumberPairs()
            assertEquals(4, faint.size)
            for (f in faint) {
                assertTrue(f.large) // the 3:1 bar
                assertTrue("${f.name}: %.2f".format(Contrast.ratio(f.foreground, f.background)), Contrast.ratio(f.foreground, f.background) >= 3.0)
                assertTrue(Contrast.ratio(p.text, f.background) > Contrast.ratio(f.foreground, f.background))
            }
        }
    }

    @Test fun blend_mixes_by_alpha() {
        assertEquals(0xFF808080L, blend(0xFFFFFFFF, 0xFF000000, 0.5f) and 0xFFFFFFFFL)
        assertEquals(0xFFFFFFFFL, blend(0xFFFFFFFF, 0xFF000000, 1f))
        assertEquals(0xFF000000L, blend(0xFFFFFFFF, 0xFF000000, 0f))
    }

    private fun neutral(c: Long) = ((c shr 16) and 0xFF) == ((c shr 8) and 0xFF) && ((c shr 8) and 0xFF) == (c and 0xFF)

    @Test fun the_dark_theme_has_no_blue_cast() {
        val d = Palette.dark
        for ((name, color) in listOf(
            "background" to d.background, "surface" to d.surface, "elevated" to d.elevated, "hairline" to d.hairline,
            "text" to d.text, "secondary text" to d.textSecondary, "secondary container" to d.secondaryContainer,
            "on secondary container" to d.onSecondaryContainer, "numpad band" to d.keypadBackground,
            "inverse surface" to d.inverseSurface, "inverse on surface" to d.inverseOnSurface,
        )) {
            assertTrue("$name 0x${color.toString(16)} is not a neutral gray", neutral(color))
        }
    }

    @Test fun the_numpad_band_merges_with_the_dark_background_and_surfaces_step_up_in_brightness() {
        val d = Palette.dark
        assertEquals(0xFF050505L, d.background)
        assertEquals(d.background, d.keypadBackground)
        assertEquals(0xFF121212L, d.surface)
        assertEquals(0xFF1A1A1AL, d.elevated)
        assertTrue(Contrast.luminance(d.surface) > Contrast.luminance(d.background))
        assertTrue(Contrast.luminance(d.elevated) > Contrast.luminance(d.surface))
        // the keys still stand out from the (now black) band
        assertTrue(Contrast.ratio(d.keyDigit, d.keypadBackground) >= 1.15)
    }

    @Test fun the_light_theme_is_unchanged() {
        val l = Palette.light
        assertEquals(0xFFF6F7F9L, l.background)
        assertEquals(0xFFFFFFFFL, l.surface)
        assertEquals(0xFFE9E9ECL, l.keypadBackground)
        assertEquals(0xFF14171CL, l.text)
    }

    @Test fun selected_chips_are_filled_with_the_accent_and_clearly_differ_from_unselected_ones() {
        for ((name, p) in listOf("dark" to Palette.dark, "light" to Palette.light)) {
            // selected: accent fill against the sheet background; unselected: transparent, outlined in the secondary text color
            assertTrue("$name selected fill vs background", Contrast.ratio(p.accent, p.background) >= 3.0)
            assertTrue("$name selected fill vs surface", Contrast.ratio(p.accent, p.surface) >= 3.0)
            assertTrue("$name selected label", Contrast.ratio(p.onAccent, p.accent) >= 4.5)
            assertTrue("$name unselected outline", Contrast.ratio(p.textSecondary, p.background) >= 3.0)
            assertTrue("$name unselected label", Contrast.ratio(p.text, p.background) >= 4.5)
        }
    }
}
