package com.cashflow.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cashflow.app.R
import com.cashflow.app.ui.theme.cashflow

/** Reject (red cross) and accept (green check): the same red and green as the EXPENSE and INCOME buttons. */
@Composable
fun AnswerButtons(
    onReject: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
    acceptEnabled: Boolean = true,
    size: Dp = 72.dp,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally)) {
        RoundIconButton(Icons.Filled.Close, stringResource(R.string.action_reject), MaterialTheme.cashflow.expenseFill, true, onReject, size)
        RoundIconButton(Icons.Filled.Check, stringResource(R.string.action_accept), MaterialTheme.cashflow.incomeFill, acceptEnabled, onAccept, size)
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    fill: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
    size: Dp,
) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size).pressScale(source),
        shape = CircleShape,
        color = if (enabled) fill else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.cashflow.buttonLabel,
        interactionSource = source,
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/**
 * The first-use swipe hint: a red cross with a left arrow, and a right arrow with a green check. No words;
 * TalkBack users get Accept and Reject as actions instead, so the hint itself is hidden from them.
 */
@Composable
fun SwipeHint(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.cashflow
    Row(modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = colors.expenseFill, modifier = Modifier.size(28.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.incomeFill, modifier = Modifier.size(28.dp))
        }
    }
}
