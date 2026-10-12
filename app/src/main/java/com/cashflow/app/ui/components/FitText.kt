package com.cashflow.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * One line of text that shrinks instead of clipping or wrapping when it does not fit its width. It never gets an
 * ellipsis. By default it shrinks to 60% of [style]'s size; with [minSize] it shrinks until that size (e.g. 9sp)
 * is reached, so a number stays readable and visible instead of being replaced by something else.
 */
@Composable
fun FitText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    minSize: TextUnit = TextUnit.Unspecified,
) {
    var scale by remember(text, style) { mutableFloatStateOf(1f) }
    val floor = if (minSize == TextUnit.Unspecified) 0.6f else (minSize.value / style.fontSize.value).coerceIn(0.1f, 1f)
    Text(
        text = text,
        color = color,
        style = style.copy(fontSize = style.fontSize * scale),
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center,
        onTextLayout = { if (it.didOverflowWidth && scale > floor) scale = maxOf(floor, scale * 0.88f) },
    )
}
