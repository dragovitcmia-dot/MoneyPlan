package com.cashflow.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Palette (dark-first) ------------------------------------------------------------------
private val DarkBackground = Color(0xFF0B0D10)
private val DarkSurface = Color(0xFF14171C)
private val DarkElevated = Color(0xFF1B1F26)
private val DarkHairline = Color(0xFF262B33)
private val DarkText = Color(0xFFF2F4F7)
private val DarkTextSecondary = Color(0xFF8A93A0)

private val Mint = Color(0xFF3DDC97)
private val MintOnLight = Color(0xFF0B8A5A) // darkened for contrast on white
val Negative = Color(0xFFFF5C6C)

private val LightBackground = Color(0xFFF6F7F9)
private val LightSurface = Color(0xFFFFFFFF)
private val LightHairline = Color(0xFFE3E6EB)
private val LightText = Color(0xFF14171C)
private val LightTextSecondary = Color(0xFF5B6573)

/** Colors the Material scheme has no slot for. */
@Immutable
class CashflowColors(
    val hairline: Color,
    val elevated: Color,
    val negative: Color,
    val positive: Color,
)

private val LocalCashflowColors = staticCompositionLocalOf {
    CashflowColors(DarkHairline, DarkElevated, Negative, Mint)
}

val MaterialTheme.cashflow: CashflowColors
    @Composable @ReadOnlyComposable get() = LocalCashflowColors.current

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Mint,
    onPrimary = DarkBackground,
    primaryContainer = Color(0xFF14382A),
    onPrimaryContainer = Mint,
    secondary = DarkTextSecondary,
    onSecondary = DarkBackground,
    secondaryContainer = DarkElevated,
    onSecondaryContainer = DarkText,
    tertiary = Mint,
    background = DarkBackground,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkElevated,
    onSurfaceVariant = DarkTextSecondary,
    surfaceTint = Color.Transparent,
    outline = Color(0xFF3A414B),
    outlineVariant = DarkHairline,
    error = Negative,
    onError = DarkBackground,
    errorContainer = Color(0xFF3A1A1F),
    onErrorContainer = Negative,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkElevated,
    surfaceContainerHighest = DarkElevated,
    inverseSurface = DarkText,
    inverseOnSurface = DarkBackground,
    inversePrimary = MintOnLight,
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = MintOnLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F5E6),
    onPrimaryContainer = Color(0xFF064D33),
    secondary = LightTextSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEFF3),
    onSecondaryContainer = LightText,
    tertiary = MintOnLight,
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = LightTextSecondary,
    surfaceTint = Color.Transparent,
    outline = Color(0xFFB4BBC6),
    outlineVariant = LightHairline,
    error = Negative,
    onError = Color.White,
    errorContainer = Color(0xFFFFDDE0),
    onErrorContainer = Color(0xFF7A0F1B),
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurface,
    surfaceContainerHighest = Color(0xFFEDEFF3),
    inverseSurface = DarkSurface,
    inverseOnSurface = DarkText,
    inversePrimary = Mint,
)

private val CashflowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** System font everywhere, with tabular figures so amounts line up and don't jitter while counting. */
private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

private val CashflowTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp).tabular(),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold).tabular(),
        displaySmall = base.displaySmall.tabular(),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold).tabular(),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold).tabular(),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold).tabular(),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold).tabular(),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold).tabular(),
        titleSmall = base.titleSmall.tabular(),
        bodyLarge = base.bodyLarge.tabular(),
        bodyMedium = base.bodyMedium.tabular(),
        bodySmall = base.bodySmall.tabular(),
        labelLarge = base.labelLarge.tabular(),
        labelMedium = base.labelMedium.tabular(),
        labelSmall = base.labelSmall.tabular(),
    )
}

/** No dynamic color: the palette above is the brand. Follows the system light/dark setting. */
@Composable
fun CashflowTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val extra = if (dark) {
        CashflowColors(DarkHairline, DarkElevated, Negative, Mint)
    } else {
        CashflowColors(LightHairline, LightSurface, Negative, MintOnLight)
    }
    CompositionLocalProvider(LocalCashflowColors provides extra) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = CashflowTypography,
            shapes = CashflowShapes,
            content = content,
        )
    }
}
