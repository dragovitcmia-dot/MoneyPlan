package com.cashflow.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors the Material scheme has no slot for. All values come from [Palette]. */
@Immutable
class CashflowColors(
    val hairline: Color,
    val elevated: Color,
    /** Red used for text on the app's backgrounds (darker in the light theme to stay readable). */
    val negative: Color,
    val positive: Color,
    val expenseFill: Color,
    val incomeFill: Color,
    val buttonLabel: Color,
    val keypadBackground: Color,
    val keyDigit: Color,
    val keyAction: Color,
    val keyPressed: Color,
    val keyText: Color,
    val dayNegativeFill: Color,
    val daySelectedFill: Color,
    val badge: Color,
    val cellIncome: Color,
)

private fun ThemePalette.extra() = CashflowColors(
    hairline = Color(hairline),
    elevated = Color(elevated),
    negative = Color(negativeText),
    positive = Color(accent),
    expenseFill = Color(expenseFill),
    incomeFill = Color(incomeFill),
    buttonLabel = Color(buttonLabel),
    keypadBackground = Color(keypadBackground),
    keyDigit = Color(keyDigit),
    keyAction = Color(keyAction),
    keyPressed = Color(keyPressed),
    keyText = Color(keyText),
    dayNegativeFill = Color(dayNegativeFill),
    daySelectedFill = Color(daySelectedFill),
    badge = Color(badge),
    cellIncome = Color(cellIncome),
)

private val LocalCashflowColors = staticCompositionLocalOf { Palette.dark.extra() }

val MaterialTheme.cashflow: CashflowColors
    @Composable @ReadOnlyComposable get() = LocalCashflowColors.current

private fun ThemePalette.toDarkScheme(): ColorScheme = darkColorScheme(
    primary = Color(accent), onPrimary = Color(onAccent),
    primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
    secondary = Color(textSecondary), onSecondary = Color(onAccent),
    secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(accent),
    background = Color(background), onBackground = Color(text),
    surface = Color(surface), onSurface = Color(text),
    surfaceVariant = Color(elevated), onSurfaceVariant = Color(textSecondary),
    surfaceTint = Color.Transparent,
    outline = Color(textSecondary), outlineVariant = Color(hairline),
    error = Color(negativeText), onError = Color(onAccent),
    errorContainer = Color(errorContainer), onErrorContainer = Color(onErrorContainer),
    surfaceContainerLowest = Color(background), surfaceContainerLow = Color(surface),
    surfaceContainer = Color(surface), surfaceContainerHigh = Color(elevated), surfaceContainerHighest = Color(elevated),
    inverseSurface = Color(inverseSurface), inverseOnSurface = Color(inverseOnSurface), inversePrimary = Color(inverseAccent),
)

private fun ThemePalette.toLightScheme(): ColorScheme = lightColorScheme(
    primary = Color(accent), onPrimary = Color(onAccent),
    primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
    secondary = Color(textSecondary), onSecondary = Color(onAccent),
    secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(accent),
    background = Color(background), onBackground = Color(text),
    surface = Color(surface), onSurface = Color(text),
    surfaceVariant = Color(secondaryContainer), onSurfaceVariant = Color(textSecondary),
    surfaceTint = Color.Transparent,
    outline = Color(textSecondary), outlineVariant = Color(hairline),
    error = Color(negativeText), onError = Color(onAccent),
    errorContainer = Color(errorContainer), onErrorContainer = Color(onErrorContainer),
    surfaceContainerLowest = Color(surface), surfaceContainerLow = Color(surface),
    surfaceContainer = Color(surface), surfaceContainerHigh = Color(surface), surfaceContainerHighest = Color(secondaryContainer),
    inverseSurface = Color(inverseSurface), inverseOnSurface = Color(inverseOnSurface), inversePrimary = Color(inverseAccent),
)

private val CashflowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** System font; tabular figures so amounts line up; nothing smaller than 16sp. */
private fun style(size: Int, weight: FontWeight = FontWeight.Normal, letterSpacing: TextUnit = 0.sp) = TextStyle(
    fontSize = size.sp,
    lineHeight = (size * 1.3f).sp,
    fontWeight = weight,
    letterSpacing = letterSpacing,
    fontFeatureSettings = "tnum",
)

private val CashflowTypography = Typography(
    displayLarge = style(56, FontWeight.SemiBold, (-1).sp),
    displayMedium = style(44, FontWeight.SemiBold),
    displaySmall = style(36, FontWeight.SemiBold),
    headlineLarge = style(32, FontWeight.SemiBold),
    headlineMedium = style(28, FontWeight.SemiBold),
    headlineSmall = style(24, FontWeight.SemiBold),
    titleLarge = style(22, FontWeight.SemiBold),
    titleMedium = style(18, FontWeight.SemiBold),
    titleSmall = style(16, FontWeight.Medium),
    bodyLarge = style(18),
    bodyMedium = style(16),
    bodySmall = style(16),
    labelLarge = style(16, FontWeight.Medium),
    labelMedium = style(16, FontWeight.Medium),
    labelSmall = style(16, FontWeight.Medium),
)

/** No dynamic color: the palette is the brand. Follows the system light/dark setting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashflowTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) Palette.dark else Palette.light
    CompositionLocalProvider(
        LocalCashflowColors provides palette.extra(),
        LocalMinimumInteractiveComponentSize provides 56.dp, // touch targets of at least 56dp
    ) {
        MaterialTheme(
            colorScheme = if (dark) palette.toDarkScheme() else palette.toLightScheme(),
            typography = CashflowTypography,
            shapes = CashflowShapes,
            content = content,
        )
    }
}
