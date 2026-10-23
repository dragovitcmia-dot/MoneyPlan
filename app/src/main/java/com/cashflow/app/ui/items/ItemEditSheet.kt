package com.cashflow.app.ui.items

import androidx.compose.foundation.layout.Arrangement
import com.cashflow.app.ui.theme.Spacing
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cashflow.app.R
import com.cashflow.app.engine.ItemEdits
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.LabeledTextField
import com.cashflow.app.ui.settings.DateField
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Change ONE item: amount, description and date, answer it (Done / Skip) or put it back to waiting, or delete
 * it. For an item that comes from a repeating payment only this day changes; "Edit this repeating payment"
 * opens the whole series.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditSheet(
    tx: Transaction,
    actions: ItemActions,
    onDismiss: () -> Unit,
    today: LocalDate,
    /** After Save or Delete: the whole item flow closes (a plain dismiss only goes back to the item sheet). */
    onDone: () -> Unit = onDismiss,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var amountText by remember(tx.id) { mutableStateOf(Money.formatInput(tx.amount)) }
    var description by remember(tx.id) { mutableStateOf(tx.description) }
    var date by remember(tx.id) { mutableStateOf(tx.date) }
    var confirmDelete by remember(tx.id) { mutableStateOf(false) }

    val typed = Money.parse(amountText, maxDecimals = 2)?.takeIf { ItemEdits.canSave(it) }
    // An untouched amount keeps its exact stored value (the field only shows 2 decimals).
    val amount = typed?.let { if (it == Money.roundToCents(tx.amount)) tx.amount else it }
    val fromSeries = ItemEdits.isFromSeries(tx)
    val label = actions.labelOf(tx)

    fun close(after: () -> Unit) {
        after()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDone() }
    }

    com.cashflow.app.ui.theme.GlassSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).padding(bottom = Spacing.l).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(stringResource(R.string.item_edit_title), style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            LabeledTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text(stringResource(R.string.amount_label)) },
                singleLine = true,
                isError = typed == null,
                supportingText = { if (typed == null) Text(stringResource(R.string.amount_error_decimals)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            LabeledTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.description_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // Today to forever, whatever the item's own date.
            DateField(stringResource(R.string.item_date_label), date, earliest = com.cashflow.app.engine.ItemRules.earliestDate(today)) { date = it }

            AppButton(
                stringResource(R.string.action_save),
                onClick = { amount?.let { a -> close { actions.save(tx, a, description, date) } } },
                enabled = amount != null && com.cashflow.app.engine.ItemRules.canSaveChange(tx, date, today),
                modifier = Modifier.fillMaxWidth(),
            )
            // Permanent history: an accepted item, or one dated before today, cannot be deleted.
            if (!com.cashflow.app.engine.HistoryGuard.isHistory(tx, today)) {
                AppButton(
                    stringResource(R.string.action_delete),
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                    style = AppButtonStyle.Outlined,
                    contentColor = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.item_delete_title, label)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(if (fromSeries) R.string.item_delete_series_body else R.string.item_delete_manual_body))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        close { actions.delete(tx) }
                    },
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
