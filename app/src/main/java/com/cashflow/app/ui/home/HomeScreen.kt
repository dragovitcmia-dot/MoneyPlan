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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.cashflow.app.ui.components.LabeledTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.R
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.NextExpense
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import com.cashflow.app.ui.Haptics
import com.cashflow.app.ui.components.HeroNumber
import com.cashflow.app.ui.components.HeroOdometer
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.AppCard
import com.cashflow.app.ui.components.pressScale
import com.cashflow.app.ui.components.quantityString
import com.cashflow.app.ui.rememberHaptics
import com.cashflow.app.ui.theme.cashflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Position of the "Waiting for you" header in the list below; the chip scrolls here. */
private const val WAITING_HEADER_INDEX = 3

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    odometer: HeroOdometer,
    snackbarHost: SnackbarHostState,
    contentPadding: PaddingValues,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val ui = state
    if (ui == null) {
        HomeSkeleton(contentPadding) // nothing numeric until the first data emission
    } else {
        HomeContent(ui, viewModel, odometer, snackbarHost, contentPadding)
    }
}

@Composable
private fun HomeContent(
    ui: HomeUiState,
    viewModel: HomeViewModel,
    odometer: HeroOdometer,
    snackbarHost: SnackbarHostState,
    padding: PaddingValues,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val resources = LocalContext.current.resources
    val showHint by viewModel.swipeHintVisible.collectAsStateWithLifecycle()
    var composerKind by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Transaction?>(null) }

    val undoLabel = stringResource(R.string.action_undo)

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
                actionLabel = if (onUndo != null) undoLabel else null,
                duration = SnackbarDuration.Indefinite,
            )
            timer.cancel()
            if (result == SnackbarResult.ActionPerformed) onUndo?.invoke()
        }
    }

    fun labelOf(tx: Transaction) = tx.description.ifBlank {
        resources.getString(if (tx.kind == TxKind.INCOME) R.string.item_income else R.string.item_expense)
    }

    fun confirm(tx: Transaction, newAmount: Long? = null) {
        haptics.confirm()
        viewModel.confirm(tx.id, newAmount)
        announce(resources.getString(R.string.confirmed_item, labelOf(tx))) {
            viewModel.undo(tx.id, if (newAmount != null) tx.amount else null)
        }
    }

    fun skip(tx: Transaction) {
        haptics.reject()
        viewModel.skip(tx.id)
        announce(resources.getString(R.string.skipped_item, labelOf(tx))) { viewModel.undo(tx.id) }
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
        item(key = "hero", contentType = "hero") {
            Hero(
                odometer = odometer,
                waitingCount = ui.toConfirm.size,
                onChipClick = { scope.launch { listState.animateScrollToItem(WAITING_HEADER_INDEX) } },
            )
        }
        item(key = "next", contentType = "next") { NextExpenseCard(ui.nextExpense) }
        item(key = "buttons", contentType = "buttons") {
            ActionButtons(
                onExpense = { composerKind = TxKind.EXPENSE.name },
                onIncome = { composerKind = TxKind.INCOME.name },
            )
        }
        item(key = "waitingHeader", contentType = "header") {
            Text(stringResource(R.string.waiting_header), style = MaterialTheme.typography.titleMedium)
        }
        if (ui.toConfirm.isEmpty()) {
            item(key = "caughtUp", contentType = "empty") { CaughtUp() }
        } else {
            items(ui.toConfirm, key = { it.id }, contentType = { "waiting" }) { tx ->
                WaitingCard(
                    tx = tx,
                    showHint = showHint,
                    haptics = haptics,
                    onConfirm = { fromSwipe ->
                        if (fromSwipe) viewModel.onSwipeCompleted()
                        confirm(tx)
                    },
                    onSkip = { fromSwipe ->
                        if (fromSwipe) viewModel.onSwipeCompleted()
                        skip(tx)
                    },
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
            title = labelOf(tx),
            onDismiss = { editing = null },
            onConfirm = { amount ->
                editing = null
                // Unchanged (also after rounding to 2 decimals) means: keep the stored amount exactly.
                val changed = amount != tx.amount && amount != Money.roundToCents(tx.amount)
                confirm(tx, newAmount = amount.takeIf { changed })
            },
        )
    }
}

@Composable
private fun Hero(odometer: HeroOdometer, waitingCount: Int, onChipClick: () -> Unit) {
    val negative = MaterialTheme.cashflow.negative
    val normal = MaterialTheme.colorScheme.onBackground
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.available_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {}, // the number's description already says "Available"
        )
        HeroNumber(odometer = odometer, normalColor = normal, negativeColor = negative)
        AnimatedVisibility(visible = waitingCount > 0, enter = fadeIn(), exit = fadeOut()) {
            AssistChip(
                onClick = onChipClick,
                label = { Text(quantityString(R.plurals.waiting_chip, waitingCount, waitingCount)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = null,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun NextExpenseCard(next: NextExpense?) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.next_expense_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (next == null) {
                Text(stringResource(R.string.no_upcoming_expenses), style = MaterialTheme.typography.titleMedium)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(daysAwayText(next.daysAway), style = MaterialTheme.typography.titleLarge)
                        if (next.description.isNotBlank()) {
                            Text(
                                next.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
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

@Composable
private fun daysAwayText(days: Int): String = when (days) {
    0 -> stringResource(R.string.today)
    1 -> stringResource(R.string.tomorrow)
    else -> quantityString(R.plurals.in_days, days, days)
}

/** Two big side-by-side buttons (stacked when the system font is very large so labels never clip). */
@Composable
private fun ActionButtons(onExpense: () -> Unit, onIncome: () -> Unit) {
    val colors = MaterialTheme.cashflow
    val stacked = LocalDensity.current.fontScale > 1.3f
    val expense = @Composable { m: Modifier ->
        BigActionButton(stringResource(R.string.button_expense), colors.expenseFill, colors.buttonLabel, onExpense, m)
    }
    val income = @Composable { m: Modifier ->
        BigActionButton(stringResource(R.string.button_income), colors.incomeFill, colors.buttonLabel, onIncome, m)
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            expense(Modifier.fillMaxWidth())
            income(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            expense(Modifier.weight(1f))
            income(Modifier.weight(1f))
        }
    }
}

@Composable
private fun BigActionButton(label: String, fill: Color, labelColor: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier.pressScale(source),
        shape = MaterialTheme.shapes.large,
        color = fill,
        contentColor = labelColor,
        interactionSource = source,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                label.uppercase(),
                color = labelColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
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
        Text(stringResource(R.string.caught_up), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Swipe right = confirm, swipe left = skip (no buttons). Small hints on the card edges show how,
 * until the first successful swipe. Tapping opens the "different amount" sheet. TalkBack gets
 * Confirm and Skip as custom actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaitingCard(
    tx: Transaction,
    showHint: Boolean,
    haptics: Haptics,
    onConfirm: (fromSwipe: Boolean) -> Unit,
    onSkip: (fromSwipe: Boolean) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
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
    val confirmLabel = stringResource(R.string.action_confirm)
    val skipLabel = stringResource(R.string.action_skip)

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val direction = state.dismissDirection
            val color = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.cashflow.hairline
                SwipeToDismissBoxValue.Settled -> Color.Transparent
            }
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(color).padding(horizontal = 24.dp),
                contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd ->
                        Text(confirmLabel, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
                    SwipeToDismissBoxValue.EndToStart ->
                        Text(skipLabel, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
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
                    CustomAccessibilityAction(confirmLabel) { onConfirm(false); true },
                    CustomAccessibilityAction(skipLabel) { onSkip(false); true },
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
                AnimatedVisibility(visible = showHint, enter = fadeIn(), exit = fadeOut()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.hint_skip), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.hint_confirm), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private val CardDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** Confirm with a different amount: the field is prefilled; a changed amount updates only this row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditAmountSheet(tx: Transaction, title: String, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(Money.formatInput(tx.amount)) }
    val amount = Money.parse(text, maxDecimals = 2)?.takeIf { it > 0 }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.edit_confirm_title, title), style = MaterialTheme.typography.titleLarge)
            Text(
                tx.date.format(CardDate) + " · " + stringResource(R.string.edit_confirm_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LabeledTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.amount_label)) },
                singleLine = true,
                isError = amount == null,
                supportingText = { if (amount == null) Text(stringResource(R.string.amount_error_decimals)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton(stringResource(R.string.action_cancel), onClick = onDismiss, modifier = Modifier.weight(1f), style = AppButtonStyle.Outlined)
                AppButton(stringResource(R.string.action_confirm), onClick = { amount?.let(onConfirm) }, modifier = Modifier.weight(1f), enabled = amount != null)
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
    fun Block(widthModifier: Modifier, height: Int) {
        Box(
            widthModifier.height(height.dp).alpha(alpha).clip(MaterialTheme.shapes.medium).background(MaterialTheme.cashflow.hairline),
        )
    }
    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Block(Modifier.width(80.dp), 18)
        Block(Modifier.width(220.dp), 56)
        Block(Modifier.fillMaxWidth(), 96)
        Block(Modifier.fillMaxWidth(), 80)
    }
}
