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

/** One line of text that shrinks (down to 60%) instead of clipping or wrapping when it does not fit its width. */
@Composable
fun FitText(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    var scale by remember(text, style) { mutableFloatStateOf(1f) }
    Text(
        text = text,
        color = color,
        style = style.copy(fontSize = style.fontSize * scale),
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center,
        onTextLayout = { if (it.didOverflowWidth && scale > 0.6f) scale *= 0.88f },
    )
}
