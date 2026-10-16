package com.cashflow.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cashflow.app.R
import com.cashflow.app.ui.theme.cashflow

private val KeyShape = RoundedCornerShape(com.cashflow.app.ui.theme.Tokens.CARD_CORNER_DP.dp)

private val DigitStyle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum")

/**
 * Numeric keypad in the style of a phone keyboard. Keys have NO borders or outlines: a key's shape
 * comes only from its fill against the keypad background plus the gaps between keys (6dp across,
 * 10dp down). Backspace has a darker/different fill; a pressed key gets a lighter/darker fill.
 * Long-press on backspace clears the whole amount.
 */
@Composable
fun Keypad(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.cashflow
    Column(
        modifier.fillMaxWidth().background(colors.keypadBackground).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (row in listOf("123", "456", "789")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (digit in row) {
                    KeyButton(colors.keyDigit, { onKey(digit) }, Modifier.weight(1f)) { KeyLabel(digit.toString()) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyButton(colors.keyDigit, { onKey('.') }, Modifier.weight(1f)) { KeyLabel(".") }
            KeyButton(colors.keyDigit, { onKey('0') }, Modifier.weight(1f)) { KeyLabel("0") }
            KeyButton(
                fill = colors.keyAction,
                onClick = onBackspace,
                modifier = Modifier.weight(1f),
                onLongClick = onClear,
                description = stringResource(R.string.backspace_description),
            ) {
                Icon(painterResource(R.drawable.ic_backspace), contentDescription = null, tint = colors.keyText)
            }
        }
    }
}

@Composable
private fun KeyLabel(text: String) {
    Text(text, style = DigitStyle, color = MaterialTheme.cashflow.keyText)
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
    Box(
        modifier
            .heightIn(min = 68.dp)
            .clip(KeyShape)
            .background(if (pressed) MaterialTheme.cashflow.keyPressed else fill)
            .combinedClickable(
                interactionSource = source,
                indication = null, // the fill change is the press feedback
                role = Role.Button,
                onClickLabel = description,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}
