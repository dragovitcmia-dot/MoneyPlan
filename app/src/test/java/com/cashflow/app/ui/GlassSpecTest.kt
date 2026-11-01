package com.cashflow.app.ui

import com.cashflow.app.ui.theme.GlassSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassSpecTest {
    @Test fun glass_is_on_by_default() {
        assertTrue(GlassSpec.DEFAULT_ENABLED)
    }

    @Test fun it_shows_only_on_android_12_with_the_setting_on_and_no_battery_saver() {
        assertTrue(GlassSpec.active(sdk = 31, batterySaver = false, settingOn = true))
        assertTrue(GlassSpec.active(sdk = 34, batterySaver = false, settingOn = true))
        assertFalse(GlassSpec.active(sdk = 30, batterySaver = false, settingOn = true))
        assertFalse(GlassSpec.active(sdk = 26, batterySaver = false, settingOn = true))
        assertFalse(GlassSpec.active(sdk = 34, batterySaver = true, settingOn = true))
        assertFalse(GlassSpec.active(sdk = 34, batterySaver = false, settingOn = false))
    }

    @Test fun the_look_stays_inside_the_brief() {
        assertTrue(GlassSpec.BLUR_DP <= GlassSpec.MAX_BLUR_DP)
        assertEquals(24, GlassSpec.MAX_BLUR_DP)
        assertTrue(GlassSpec.BORDER_ALPHA in 0.12f..0.16f)
        assertTrue(GlassSpec.HIGHLIGHT_ALPHA in 0.05f..0.07f)
        assertEquals(2, GlassSpec.MAX_BLURRED_SURFACES)
        assertEquals(31, GlassSpec.MIN_BLUR_SDK)
    }

    @Test fun the_tint_picks_the_theme() {
        assertEquals(0xFF121212L, GlassSpec.tint(true))
        assertEquals(0xFFFFFFFFL, GlassSpec.tint(false))
        assertEquals(GlassSpec.DARK_TINT_ALPHA, GlassSpec.tintAlpha(true), 0f)
        assertEquals(GlassSpec.LIGHT_TINT_ALPHA, GlassSpec.tintAlpha(false), 0f)
    }
}
