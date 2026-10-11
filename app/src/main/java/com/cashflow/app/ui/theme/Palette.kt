package com.cashflow.app.ui.theme

import kotlin.math.pow

/** ARGB colors for one theme as plain numbers, so contrast can be unit tested without Compose. */
class ThemePalette(
    val background: Long,
    val surface: Long,
    val elevated: Long,
    val hairline: Long,
    val text: Long,
    val textSecondary: Long,
    val accent: Long,
    val onAccent: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val negativeText: Long,
    val errorContainer: Long,
    val onErrorContainer: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val expenseFill: Long,
    val incomeFill: Long,
    val buttonLabel: Long,
    val keypadBackground: Long,
    val keyDigit: Long,
    val keyAction: Long,
    val keyPressed: Long,
    val keyText: Long,
    val inverseSurface: Long,
    val inverseOnSurface: Long,
    val inverseAccent: Long,
)

object Palette {
    /**
     * Fills of the big EXPENSE / INCOME buttons, the composer header and the Save button; the same in
     * both themes. Fully saturated, and just dark enough for white text (4.63:1 and 4.64:1).
     */
    const val EXPENSE_RED = 0xFFEB0000L
    const val INCOME_GREEN = 0xFF008800L
    const val KIND_LABEL = 0xFFFFFFFFL

    val dark = ThemePalette(
        background = 0xFF0B0D10, surface = 0xFF14171C, elevated = 0xFF1B1F26, hairline = 0xFF262B33,
        text = 0xFFF2F4F7, textSecondary = 0xFF8A93A0,
        accent = 0xFF3DDC97, onAccent = 0xFF0B0D10,
        primaryContainer = 0xFF14382A, onPrimaryContainer = 0xFF3DDC97,
        negativeText = 0xFFFF5C6C, errorContainer = 0xFF3A1A1F, onErrorContainer = 0xFFFF5C6C,
        secondaryContainer = 0xFF1B1F26, onSecondaryContainer = 0xFFF2F4F7,
        expenseFill = EXPENSE_RED, incomeFill = INCOME_GREEN, buttonLabel = KIND_LABEL,
        keypadBackground = 0xFF010101, keyDigit = 0xFF2D2D2F, keyAction = 0xFF171719, keyPressed = 0xFF474749, keyText = 0xFFFFFFFF,
        inverseSurface = 0xFFF2F4F7, inverseOnSurface = 0xFF0B0D10, inverseAccent = 0xFF087A4F,
    )

    val light = ThemePalette(
        background = 0xFFF6F7F9, surface = 0xFFFFFFFF, elevated = 0xFFFFFFFF, hairline = 0xFFE3E6EB,
        text = 0xFF14171C, textSecondary = 0xFF5B6573,
        accent = 0xFF087A4F, onAccent = 0xFFFFFFFF,
        primaryContainer = 0xFFD7F5E6, onPrimaryContainer = 0xFF064D33,
        negativeText = 0xFFC62839, errorContainer = 0xFFFFDDE0, onErrorContainer = 0xFF7A0F1B,
        secondaryContainer = 0xFFEDEFF3, onSecondaryContainer = 0xFF14171C,
        expenseFill = EXPENSE_RED, incomeFill = INCOME_GREEN, buttonLabel = KIND_LABEL,
        keypadBackground = 0xFFE9E9EC, keyDigit = 0xFFFFFFFF, keyAction = 0xFFD3D3D8, keyPressed = 0xFFC8C8CE, keyText = 0xFF1C1C1E,
        inverseSurface = 0xFF14171C, inverseOnSurface = 0xFFF2F4F7, inverseAccent = 0xFF3DDC97,
    )
}

/** WCAG 2.x contrast. */
object Contrast {
    fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    const val NORMAL_TEXT = 4.5
    const val LARGE_TEXT = 3.0
}

/** A text color drawn on a background somewhere in the app. [large] = 24sp or bigger. */
class TextPair(val name: String, val foreground: Long, val background: Long, val large: Boolean = false) {
    val required get() = if (large) Contrast.LARGE_TEXT else Contrast.NORMAL_TEXT
}

/** Every text/background combination the UI uses, per theme. The unit test checks all of them. */
fun ThemePalette.textPairs(): List<TextPair> = listOf(
    TextPair("text on background", text, background),
    TextPair("text on surface", text, surface),
    TextPair("text on elevated", text, elevated),
    TextPair("secondary text on background", textSecondary, background),
    TextPair("secondary text on surface", textSecondary, surface),
    TextPair("secondary text on elevated", textSecondary, elevated),
    TextPair("accent text on background", accent, background),
    TextPair("accent text on surface", accent, surface),
    TextPair("accent text on elevated", accent, elevated),
    TextPair("negative text on background", negativeText, background),
    TextPair("negative text on surface", negativeText, surface),
    TextPair("negative text on elevated", negativeText, elevated),
    TextPair("big balance (negative) on background", negativeText, background, large = true),
    TextPair("label on accent button", onAccent, accent),
    TextPair("chip label on chip", onPrimaryContainer, primaryContainer),
    TextPair("label on error container", onErrorContainer, errorContainer),
    TextPair("label on secondary container", onSecondaryContainer, secondaryContainer),
    TextPair("white label on EXPENSE fill (Home button, composer header, Save)", buttonLabel, expenseFill),
    TextPair("white label on INCOME fill (Home button, composer header, Save)", buttonLabel, incomeFill),
    TextPair("keypad digit on digit key", keyText, keyDigit, large = true),
    TextPair("keypad digit on digit key (normal size check)", keyText, keyDigit),
    TextPair("keypad digit on pressed key", keyText, keyPressed),
    TextPair("backspace icon on backspace key", keyText, keyAction),
    TextPair("skip swipe label on hairline", text, hairline),
    TextPair("snackbar text", inverseOnSurface, inverseSurface),
    TextPair("snackbar action", inverseAccent, inverseSurface),
)
