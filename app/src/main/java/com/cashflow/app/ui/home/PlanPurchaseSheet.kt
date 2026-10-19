package com.cashflow.app.ui.home

import androidx.activity.compose.BackHandler
import com.cashflow.app.ui.theme.Spacing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cashflow.app.R
import com.cashflow.app.engine.CloseDecision
import com.cashflow.app.engine.EntryFormState
import com.cashflow.app.engine.FormProblem
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.PlanBaseline
import com.cashflow.app.engine.PurchaseOption
import com.cashflow.app.engine.PurchasePlanner
import com.cashflow.app.engine.PurchaseProblem
import com.cashflow.app.engine.PurchaseResult
import com.cashflow.app.engine.PurchaseState
import com.cashflow.app.engine.PurchaseStep
import com.cashflow.app.engine.PurchaseStrip
import com.cashflow.app.engine.PurchaseWizard
import com.cashflow.app.engine.NewEntry
import com.cashflow.app.engine.StripCell
import com.cashflow.app.engine.TxKind
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.selectedChipColors
import com.cashflow.app.ui.notifications.LocalNotificationPermission
import com.cashflow.app.ui.rememberHaptics
import com.cashflow.app.ui.theme.cashflow
import kotlinx.coroutines.launch

/**
 * "Plan a purchase": price on the numpad, a name, then when it can be paid without the balance ever going below
 * what it would be anyway, as "Pay all" or in 2 to 6 parts, with a calendar strip around the purchase. Same sheet,
 * discard guard and insets as the Add wizard. "Plan it" creates ordinary one-time planned expenses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanPurchaseSheet(
    ui: HomeUiState,
    loadBaseline: suspend () -> PlanBaseline?,
    onPlan: suspend (List<NewEntry.Planned>) -> Unit,
    onPlanned: (String) -> Unit,
    onAddNormal: (PurchaseState) -> Unit,
    onDismiss: () -> Unit,
    /** A Lab check opens the sheet straight on the result of a price. */
    initial: PurchaseState = PurchaseState(),
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val colors = MaterialTheme.cashflow
    val permission = LocalNotificationPermission.current
    var state by remember { mutableStateOf(initial) }
    var asking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val baseline by produceState<PlanBaseline?>(null) { value = loadBaseline() }

    val result: PurchaseResult? by remember {
        derivedStateOf { baseline?.let { PurchasePlanner.plan(it, PurchaseWizard.price(state)) } }
    }
    val option: PurchaseOption? = result?.option(state.selectedParts)

    fun requestClose() {
        if (PurchaseWizard.onCloseRequested(state) == CloseDecision.CLOSE) onDismiss() else asking = true
    }

    fun goBack() {
        val back = PurchaseWizard.back(state)
        if (back == null) requestClose() else state = back
        haptics.tick()
    }

    fun goNext() {
        val next = PurchaseWizard.next(state)
        if (next.step == state.step) haptics.reject() else haptics.tick()
        state = next
    }

    val savedOne = stringResource(R.string.plan_saved_one, state.name.trim())
    val savedParts = stringResource(R.string.plan_saved_parts, state.name.trim(), option?.parts ?: 1)
    fun planIt() {
        val chosen = option ?: return
        scope.launch {
            saving = true
            onPlan(PurchaseWizard.entriesFor(state.name, chosen))
            permission?.onPaymentAdded()
            haptics.confirm()
            onPlanned(if (chosen.parts == 1) savedOne else savedParts)
            onDismiss()
        }
    }

    val latest by rememberUpdatedState(state)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            when {
                target != SheetValue.Hidden -> true
                !PurchaseWizard.hasProgress(latest) -> true
                else -> {
                    asking = true
                    false
                }
            }
        },
    )
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp

    ModalBottomSheet(
        onDismissRequest = ::requestClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        BackHandler(enabled = !asking) { requestClose() }
        Column(Modifier.fillMaxWidth().heightIn(max = maxSheetHeight)) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.s).clip(MaterialTheme.shapes.large)
                    .background(colors.expenseFill).padding(vertical = Spacing.m),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.plan_title).uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.buttonLabel,
                )
            }
            val (position, total) = PurchaseWizard.progress(state)
            Column(Modifier.padding(horizontal = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                LinearProgressIndicator(
                    progress = { position.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
                Text(
                    stringResource(R.string.wizard_step_of, position, total),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The numpad and the name field are the Add wizard's own screens, fed through a form that carries the two values.
            val form = remember(state.priceText, state.name) {
                EntryFormState.initial(TxKind.EXPENSE, ui.today).copy(amountText = state.priceText, description = state.name)
            }
            val problems = if (state.attempted) {
                PurchaseWizard.problems(state).map { if (it == PurchaseProblem.PRICE_MISSING) FormProblem.AMOUNT_MISSING else FormProblem.NAME_MISSING }
            } else {
                emptyList()
            }
            val edit: ((EntryFormState) -> EntryFormState) -> Unit = { change ->
                val changed = change(form)
                if (changed.amountText != form.amountText) state = PurchaseWizard.editPrice(state, changed.amountText)
                if (changed.description != form.description) state = PurchaseWizard.editName(state, changed.description)
            }
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl, vertical = Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                when (state.step) {
                    PurchaseStep.PRICE -> AmountStep(form, problems, change = edit)
                    PurchaseStep.NAME -> NameStep(form, true, ui.expenseNames, problems, onNext = ::goNext, change = edit)
                    PurchaseStep.RESULT -> ResultStep(baseline, result, option, onSelect = { state = PurchaseWizard.select(state, it); haptics.tick() })
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                AppButton(
                    stringResource(if (PurchaseWizard.back(state) == null) R.string.action_cancel else R.string.wizard_back),
                    onClick = ::goBack,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                    style = AppButtonStyle.Outlined,
                )
                if (state.step == PurchaseStep.RESULT && result?.affordable == false) {
                    AppButton(
                        stringResource(R.string.plan_add_normal),
                        onClick = { onAddNormal(state) },
                        modifier = Modifier.weight(1.4f).heightIn(min = 64.dp),
                        style = AppButtonStyle.Outlined,
                    )
                } else {
                    val last = state.step == PurchaseStep.RESULT
                    AppButton(
                        text = stringResource(if (saving) R.string.saving else if (last) R.string.plan_it else R.string.wizard_next),
                        onClick = { if (last) planIt() else goNext() },
                        enabled = !saving && (!last || option != null),
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.expenseFill, contentColor = colors.buttonLabel),
                        textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.ime.union(WindowInsets.navigationBars)))
        }
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.wizard_discard_title)) },
            text = { Text(stringResource(R.string.wizard_discard_body)) },
            confirmButton = {
                TextButton(onClick = { asking = false; onDismiss() }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.wizard_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.wizard_keep_going))
                }
            },
        )
    }
}

@Composable
private fun ResultStep(baseline: PlanBaseline?, result: PurchaseResult?, option: PurchaseOption?, onSelect: (Int) -> Unit) {
    if (baseline == null || result == null) {
        Text(stringResource(R.string.plan_loading), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (!result.affordable) {
        Text(stringResource(R.string.plan_not_affordable), style = MaterialTheme.typography.headlineSmall)
        result.extraNeeded?.let { extra ->
            Text(
                stringResource(R.string.plan_extra_needed, Money.formatDisplay(extra)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // The choices: Pay all, 2, 3 ... (only the ones that exist). The default is the earliest start.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        for (o in result.options) {
            val desc = if (o.parts == 1) stringResource(R.string.plan_chip_desc_all) else stringResource(R.string.plan_chip_desc_parts, o.parts)
            FilterChip(
                selected = o.parts == option?.parts,
                onClick = { onSelect(o.parts) },
                label = { Text(PurchaseStrip.chipLabel(o), style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc },
                colors = selectedChipColors(),
            )
        }
    }
    if (option != null) {
        Text(PurchaseStrip.label(option, baseline.today), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        CalendarStrip(PurchaseStrip.cells(baseline, option), option)
    }
}

private val StripShape = RoundedCornerShape(3.dp)

/** The days around the purchase with the plan applied; scrolls itself to the first purchase day when the choice changes. */
@Composable
private fun CalendarStrip(cells: List<StripCell>, option: PurchaseOption) {
    val listState = rememberLazyListState()
    val first = remember(cells) { PurchaseStrip.firstPurchaseIndex(cells) ?: 0 }
    LaunchedEffect(option.parts, first) { listState.animateScrollToItem((first - 1).coerceAtLeast(0)) }
    val desc = stringResource(R.string.plan_strip_desc)
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = desc },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items(cells, key = { it.date.toEpochDay() }) { cell -> com.cashflow.app.ui.theme.CappedFontScale { StripCellView(cell) } }
    }
}

@Composable
private fun StripCellView(cell: StripCell) {
    val colors = MaterialTheme.cashflow
    val scheme = MaterialTheme.colorScheme
    val purchase = cell.purchase != null
    val shape = StripShape
    Column(
        Modifier.width(68.dp).height(96.dp).clip(shape)
            .background(if (cell.negative) colors.dayNegativeFill else scheme.surface)
            .then(if (purchase) Modifier.border(BorderStroke(2.dp, colors.expenseFill), shape) else Modifier)
            .padding(vertical = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // day number: faint, today at full contrast on an accent square
        Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            if (cell.isToday) {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(scheme.primary).padding(horizontal = Spacing.xs), contentAlignment = Alignment.Center) {
                    Text(cell.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = scheme.onPrimary, maxLines = 1)
                }
            } else {
                Text(cell.label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurface.copy(alpha = 0.55f), maxLines = 1)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            cell.purchaseText?.let { text ->
                Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.negative, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                cell.closingText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (cell.negative) colors.negative else scheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
