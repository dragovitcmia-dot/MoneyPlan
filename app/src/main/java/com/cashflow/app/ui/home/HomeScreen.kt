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
import androidx.compose.foundation.lazy.LazyListState
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
import com.cashflow.app.ui.components.AnswerButtons
import com.cashflow.app.ui.components.AppCard
import com.cashflow.app.ui.components.KindButtons
import com.cashflow.app.ui.items.CardDate
import com.cashflow.app.ui.items.ItemDetails
import com.cashflow.app.ui.items.ItemEditSheet
import com.cashflow.app.ui.items.RejectConfirmDialog
import com.cashflow.app.ui.items.WaitingCard
import com.cashflow.app.ui.items.rememberItemActions
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
    listState: LazyListState,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val ui = state
    if (ui == null) {
        HomeSkeleton(contentPadding) // nothing numeric until the first data emission
    } else {
        HomeContent(ui, viewModel, odometer, snackbarHost, contentPadding, listState)
    }
}

@Composable
private fun HomeContent(
    ui: HomeUiState,
    viewModel: HomeViewModel,
    odometer: HeroOdometer,
    snackbarHost: SnackbarHostState,
    padding: PaddingValues,
    listState: LazyListState,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val showHint by viewModel.swipeHintVisible.collectAsStateWithLifecycle()
    var composerKind by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var changing by remember { mutableStateOf<Transaction?>(null) }

    val actions = rememberItemActions(viewModel, snackbarHost)
    fun labelOf(tx: Transaction) = actions.labelOf(tx)
    fun confirm(tx: Transaction, newAmount: Long? = null) = actions.confirm(tx, newAmount)
    fun reject(tx: Transaction) = actions.requestReject(tx) // asks first: rejecting is permanent

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
            KindButtons(
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
                        reject(tx)
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
            redDip = viewModel::redDip,
            resultingBalance = viewModel::resultingBalance,
            onSave = viewModel::save,
            onSaved = { message -> actions.announce(message) },
            onDismiss = { composerKind = null },
        )
    }

    editing?.let { tx ->
        ItemDetails(
            tx = tx,
            home = viewModel,
            actions = actions,
            today = ui.today,
            onDismiss = { editing = null },
            onEdit = { changing = tx },
        )
    }
    changing?.let { tx ->
        ItemEditSheet(tx = tx, actions = actions, onDismiss = { changing = null }, today = ui.today)
    }
    RejectConfirmDialog(actions)
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
