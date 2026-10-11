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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.cashflow.app.ui.theme.cashflow

/** Buttons and tappable cards shrink to 0.96 while pressed. */
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
) {
    val source = remember { MutableInteractionSource() }
    val m = modifier.pressScale(source).heightIn(min = 48.dp)
    when (style) {
        AppButtonStyle.Primary -> Button(onClick, m, enabled = enabled, elevation = null, interactionSource = source) { Text(text) }
        AppButtonStyle.Tonal -> FilledTonalButton(onClick, m, enabled = enabled, elevation = null, interactionSource = source) { Text(text) }
        AppButtonStyle.Outlined -> OutlinedButton(
            onClick, m, enabled = enabled, interactionSource = source,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(),
        ) { Text(text) }
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

private val EaseOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/**
 * Smoothly counts from the previously shown value to [target] (~600 ms, ease-out). The first value
 * is shown as-is, so nothing counts up from zero when the screen first appears.
 */
@Composable
fun rememberCountedValue(target: Long, durationMillis: Int = 600): Long {
    var from by remember { mutableLongStateOf(target) }
    var to by remember { mutableLongStateOf(target) }
    val progress = remember { Animatable(1f) }
    fun shown(): Long = if (progress.value >= 1f) to else from + ((to - from) * progress.value.toDouble()).toLong()
    LaunchedEffect(target) {
        if (target != to) {
            from = shown()
            to = target
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis, easing = EaseOut))
        }
    }
    return shown()
}

/** A padded card that groups one settings section. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AppCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
