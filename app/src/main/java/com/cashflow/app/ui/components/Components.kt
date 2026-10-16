package com.cashflow.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import com.cashflow.app.ui.theme.Spacing
import com.cashflow.app.ui.theme.cashflow
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cashflow.app.engine.CountUp
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.RouletteTicks
import com.cashflow.app.ui.Haptics
import com.cashflow.app.ui.theme.cashflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Buttons and tappable cards shrink to 0.96 while pressed. Only the DRAWING is scaled: scaling the whole element (a
 * graphics layer) would shrink its touch area under the finger, and a press near an edge could then end outside the
 * button and not count as a tap.
 */
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = com.cashflow.app.ui.theme.Motion.fast(),
        label = "pressScale",
    )
    drawWithContent {
        val s = scale
        if (s == 1f) {
            drawContent()
        } else {
            scale(s, s, pivot = center) { this@drawWithContent.drawContent() }
        }
    }
}

enum class AppButtonStyle { Primary, Tonal, Outlined }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: AppButtonStyle = AppButtonStyle.Primary,
    colors: ButtonColors? = null,
    textStyle: TextStyle? = null,
    /** Label colour of the Tonal / Outlined styles (e.g. red for a destructive action). */
    contentColor: Color? = null,
    /** A custom corner shape (default: the usual pill). */
    shape: androidx.compose.ui.graphics.Shape? = null,
) {
    val source = remember { MutableInteractionSource() }
    val m = modifier.pressScale(source).heightIn(min = 56.dp)
    when (style) {
        AppButtonStyle.Primary -> Button(
            onClick, m, enabled = enabled, elevation = null, interactionSource = source,
            shape = shape ?: MaterialTheme.shapes.medium,
            colors = colors ?: ButtonDefaults.buttonColors(),
        ) { Text(text, textAlign = TextAlign.Center, style = textStyle ?: LocalTextStyle.current) }
        AppButtonStyle.Tonal -> FilledTonalButton(
            onClick, m, enabled = enabled, elevation = null, interactionSource = source,
            shape = shape ?: MaterialTheme.shapes.medium,
            colors = if (contentColor != null) ButtonDefaults.filledTonalButtonColors(contentColor = contentColor) else ButtonDefaults.filledTonalButtonColors(),
        ) {
            Text(text, textAlign = TextAlign.Center)
        }
        AppButtonStyle.Outlined -> OutlinedButton(
            onClick, m, enabled = enabled, interactionSource = source,
            shape = shape ?: MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = if (contentColor != null) ButtonDefaults.outlinedButtonColors(contentColor = contentColor) else ButtonDefaults.outlinedButtonColors(),
        ) { Text(text, textAlign = TextAlign.Center, style = textStyle ?: LocalTextStyle.current) }
    }
}

/** Flat rounded card with a hairline border and no shadow. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(
        containerColor = if (elevated) MaterialTheme.cashflow.elevated else MaterialTheme.colorScheme.surface,
    )
    val border = BorderStroke(1.dp, MaterialTheme.cashflow.hairline)
    val shape = MaterialTheme.shapes.medium // the one card shape (12 dp)
    if (onClick == null) {
        Card(modifier, shape = shape, colors = colors, border = border, elevation = CardDefaults.cardElevation(0.dp), content = content)
    } else {
        val source = remember { MutableInteractionSource() }
        Card(
            onClick = onClick,
            modifier = modifier.pressScale(source),
            shape = shape,
            colors = colors,
            border = border,
            elevation = CardDefaults.cardElevation(0.dp),
            interactionSource = source,
            content = content,
        )
    }
}

/** A padded card that groups one settings section. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AppCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m), content = content)
    }
}

/** Lets a child use the full width even inside horizontally padded content (e.g. the keypad in a sheet). */
fun Modifier.fullBleed(horizontalPadding: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val extra = (horizontalPadding * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-horizontalPadding.roundToPx(), 0) }
}

/** Plural string from resources (avoids depending on the experimental Compose helper). */
@Composable
fun quantityString(@androidx.annotation.PluralsRes id: Int, count: Int, vararg args: Any): String =
    androidx.compose.ui.platform.LocalContext.current.resources.getQuantityString(id, count, *args)

/**
 * Text field with its label ABOVE the field instead of floating on the border, so the label can
 * wrap onto several lines at large font sizes without overlapping the outline.
 */
@Composable
fun LabeledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    isError: Boolean = false,
    placeholder: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    /** Applied to the text field itself (e.g. a focus requester). */
    fieldModifier: Modifier = Modifier,
) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        CompositionLocalProvider(LocalContentColor provides if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { label() }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().then(fieldModifier),
            singleLine = singleLine,
            isError = isError,
            placeholder = placeholder,
            supportingText = supportingText,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
        )
    }
}

/** A selected chip is filled with the accent (3:1 or more against the background) and its label turns dark/white. */
@Composable
fun selectedChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
)

/**
 * The name of an item with, when it belongs to a repeating series, a small circular-arrow icon right after it in the type color
 * (income green, expense red). One-time items show nothing extra.
 */
@Composable
fun NameWithRepeat(name: String, repeating: Boolean, income: Boolean, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (repeating) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.Refresh,
                contentDescription = androidx.compose.ui.res.stringResource(com.cashflow.app.R.string.repeats_label),
                tint = com.cashflow.app.engine.SemanticColor.ofRepeatIcon(if (income) com.cashflow.app.engine.TxKind.INCOME else com.cashflow.app.engine.TxKind.EXPENSE).color(),
                modifier = Modifier.padding(start = 6.dp).size(16.dp),
            )
        }
    }
}

/**
 * One list-row style for items (Upcoming, the day sheet, the series list): a card at least 56 dp tall with a leading kind badge
 * (+ green for money in, - red for money out), the name with its repeat arrow, an optional caption, and the signed amount aligned
 * to the end. A [footer] (tags) goes under the row.
 */
@Composable
fun AppListRow(
    title: String,
    kind: com.cashflow.app.engine.TxKind,
    repeating: Boolean,
    amountText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    amountColor: Color? = null,
    struck: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
) {
    val income = kind == com.cashflow.app.engine.TxKind.INCOME
    val token = com.cashflow.app.engine.SemanticColor.ofKind(kind).color()
    AppCard(modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().heightIn(min = com.cashflow.app.ui.theme.ShapeTokens.rowMinHeight - Spacing.l * 2),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(32.dp).background(token.copy(alpha = 0.16f), MaterialTheme.shapes.small),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Text(if (income) "+" else "\u2212", color = token, style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f)) {
                    NameWithRepeat(title, repeating = repeating, income = income)
                    if (caption != null) {
                        Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    amountText,
                    style = MaterialTheme.typography.titleMedium,
                    color = amountColor ?: token,
                    textDecoration = if (struck) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                )
            }
            footer?.invoke()
        }
    }
}

/** The one section-header style: body-strong text with a little air above it. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(top = Spacing.s).semantics { heading() },
    )
}

/** Resolves a semantic [com.cashflow.app.engine.ColorToken] to the theme's color: green in, red out, normal text otherwise. */
@Composable
fun com.cashflow.app.engine.ColorToken.color(): Color = when (this) {
    com.cashflow.app.engine.ColorToken.INCOME -> MaterialTheme.cashflow.positive
    com.cashflow.app.engine.ColorToken.EXPENSE -> MaterialTheme.cashflow.negative
    com.cashflow.app.engine.ColorToken.NEUTRAL -> MaterialTheme.colorScheme.onSurface
}
