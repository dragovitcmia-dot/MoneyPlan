package com.cashflow.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import com.cashflow.app.ui.theme.Spacing
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
import com.cashflow.app.engine.HomeStatus
import com.cashflow.app.engine.HomeStatusLine
import com.cashflow.app.engine.Money
import com.cashflow.app.ui.components.color
import androidx.compose.ui.semantics.contentDescription
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import com.cashflow.app.ui.Haptics
import com.cashflow.app.ui.components.HeroNumber
import com.cashflow.app.ui.components.HeroOdometer
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.AnswerButtons
import com.cashflow.app.ui.components.AppCard
import com.cashflow.app.ui.components.SectionHeader
import com.cashflow.app.ui.theme.Motion
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import com.cashflow.app.ui.components.KindButtons
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

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    odometer: HeroOdometer,
    snackbarHost: SnackbarHostState,
    contentPadding: PaddingValues,
    listState: LazyListState,
    /** The shortage line opens the Calendar on that day. */
    onOpenDay: (java.time.LocalDate) -> Unit = {},
    onOpenDayList: (java.time.LocalDate) -> Unit = {},
    /** A place to show by itself on this screen (the add wizard on its Name step, the purchase result); null when there is none. */
    request: com.cashflow.app.engine.UiTarget? = null,
    onRequestHandled: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val ui = state
    if (ui == null) {
        HomeSkeleton(contentPadding) // nothing numeric until the first data emission
    } else {
        HomeContent(ui, viewModel, odometer, snackbarHost, contentPadding, listState, onOpenDay, onOpenDayList, request, onRequestHandled)
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
    onOpenDay: (java.time.LocalDate) -> Unit,
    onOpenDayList: (java.time.LocalDate) -> Unit,
    request: com.cashflow.app.engine.UiTarget?,
    onRequestHandled: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val showHint by viewModel.swipeHintVisible.collectAsStateWithLifecycle()
    var composerKind by rememberSaveable { mutableStateOf<String?>(null) }
    var planOpen by rememberSaveable { mutableStateOf(false) }
    // "Add as a normal expense" from Plan a purchase: (price text, name) for the add wizard.
    var prefill by remember { mutableStateOf<Pair<String, String>?>(null) }
    val sheets = com.cashflow.app.ui.items.rememberItemSheetState()
    var startAtName by remember { mutableStateOf(false) }
    // Every request builds its sheet from scratch (a Lab check can open the same sheet twice in a row with different contents).
    var requestSerial by remember { mutableStateOf(0) }
    var purchaseStart by remember { mutableStateOf(com.cashflow.app.engine.PurchaseState()) }
    LaunchedEffect(request) {
        when (request) {
            is com.cashflow.app.engine.UiTarget.Wizard -> {
                requestSerial++
                prefill = request.amountText to request.name
                startAtName = request.atName
                composerKind = request.kind.name
            }
            is com.cashflow.app.engine.UiTarget.Purchase -> {
                requestSerial++
                purchaseStart = com.cashflow.app.engine.PurchaseState(
                    priceText = request.priceText, name = request.name, step = com.cashflow.app.engine.PurchaseStep.RESULT, selectedParts = request.parts,
                )
                planOpen = true
            }
            com.cashflow.app.engine.UiTarget.Home -> {
                composerKind = null
                planOpen = false
                prefill = null
                startAtName = false
                purchaseStart = com.cashflow.app.engine.PurchaseState()
            }
            else -> Unit
        }
        if (request != null) onRequestHandled()
    }

    val actions = rememberItemActions(viewModel, snackbarHost)
    val seriesCtx by viewModel.series.collectAsStateWithLifecycle()
    fun labelOf(tx: Transaction) = actions.labelOf(tx)
    fun confirm(tx: Transaction, newAmount: Long? = null) = actions.confirm(tx, newAmount)
    fun reject(tx: Transaction) = actions.requestReject(tx) // asks first: rejecting is permanent

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.l,
            end = Spacing.l,
            top = padding.calculateTopPadding() + Spacing.l,
            bottom = padding.calculateBottomPadding() + Spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        item(key = "hero", contentType = "hero") { Hero(odometer = odometer) }
        // One quiet line: the next expense, or (when a negative day is coming) how short and before when; that one opens the Calendar.
        item(key = "status", contentType = "status") { StatusLine(ui.status, ui.today) { day -> haptics.tick(); onOpenDay(day) } }
        // What was spent today (accepted expenses, a "Now" expense included); it opens that day's list. Hidden while nothing was spent.
        if (ui.spentToday > 0) {
            item(key = "today", contentType = "today") { TodayLine(ui.spentToday) { haptics.tick(); onOpenDayList(ui.today) } }
        }
        item(key = "buttons", contentType = "buttons") {
            KindButtons(
                onExpense = { composerKind = TxKind.EXPENSE.name },
                onIncome = { composerKind = TxKind.INCOME.name },
            )
        }
        item(key = "plan", contentType = "plan") {
            AppButton(
                stringResource(R.string.plan_button),
                onClick = { planOpen = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                style = AppButtonStyle.Outlined,
            )
        }
        if (ui.toConfirm.isEmpty()) {
            item(key = "caughtUp", contentType = "empty") { NothingPending() }
        } else {
            item(key = "waitingHeader", contentType = "header") { SectionHeader(stringResource(R.string.waiting_header)) }
            items(ui.toConfirm, key = { it.id }, contentType = { "waiting" }) { tx ->
                WaitingCard(
                    tx = tx,
                    showHint = showHint,
                    haptics = haptics,
                    onConfirm = { fromSwipe ->
                        if (fromSwipe) viewModel.onSwipeCompleted()
                        // A series that asks for the amount each time opens the amount sheet instead of accepting.
                        if (actions.asksAmount(tx, seriesCtx)) sheets.open(tx) else confirm(tx)
                    },
                    onSkip = { fromSwipe ->
                        if (fromSwipe) viewModel.onSwipeCompleted()
                        reject(tx)
                    },
                    onOpen = { sheets.open(tx) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = Motion.standard(), placementSpec = Motion.standard(), fadeOutSpec = Motion.fast(),
                    ),
                )
            }
        }
    }

    composerKind?.let { name ->
      androidx.compose.runtime.key(requestSerial) {
        EntryComposer(
            kind = TxKind.valueOf(name),
            ui = ui,
            redDip = viewModel::redDip,
            resultingBalance = viewModel::resultingBalance,
            onSave = viewModel::save,
            onSaved = { message -> actions.announce(message) },
            onDismiss = { composerKind = null; prefill = null; startAtName = false },
            prefillAmount = prefill?.first.orEmpty(),
            prefillName = prefill?.second.orEmpty(),
            startAtName = startAtName,
        )
      }
    }

    if (planOpen) {
      androidx.compose.runtime.key(requestSerial) {
        PlanPurchaseSheet(
            ui = ui,
            loadBaseline = viewModel::purchaseBaseline,
            onPlan = viewModel::planPurchase,
            onPlanned = { message -> actions.announce(message) },
            onAddNormal = { state ->
                prefill = state.priceText to state.name.trim()
                planOpen = false
                composerKind = TxKind.EXPENSE.name
            },
            onDismiss = { planOpen = false; purchaseStart = com.cashflow.app.engine.PurchaseState() },
            initial = purchaseStart,
        )
      }
    }

    com.cashflow.app.ui.items.ItemSheets(sheets, viewModel, actions, ui.today)
    RejectConfirmDialog(actions)
}

@Composable
private fun Hero(odometer: HeroOdometer) {
    val negative = MaterialTheme.cashflow.negative
    val normal = MaterialTheme.colorScheme.onBackground // a positive balance is plain text, never green
    Column(
        Modifier.fillMaxWidth().padding(top = Spacing.m, bottom = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            stringResource(R.string.available_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {}, // the number's description already says "Available"
        )
        HeroNumber(odometer = odometer, normalColor = normal, negativeColor = negative)
    }
}

/** The status line under the balance: quiet, centered, one line of text; the shortage variant is red and tappable. */
@Composable
private fun StatusLine(status: HomeStatus, today: java.time.LocalDate, onOpenDay: (java.time.LocalDate) -> Unit) {
    val text = HomeStatusLine.text(status, today) ?: return
    val day = HomeStatusLine.tapDay(status)
    val color = if (day != null) MaterialTheme.cashflow.negative else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.fillMaxWidth().then(
            if (day != null) Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { onOpenDay(day) }.heightIn(min = 48.dp) else Modifier,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color, textAlign = TextAlign.Center)
    }
}

/** "Today  -75 TND": one quiet row, the amount in the money-out red; tapping it opens today's day list on the Calendar. */
@Composable
private fun TodayLine(spent: Long, onClick: () -> Unit) {
    val amount = Money.formatSigned(TxKind.EXPENSE, spent)
    val label = stringResource(R.string.home_today_label)
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label $amount" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            amount,
            style = MaterialTheme.typography.bodyLarge,
            color = com.cashflow.app.engine.SemanticColor.ofKind(TxKind.EXPENSE).color(),
            modifier = Modifier.padding(start = Spacing.m),
        )
    }
}

@Composable
private fun NothingPending() {
    Text(
        stringResource(R.string.caught_up),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
        textAlign = TextAlign.Center,
    )
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
        Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.l, vertical = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Block(Modifier.width(80.dp), 18)
        Block(Modifier.width(220.dp), 56)
        Block(Modifier.fillMaxWidth(), 96)
        Block(Modifier.fillMaxWidth(), 80)
    }
}
