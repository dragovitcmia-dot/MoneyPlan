package com.cashflow.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cashflow.app.R
import com.cashflow.app.ui.theme.cashflow

private val KeyShape = RoundedCornerShape(14.dp)

/**
 * Numeric keypad in the style of a phone keyboard: every key is its own rounded rectangle with a
 * fill and a visible outline, small gaps, tall keys. The decimal point and backspace keys use a
 * slightly different fill. Long-press on backspace clears the whole amount.
 */
@Composable
fun Keypad(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.cashflow
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in listOf("123", "456", "789")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (digit in row) {
                    KeyButton(colors.keyDigit, { onKey(digit) }, Modifier.weight(1f)) { KeyLabel(digit.toString()) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton(colors.keyAction, { onKey('.') }, Modifier.weight(1f)) { KeyLabel(".") }
            KeyButton(colors.keyDigit, { onKey('0') }, Modifier.weight(1f)) { KeyLabel("0") }
            KeyButton(
                fill = colors.keyAction,
                onClick = onBackspace,
                modifier = Modifier.weight(1f),
                onLongClick = onClear,
                description = stringResource(R.string.backspace_description),
            ) {
                Icon(
                    painterResource(R.drawable.ic_backspace),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun KeyLabel(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyButton(
    fill: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // Pressed: a step toward the text color (lighter on dark keys, darker on light keys), plus ripple.
    val background = if (pressed) lerp(fill, MaterialTheme.colorScheme.onSurface, 0.14f) else fill
    Box(
        modifier
            .heightIn(min = 64.dp)
            .clip(KeyShape)
            .background(background)
            .border(1.dp, MaterialTheme.cashflow.keyContour, KeyShape)
            .combinedClickable(
                interactionSource = source,
                indication = ripple(),
                role = Role.Button,
                onClickLabel = description,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}
