package com.cashflow.app.ui.items

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cashflow.app.R
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import com.cashflow.app.ui.Haptics
import com.cashflow.app.ui.components.AppCard
import com.cashflow.app.ui.components.SwipeHint
import com.cashflow.app.ui.theme.cashflow
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Swipe right = confirm, swipe left = skip (no buttons). Small hints on the card edges show how,
 * until the first successful swipe. Tapping opens the "different amount" sheet. TalkBack gets
 * Confirm and Skip as custom actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaitingCard(
    tx: Transaction,
    showHint: Boolean,
    haptics: Haptics,
    onConfirm: (fromSwipe: Boolean) -> Unit,
    onSkip: (fromSwipe: Boolean) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Extra line under the title row (the Upcoming list adds "repeats" and "short by" here). */
    footer: (@Composable () -> Unit)? = null,
) {
    // The callbacks always return false: the row leaves the list when the data changes, and
    // snapping back keeps the swipe state clean if the item later returns (Undo).
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onConfirm(true)
                SwipeToDismissBoxValue.EndToStart -> onSkip(true)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
        positionalThreshold = { total -> total * 0.35f },
    )
    LaunchedEffect(state.targetValue) {
        if (state.targetValue != SwipeToDismissBoxValue.Settled) haptics.tick() // threshold reached
    }
    val acceptLabel = stringResource(R.string.action_accept)
    val rejectLabel = stringResource(R.string.action_reject)

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val direction = state.dismissDirection
            val color = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.cashflow.incomeFill
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.cashflow.expenseFill
                SwipeToDismissBoxValue.Settled -> Color.Transparent
            }
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(color).padding(horizontal = 28.dp),
                contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd ->
                        Icon(Icons.Filled.Check, contentDescription = acceptLabel, tint = MaterialTheme.cashflow.buttonLabel, modifier = Modifier.size(36.dp))
                    SwipeToDismissBoxValue.EndToStart ->
                        Icon(Icons.Filled.Close, contentDescription = rejectLabel, tint = MaterialTheme.cashflow.buttonLabel, modifier = Modifier.size(36.dp))
                    SwipeToDismissBoxValue.Settled -> Unit
                }
            }
        },
    ) {
        val income = tx.kind == TxKind.INCOME
        AppCard(
            Modifier.fillMaxWidth().semantics {
                // TalkBack users cannot swipe the card: they get the same two actions from the actions menu.
                customActions = listOf(
                    CustomAccessibilityAction(acceptLabel) { onConfirm(false); true },
                    CustomAccessibilityAction(rejectLabel) { onSkip(false); true },
                )
            },
            onClick = onOpen,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            tx.description.ifBlank {
                                stringResource(if (income) R.string.item_income else R.string.item_expense)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            tx.date.format(CardDate),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        Money.formatSigned(tx.kind, tx.amount),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (income) MaterialTheme.colorScheme.primary else MaterialTheme.cashflow.negative,
                    )
                }
                footer?.invoke()
                AnimatedVisibility(visible = showHint, enter = fadeIn(), exit = fadeOut()) { SwipeHint() }
            }
        }
    }
}


val CardDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

