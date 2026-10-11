package com.cashflow.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.NextExpense
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import com.cashflow.app.ui.Haptics
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.AppCard
import com.cashflow.app.ui.components.rememberCountedValue
import com.cashflow.app.ui.rememberHaptics
import com.cashflow.app.ui.theme.cashflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/** Position of the "To confirm" header in the list below; the chip scrolls here. */
private const val TO_CONFIRM_INDEX = 3

private val CardDate = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

@Composable
fun HomeScreen(viewModel: HomeViewModel, snackbarHost: SnackbarHostState, contentPadding: PaddingValues) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val ui = state
    if (ui == null) {
        HomeSkeleton(contentPadding) // nothing numeric until the first data emission
    } else {
        HomeContent(ui, viewModel, snackbarHost, contentPadding)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    ui: HomeUiState,
    viewModel: HomeViewModel,
    snackbarHost: SnackbarHostState,
    padding: PaddingValues,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var composerKind by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Transaction?>(null) }

    /** Shows [message] for 5 seconds, with an Undo action when [onUndo] is given. */
    fun announce(message: String, onUndo: (() -> Unit)? = null) {
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            val timer = launch {
                delay(5_000)
                snackbarHost.currentSnackbarData?.dismiss()
            }
            val result = snackbarHost.showSnackbar(
                message = message,
                actionLabel = if (onUndo != null) "Undo" else null,
                duration = SnackbarDuration.Indefinite,
            )
            timer.cancel()
            if (result == SnackbarResult.ActionPerformed) onUndo?.invoke()
        }
    }

    fun confirm(tx: Transaction, newAmount: Long? = null) {
        haptics.confirm()
        viewModel.confirm(tx.id, newAmount)
        announce("Confirmed ${tx.label()}") { viewModel.undo(tx.id, if (newAmount != null) tx.amount else null) }
    }

    fun skip(tx: Transaction) {
        haptics.reject()
        viewModel.skip(tx.id)
        announce("Skipped ${tx.label()}") { viewModel.undo(tx.id) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = padding.calculateTopPadding() + 16.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "hero") {
            Hero(
                available = ui.available,
                toConfirmCount = ui.toConfirm.size,
                onChipClick = { scope.launch { listState.animateScrollToItem(TO_CONFIRM_INDEX) } },
            )
        }
        item(key = "next") { NextExpenseCard(ui.nextExpense) }
        item(key = "buttons") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton("Add expense", onClick = { composerKind = TxKind.EXPENSE.name }, modifier = Modifier.weight(1f))
                AppButton(
                    "Add income",
                    onClick = { composerKind = TxKind.INCOME.name },
                    modifier = Modifier.weight(1f),
                    style = AppButtonStyle.Tonal,
                )
            }
        }
        item(key = "toConfirmHeader") {
            Text("To confirm", style = MaterialTheme.typography.titleMedium)
        }
        if (ui.toConfirm.isEmpty()) {
            item(key = "caughtUp") { CaughtUp() }
        } else {
            items(ui.toConfirm, key = { it.id }) { tx ->
                ConfirmCard(
                    tx = tx,
                    haptics = haptics,
                    onConfirm = { confirm(tx) },
                    onSkip = { skip(tx) },
                    onOpen = { editing = tx },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    composerKind?.let { name ->
        EntryComposer(
            kind = TxKind.valueOf(name),
            ui = ui,
            whatIf = viewModel::whatIf,
            onSave = viewModel::save,
            onSaved = { message -> announce(message) },
            onDismiss = { composerKind = null },
        )
    }

    editing?.let { tx ->
        EditAmountSheet(
            tx = tx,
            onDismiss = { editing = null },
            onConfirm = { amount ->
                editing = null
                confirm(tx, newAmount = amount.takeIf { it != tx.amount })
            },
        )
    }
}

private fun Transaction.label() = description.ifBlank { if (kind == TxKind.INCOME) "income" else "expense" }

@Composable
private fun Hero(available: Long, toConfirmCount: Int, onChipClick: () -> Unit) {
    val shown = rememberCountedValue(available)
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Available", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            Money.formatDisplay(shown),
            style = MaterialTheme.typography.displayLarge,
            color = if (shown < 0) MaterialTheme.cashflow.negative else MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        AnimatedVisibility(visible = toConfirmCount > 0, enter = fadeIn(), exit = fadeOut()) {
            AssistChip(
                onClick = onChipClick,
                label = { Text("$toConfirmCount to confirm") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = null,
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
    }
}

@Composable
private fun NextExpenseCard(next: NextExpense?) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Next expense", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (next == null) {
                Text("No upcoming expenses", style = MaterialTheme.typography.titleMedium)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(inDays(next.daysAway), style = MaterialTheme.typography.titleLarge)
                        if (next.description.isNotBlank()) {
                            Text(
                                next.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        Money.formatSigned(TxKind.EXPENSE, next.amount),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.cashflow.negative,
                    )
                }
            }
        }
    }
}

private fun inDays(days: Int) = when (days) {
    0 -> "Today"
    1 -> "Tomorrow"
    else -> "in $days days"
}

@Composable
private fun CaughtUp() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text("All caught up", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Swipe right = confirm, swipe left = skip; tap opens the edit-amount sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmCard(
    tx: Transaction,
    haptics: Haptics,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The callbacks always return false: the row leaves the list when the data changes, and
    // snapping back keeps the swipe state clean if the item later returns (Undo).
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onConfirm()
                SwipeToDismissBoxValue.EndToStart -> onSkip()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
        positionalThreshold = { total -> total * 0.35f },
    )
    LaunchedEffect(state.targetValue) {
        if (state.targetValue != SwipeToDismissBoxValue.Settled) haptics.tick() // threshold reached
    }

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val direction = state.dismissDirection
            val accent = direction == SwipeToDismissBoxValue.StartToEnd
            val color = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.cashflow.hairline
                SwipeToDismissBoxValue.Settled -> Color.Transparent
            }
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(color).padding(horizontal = 24.dp),
                contentAlignment = if (accent) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Text("Confirm", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
                    SwipeToDismissBoxValue.EndToStart -> Text("Skip", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    SwipeToDismissBoxValue.Settled -> Unit
                }
            }
        },
    ) {
        val income = tx.kind == TxKind.INCOME
        AppCard(Modifier.fillMaxWidth(), onClick = onOpen) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            tx.description.ifBlank { if (income) "Income" else "Expense" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("Confirm", onClick = onConfirm, modifier = Modifier.weight(1f))
                    AppButton("Skip", onClick = onSkip, modifier = Modifier.weight(1f), style = AppButtonStyle.Tonal)
                }
            }
        }
    }
}

/** Confirm with a different amount: the field is prefilled; a changed amount updates only this row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditAmountSheet(tx: Transaction, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(Money.format(tx.amount, withCurrency = false)) }
    val amount = Money.parse(text)?.takeIf { it > 0 }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Confirm ${tx.description.ifBlank { if (tx.kind == TxKind.INCOME) "income" else "expense" }}", style = MaterialTheme.typography.titleLarge)
            Text(tx.date.format(CardDate), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Amount (TND)") },
                singleLine = true,
                isError = amount == null,
                supportingText = { if (amount == null) Text("Enter an amount greater than zero") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton("Cancel", onClick = onDismiss, modifier = Modifier.weight(1f), style = AppButtonStyle.Outlined)
                AppButton("Confirm", onClick = { amount?.let(onConfirm) }, modifier = Modifier.weight(1f), enabled = amount != null)
            }
        }
    }
}

@Composable
private fun HomeSkeleton(padding: PaddingValues) {
    val alpha by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    @Composable
    fun Block(width: Modifier, height: Int) {
        Box(
            width.height(height.dp).alpha(alpha).clip(MaterialTheme.shapes.medium).background(MaterialTheme.cashflow.hairline),
        )
    }
    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Block(Modifier.width(80.dp), 14)
        Block(Modifier.width(220.dp), 56)
        Block(Modifier.fillMaxWidth(), 96)
        Block(Modifier.fillMaxWidth(), 52)
    }
}
