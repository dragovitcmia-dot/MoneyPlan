package com.cashflow.app.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.R
import com.cashflow.app.engine.ItemRules
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.Postpone
import com.cashflow.app.engine.RecurringRule
import com.cashflow.app.engine.SemanticColor
import com.cashflow.app.engine.SeriesContext
import com.cashflow.app.engine.Transaction
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.LabeledTextField
import com.cashflow.app.ui.components.color
import com.cashflow.app.ui.home.HomeViewModel
import com.cashflow.app.ui.settings.DatePickerDialogFor
import com.cashflow.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Which item sheet a screen shows. A screen holds ONE of these, so only one bottom sheet is ever visible: opening an item from a day
 * list replaces the list (the screen hides it while [item] is set) and dismissing the item sheet shows the list again. "Change" replaces
 * the item sheet the same way and goes back to it.
 */
@Stable
class ItemSheetState {
    var item by mutableStateOf<Transaction?>(null)
        private set
    var changing by mutableStateOf(false)
        private set

    /** The Move date picker opens by itself on the next sheet (a Lab check sets it). */
    var autoMove by mutableStateOf(false)
        private set

    fun open(tx: Transaction, move: Boolean = false) {
        item = tx
        changing = false
        autoMove = move
    }

    fun change() {
        if (item != null) changing = true
    }

    /** Back from "Change" to the item sheet. */
    fun backToItem() {
        changing = false
    }

    fun close() {
        item = null
        changing = false
        autoMove = false
    }
}

@Composable
fun rememberItemSheetState() = remember { ItemSheetState() }

/** The single host of every item sheet (Calendar day list, Upcoming, Home pending cards, notifications): renders at most ONE sheet. */
@Composable
fun ItemSheets(state: ItemSheetState, home: HomeViewModel, actions: ItemActions, today: LocalDate) {
    val tx = state.item ?: return
    val rules by home.rules.collectAsStateWithLifecycle()
    val series by home.series.collectAsStateWithLifecycle()
    val rule = tx.recurringRuleId?.let { id -> rules.firstOrNull { it.id == id } }
    if (state.changing) {
        ItemEditSheet(tx = tx, actions = actions, today = today, onDismiss = state::backToItem, onDone = state::close)
    } else {
        ItemSheet(tx, rule, series, actions, today, onDismiss = state::close, onChange = state::change, startMoving = state.autoMove)
    }
}

/**
 * THE item sheet: handle, name, amount (red out, green in), one plain meta line, then only what applies: one filled Accept button
 * (when due or overdue), one row of two equal outlined buttons (Change, Move date), "Mark as waiting" for a rejected item that may come
 * back, and for a series item one outlined "Repeating payment" menu. History shows only a plain line. Buttons are at least 48 dp tall.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemSheet(
    tx: Transaction,
    rule: RecurringRule?,
    seriesContext: SeriesContext,
    actions: ItemActions,
    today: LocalDate,
    onDismiss: () -> Unit,
    onChange: () -> Unit,
    startMoving: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var moving by remember(tx.id) { mutableStateOf(startMoving) }
    var asking by remember(tx.id) { mutableStateOf(false) }
    val allowed = ItemRules.actionsFor(tx, today)

    fun close(after: () -> Unit) {
        after()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l).padding(bottom = Spacing.l).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(actions.labelOf(tx), style = MaterialTheme.typography.titleLarge)
            Text(
                Money.formatSigned(tx.kind, tx.amount),
                style = MaterialTheme.typography.headlineLarge,
                color = SemanticColor.ofKind(tx.kind).color(),
            )
            Text(ItemRules.meta(tx, rule), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (allowed.history) {
                Text(
                    ItemRules.HISTORY_LINE,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = Spacing.m),
                )
            }
            if (allowed.accept || allowed.change || allowed.moveDate || allowed.markWaiting || allowed.series) {
                Column(Modifier.padding(top = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    if (allowed.accept) {
                        AppButton(
                            stringResource(R.string.action_accept),
                            onClick = { if (actions.asksAmount(tx, seriesContext)) asking = true else close { actions.confirm(tx) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (allowed.change || allowed.moveDate) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            if (allowed.change) {
                                AppButton(stringResource(R.string.item_change), onClick = onChange, modifier = Modifier.weight(1f), style = AppButtonStyle.Outlined)
                            }
                            if (allowed.moveDate) {
                                AppButton(stringResource(R.string.item_move_date), onClick = { moving = true }, modifier = Modifier.weight(1f), style = AppButtonStyle.Outlined)
                            }
                        }
                    }
                    if (allowed.markWaiting) {
                        AppButton(
                            stringResource(R.string.action_mark_waiting),
                            onClick = { close { actions.markWaiting(tx) } },
                            modifier = Modifier.fillMaxWidth(),
                            style = AppButtonStyle.Outlined,
                        )
                    }
                    if (allowed.series && rule != null) {
                        SeriesActions(rule, today, actions, seriesContext, onClose = { after -> close(after) }, asMenu = true)
                    }
                }
            }
        }
    }

    if (moving) {
        // Today to forever, whatever the item's own date; the suggested day is pre-selected.
        DatePickerDialogFor(
            initial = Postpone.suggestedDate(tx, rule?.pattern, today) ?: today,
            earliest = ItemRules.earliestDate(today),
            isValid = { ItemRules.canMoveTo(tx, it, today) },
            caption = ItemRules.pickerCaption(tx),
            onPick = { date -> close { actions.postpone(tx, date) } },
            onDismiss = { moving = false },
        )
    }
    if (asking) {
        AcceptAmountDialog(tx, onAccept = { amount -> asking = false; close { actions.confirm(tx, newAmount = amount.takeIf { it != tx.amount }) } }, onDismiss = { asking = false })
    }
}

/** A series that asks for the amount each time: Accept first asks what was really paid (the planned amount is pre-filled). */
@Composable
private fun AcceptAmountDialog(tx: Transaction, onAccept: (Long) -> Unit, onDismiss: () -> Unit) {
    var text by remember(tx.id) { mutableStateOf(Money.formatInput(tx.amount)) }
    val typed = Money.parse(text, maxDecimals = 2)?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.series_ask_amount)) },
        text = {
            LabeledTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.amount_label)) },
                singleLine = true,
                isError = typed == null,
                supportingText = { if (typed == null) Text(stringResource(R.string.amount_error_decimals)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { typed?.let { a -> onAccept(if (a == Money.roundToCents(tx.amount)) tx.amount else a) } },
                enabled = typed != null,
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.action_accept)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) } },
    )
}
