package com.cashflow.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.RouletteTicks
import com.cashflow.app.ui.Haptics
import com.cashflow.app.ui.theme.cashflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Buttons and tappable cards shrink to 0.96 while pressed. The scale is read in the draw phase. */
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
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
) {
    val source = remember { MutableInteractionSource() }
    val m = modifier.pressScale(source).heightIn(min = 56.dp)
    when (style) {
        AppButtonStyle.Primary -> Button(
            onClick, m, enabled = enabled, elevation = null, interactionSource = source,
            colors = colors ?: ButtonDefaults.buttonColors(),
        ) { Text(text, textAlign = TextAlign.Center) }
        AppButtonStyle.Tonal -> FilledTonalButton(onClick, m, enabled = enabled, elevation = null, interactionSource = source) {
            Text(text, textAlign = TextAlign.Center)
        }
        AppButtonStyle.Outlined -> OutlinedButton(
            onClick, m, enabled = enabled, interactionSource = source,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) { Text(text, textAlign = TextAlign.Center) }
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
    val shape = MaterialTheme.shapes.large
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
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/** Strong deceleration: fast start, slow arrival. */
private val CountEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private const val COUNT_MILLIS = 1100

private fun valueAt(from: Long, to: Long, progress: Float): Long =
    if (progress >= 1f) to else from + ((to - from) * progress.toDouble()).toLong()

/**
 * The big Available number. It counts from the previous value to the new one (first value is shown
 * as-is, never counted up from zero) with a light haptic tick each time it crosses an evenly spaced
 * value step, and a stronger tick on arrival. A new target interrupts the count and drops pending
 * ticks. The animated value is read only in this composable, so just this number is redrawn on each
 * frame, not the screen around it. The text shrinks so it always fits on one line.
 */
@Composable
fun AnimatedAmountText(target: Long, haptics: Haptics, color: (Long) -> Color, modifier: Modifier = Modifier) {
    var from by remember { mutableLongStateOf(target) }
    var to by remember { mutableLongStateOf(target) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        val start = valueAt(from, to, progress.value)
        from = start
        to = target
        val steps = RouletteTicks.stepCount(start, target)
        progress.snapTo(0f)
        coroutineScope {
            val ticker = launch {
                var passedBefore = 0
                var ticks = 0
                snapshotFlow { progress.value }.collect { p ->
                    val passed = RouletteTicks.thresholdsPassed(start, target, valueAt(start, target, p), steps)
                    if (passed > passedBefore) {
                        passedBefore = passed
                        if (ticks < RouletteTicks.MAX_TICKS - 1) { // one slot stays free for the arrival tick
                            ticks++
                            haptics.tick()
                        }
                    }
                }
            }
            progress.animateTo(1f, tween(COUNT_MILLIS, easing = CountEasing))
            ticker.cancel()
        }
        haptics.arrive() // only reached when the count finished (an interrupted count is cancelled above)
    }

    val shown = valueAt(from, to, progress.value)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = MaterialTheme.typography.displayLarge
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxWidthPx = constraints.maxWidth
        val fontSize = remember(from, to, maxWidthPx, density.fontScale, density.density) {
            val widest = listOf(Money.formatDisplay(from), Money.formatDisplay(to)).maxBy { it.length }
            var size = base.fontSize.value
            while (size > 18f && measurer.measure(widest, base.copy(fontSize = size.sp), maxLines = 1, softWrap = false).size.width > maxWidthPx) {
                size -= 2f
            }
            size.sp
        }
        Text(
            Money.formatDisplay(shown),
            style = base,
            fontSize = fontSize,
            color = color(shown),
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Plural string from resources (avoids depending on the experimental Compose helper). */
@Composable
fun quantityString(@androidx.annotation.PluralsRes id: Int, count: Int, vararg args: Any): String =
    androidx.compose.ui.platform.LocalContext.current.resources.getQuantityString(id, count, *args)
