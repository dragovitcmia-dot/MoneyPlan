package com.cashflow.app.ui.theme

/**
 * The design tokens as plain numbers (no Compose), so the rules can be unit tested. [Spacing], [Motion] and the typography in
 * Theme.kt turn them into Dp, specs and styles.
 */
object Tokens {
    /** The only spacings: 4, 8, 12, 16, 24 dp. */
    val SPACING_DP = listOf(4, 8, 12, 16, 24)

    /** One card style: the same corner, padding and surface everywhere. */
    const val CARD_CORNER_DP = 12
    const val CARD_PADDING_DP = 16

    /** One list-row style: at least this tall. */
    const val ROW_MIN_HEIGHT_DP = 56

    /** Calendar cells are nearly square: a corner of 2 to 4 dp. */
    const val CELL_CORNER_MIN_DP = 2
    const val CELL_CORNER_MAX_DP = 4

    /** Motion: fast (press feedback, small changes) and standard (sheets, tabs, list items), one easing. */
    const val MOTION_FAST_MS = 150
    const val MOTION_STANDARD_MS = 250

    /** The type scale in sp: hero, title, body, label, caption. Nothing in the app is smaller than 16 sp. */
    const val HERO_SP = 56
    const val TITLE_SP = 24
    const val BODY_SP = 18
    const val LABEL_SP = 16
    const val CAPTION_SP = 16
}
