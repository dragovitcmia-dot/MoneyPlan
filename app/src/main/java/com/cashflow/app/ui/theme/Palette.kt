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
    /** Fill of a calendar day whose closing balance is negative. */
    val dayNegativeFill: Long,
    /** Fill of the selected calendar day (it also gets an accent outline). */
    val daySelectedFill: Long,
    /** The "waiting for you" dot on a calendar day (not text, so 3:1 is enough). */
    val badge: Long,
    /** Income amounts and dots inside calendar cells: a little darker than [accent] in the light theme so it stays readable on the red tint. */
    val cellIncome: Long,
)

object Palette {
    /**
     * Fills of the big EXPENSE / INCOME buttons, the composer header and the Save button; the same in
     * both themes. Fully saturated, and just dark enough for white text (4.63:1 and 4.64:1).
     */
    const val EXPENSE_RED = 0xFFEB0000L
    const val INCOME_GREEN = 0xFF008800L
    const val KIND_LABEL = 0xFFFFFFFFL

    /**
     * Day-of-month numbers in the calendar are faint on purpose (the money numbers are the content); only today's
     * number is full contrast and bold. Decorative hierarchy: the ContrastTest asks 3:1 for these, not 4.5:1.
     */
    const val DAY_NUMBER_ALPHA = 0.55f

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
        dayNegativeFill = 0xFF3A1A1F, daySelectedFill = 0xFF1D2A27, badge = 0xFFFFB020, cellIncome = 0xFF3DDC97,
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
        dayNegativeFill = 0xFFFFE2E5, daySelectedFill = 0xFFE4F3EC, badge = 0xFFB45F00, cellIncome = 0xFF066B44,
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

    /** Non-text graphics (dots, outlines). */
    const val GRAPHIC = 3.0
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
    // Calendar cells (day numbers, amounts, the banner and the "short by" tag reuse the pairs above).
    TextPair("calendar income amount on background", cellIncome, background),
    TextPair("calendar income amount on surface", cellIncome, surface),
    TextPair("calendar text on a negative day", text, dayNegativeFill),
    TextPair("calendar secondary text (past day number) on a negative day", textSecondary, dayNegativeFill),
    TextPair("calendar expense amount on a negative day", negativeText, dayNegativeFill),
    TextPair("calendar income amount on a negative day", cellIncome, dayNegativeFill),
    TextPair("calendar text on the selected day", text, daySelectedFill),
    TextPair("calendar secondary text (past day number) on the selected day", textSecondary, daySelectedFill),
    TextPair("calendar expense amount on the selected day", negativeText, daySelectedFill),
    TextPair("calendar income amount on the selected day", cellIncome, daySelectedFill),
    TextPair("today's day number on the today circle", onAccent, accent),
    // The day's balance (full contrast, never dimmed): normal text on every cell background, red when negative.
    TextPair("calendar balance on background", text, background),
    TextPair("calendar balance on surface", text, surface),
    TextPair("calendar balance on the selected day", text, daySelectedFill),
    TextPair("calendar balance on a negative day (not shown in normal text, kept as a floor)", text, dayNegativeFill),
    TextPair("calendar negative balance on background", negativeText, background),
    TextPair("calendar negative balance on a negative day", negativeText, dayNegativeFill),
    TextPair("calendar negative balance on the selected day", negativeText, daySelectedFill),
)

/** [fg] drawn over [bg] with [alpha] opacity, as an opaque ARGB color. */
fun blend(fg: Long, bg: Long, alpha: Float): Long {
    fun ch(shift: Int): Long {
        val f = (fg shr shift) and 0xFF
        val b = (bg shr shift) and 0xFF
        return Math.round(f * alpha + b * (1 - alpha)).toLong()
    }
    return 0xFF000000L or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * The faint day-of-month numbers on each cell background. They are decorative hierarchy, so the bar is 3:1 (the
 * large-text bar), documented here and checked in the ContrastTest.
 */
fun ThemePalette.faintDayNumberPairs(): List<TextPair> = listOf(
    "background" to background, "surface" to surface, "a negative day" to dayNegativeFill, "the selected day" to daySelectedFill,
).map { (name, bg) -> TextPair("faint day number on $name", blend(text, bg, Palette.DAY_NUMBER_ALPHA), bg, large = true) }

/** A graphic (dot, outline, tint) against what it sits on; WCAG asks for 3:1 for such non-text parts. */
class GraphicPair(val name: String, val foreground: Long, val background: Long, val required: Double = Contrast.GRAPHIC)

fun ThemePalette.graphicPairs(): List<GraphicPair> = listOf(
    GraphicPair("waiting dot on background", badge, background),
    GraphicPair("waiting dot on a negative day", badge, dayNegativeFill),
    GraphicPair("waiting dot on the selected day", badge, daySelectedFill),
    GraphicPair("waiting dot on surface", badge, surface),
    GraphicPair("expense dot on background", negativeText, background),
    GraphicPair("expense dot on a negative day", negativeText, dayNegativeFill),
    GraphicPair("expense dot on the selected day", negativeText, daySelectedFill),
    GraphicPair("income dot on background", cellIncome, background),
    GraphicPair("income dot on a negative day", cellIncome, dayNegativeFill),
    GraphicPair("income dot on the selected day", cellIncome, daySelectedFill),
    // Accept / reject icons (the INCOME green and EXPENSE red) drawn on the cards and sheets: the swipe hint and the
    // question icons. On the coloured buttons themselves the icon is white (see the text pairs for the kind fills).
    GraphicPair("reject cross (expense red) on surface", expenseFill, surface),
    GraphicPair("reject cross (expense red) on background", expenseFill, background),
    GraphicPair("accept check (income green) on surface", incomeFill, surface),
    GraphicPair("accept check (income green) on background", incomeFill, background),
    GraphicPair("white icon on the reject fill", buttonLabel, expenseFill),
    GraphicPair("white icon on the accept fill", buttonLabel, incomeFill),
    GraphicPair("selection outline on background", accent, background),
    GraphicPair("selection outline on a negative day", accent, dayNegativeFill),
    GraphicPair("selection outline on the selected day", accent, daySelectedFill),
    // A negative day must be recognisable by its fill alone (the colour is not the only cue: dots and text help too).
    GraphicPair("negative day fill against the background", dayNegativeFill, background, required = 1.12),
    GraphicPair("selected day fill against the background", daySelectedFill, background, required = 1.05),
)
