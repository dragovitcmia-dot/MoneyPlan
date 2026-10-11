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
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    textStyle: TextStyle? = null,
) {
    val source = remember { MutableInteractionSource() }
    val m = modifier.pressScale(source).heightIn(min = 56.dp)
    when (style) {
        AppButtonStyle.Primary -> Button(
            onClick, m, enabled = enabled, elevation = null, interactionSource = source,
            colors = colors ?: ButtonDefaults.buttonColors(),
        ) { Text(text, textAlign = TextAlign.Center, style = textStyle ?: LocalTextStyle.current) }
        AppButtonStyle.Tonal -> FilledTonalButton(onClick, m, enabled = enabled, elevation = null, interactionSource = source) {
            Text(text, textAlign = TextAlign.Center)
        }
        AppButtonStyle.Outlined -> OutlinedButton(
            onClick, m, enabled = enabled, interactionSource = source,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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

/**
 * State of the big Available number. It lives above the tabs (one instance for the whole app), so
 * switching tabs never restarts the count. [arm] makes the next run start from 0 (called every time
 * the app comes to the foreground); otherwise a new target counts from whatever is shown now.
 */
@Stable
class AvailableCounter {
    var from by mutableLongStateOf(0L)
        private set
    var to by mutableLongStateOf(0L)
        private set
    private var progress by mutableFloatStateOf(1f)
    private var armed = true

    /** The value to display right now (read only inside the number's own composable). */
    fun shown(): Long = CountUp.valueAt(from, to, progress.toDouble())

    /** The next [run] counts up from 0 to the current value. */
    fun arm() {
        armed = true
    }

    /**
     * Counts to [target] over [CountUp.durationMillis] with the strong ease-out of [CountUp.progress],
     * with a light haptic tick per evenly spaced value step and a stronger tick on arrival. If this
     * coroutine is cancelled (a new target arrived) the remaining ticks and the arrival tick are dropped.
     */
    suspend fun run(target: Long, haptics: Haptics) {
        val start = if (armed) 0L else shown()
        armed = false
        // Written together, without a suspension point in between, so no frame shows a mix of old and new.
        from = start
        to = target
        progress = if (start == target) 1f else 0f
        if (start == target) return
        val steps = RouletteTicks.stepCount(start, target)
        coroutineScope {
            val ticker = launch {
                var passedBefore = 0
                var ticks = 0
                snapshotFlow { progress }.collect { p ->
                    val passed = RouletteTicks.thresholdsPassed(start, target, CountUp.valueAt(start, target, p.toDouble()), steps)
                    if (passed > passedBefore) {
                        passedBefore = passed
                        if (ticks < RouletteTicks.MAX_TICKS - 1) { // one slot stays free for the arrival tick
                            ticks++
                            haptics.tick()
                        }
                    }
                }
            }
            animate(0f, 1f, animationSpec = tween(CountUp.durationMillis(start, target), easing = LinearEasing)) { value, _ ->
                progress = value
            }
            ticker.cancel()
        }
        haptics.arrive()
    }
}

/**
 * The big Available number. The counter's animated value is read only in this composable, so just
 * this number is redrawn on each frame, not the screen around it. The text shrinks so it always fits
 * on one line.
 */
@Composable
fun AnimatedAmountText(counter: AvailableCounter, color: (Long) -> Color, modifier: Modifier = Modifier) {
    val shown = counter.shown()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = MaterialTheme.typography.displayLarge
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxWidthPx = constraints.maxWidth
        val from = counter.from
        val to = counter.to
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
) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CompositionLocalProvider(LocalContentColor provides if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { label() }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            isError = isError,
            placeholder = placeholder,
            supportingText = supportingText,
            keyboardOptions = keyboardOptions,
        )
    }
}
