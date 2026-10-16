package com.cashflow.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing tokens: 4, 8, 12, 16, 24 dp (see [Tokens.SPACING_DP]). */
object Spacing {
    val xs: Dp = Tokens.SPACING_DP[0].dp
    val s: Dp = Tokens.SPACING_DP[1].dp
    val m: Dp = Tokens.SPACING_DP[2].dp
    val l: Dp = Tokens.SPACING_DP[3].dp
    val xl: Dp = Tokens.SPACING_DP[4].dp
}

/** Shape tokens that are not Material slots. */
object ShapeTokens {
    val cardCorner: Dp = Tokens.CARD_CORNER_DP.dp
    val cardPadding: Dp = Tokens.CARD_PADDING_DP.dp
    val rowMinHeight: Dp = Tokens.ROW_MIN_HEIGHT_DP.dp
}

/** Motion tokens: fast 150 ms and standard 250 ms, both with the standard easing. */
object Motion {
    const val FAST_MS = Tokens.MOTION_FAST_MS
    const val STANDARD_MS = Tokens.MOTION_STANDARD_MS

    /** Material "standard" easing. */
    val Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> fast(): TweenSpec<T> = tween(FAST_MS, easing = Easing)
    fun <T> standard(): TweenSpec<T> = tween(STANDARD_MS, easing = Easing)
}

/**
 * Content whose boxes have a fixed size (the tab row, the strip cells, a screen header next to an action) keeps the user's
 * font size up to [max] and no further, like the bottom bar: at 2.0 the labels would not fit their boxes at all.
 */
@androidx.compose.runtime.Composable
fun CappedFontScale(max: Float = 1.3f, content: @androidx.compose.runtime.Composable () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, minOf(density.fontScale, max)),
        content = content,
    )
}
