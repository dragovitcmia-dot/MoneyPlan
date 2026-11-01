package com.cashflow.app.ui.theme

/**
 * The frosted-glass look of surfaces that float over content (bottom bar, sheets, notification center), as plain
 * numbers so a unit test can hold them. Text on glass must stay readable over ANY backdrop, so the tint is
 * composited over pure white and pure black in the ContrastTest and the alphas were raised until both pass.
 */
object GlassSpec {
    /** Dark glass is tinted with the dark surface (#121212), light glass with white. */
    const val DARK_TINT = 0xFF121212L
    const val LIGHT_TINT = 0xFFFFFFFFL

    /** Tint opacity. Worst case (a white or black backdrop behind the glass) decides it; see the ContrastTest. */
    const val DARK_TINT_ALPHA = 0.90f
    const val LIGHT_TINT_ALPHA = 0.93f

    const val BLUR_DP = 20
    const val MAX_BLUR_DP = 24

    /** 1 dp edge: white (dark) / black (light) at this opacity. */
    const val BORDER_ALPHA = 0.14f

    /** Faint light on the top edge, fading to nothing. */
    const val HIGHLIGHT_ALPHA = 0.06f

    /** Blur is a performance cost, so at most this many blurred surfaces exist at once. */
    const val MAX_BLURRED_SURFACES = 2

    /** RenderEffect blur needs Android 12. */
    const val MIN_BLUR_SDK = 31

    const val DEFAULT_ENABLED = true

    fun tint(dark: Boolean): Long = if (dark) DARK_TINT else LIGHT_TINT

    fun tintAlpha(dark: Boolean): Float = if (dark) DARK_TINT_ALPHA else LIGHT_TINT_ALPHA

    /** The effect shows only on Android 12+, with the setting on, and not in battery saver; otherwise the opaque surfaces stay. */
    fun active(sdk: Int, batterySaver: Boolean, settingOn: Boolean): Boolean =
        settingOn && !batterySaver && sdk >= MIN_BLUR_SDK
}

/** Text and icon colors that can sit on glass, in the pairs the ContrastTest checks over both pure backdrops. */
fun ThemePalette.glassTextColors(): List<Pair<String, Long>> = listOf(
    "text" to text, "secondary text" to textSecondary, "accent" to accent,
    "income" to incomeText, "negative" to negativeText,
)

fun ThemePalette.glassGraphicColors(): List<Pair<String, Long>> = listOf(
    "icon accent" to accent, "icon secondary" to textSecondary, "income" to incomeText, "negative" to negativeText, "text" to text,
)
