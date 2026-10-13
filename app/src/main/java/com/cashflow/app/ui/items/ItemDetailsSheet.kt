package com.cashflow.app.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.R
import com.cashflow.app.engine.AppDates
import com.cashflow.app.engine.ItemEdits
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.Postpone
import com.cashflow.app.engine.PostponeChoice
import com.cashflow.app.engine.RecurringRule
import com.cashflow.app.engine.SeriesContext
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import com.cashflow.app.engine.TxStatus
import com.cashflow.app.ui.components.AnswerButtons
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.LabeledTextField
import com.cashflow.app.ui.home.HomeViewModel
import com.cashflow.app.ui.settings.DatePickerDialogFor
import com.cashflow.app.ui.settings.scheduleSummary
import com.cashflow.app.ui.theme.cashflow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Finds the series [tx] belongs to and opens its details. Used by Home, Calendar and Upcoming. */
@Composable
fun ItemDetails(
    tx: Transaction,
    home: HomeViewModel,
    actions: ItemActions,
    today: LocalDate,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val rules by home.rules.collectAsStateWithLifecycle()
    val series by home.series.collectAsStateWithLifecycle()
    val rule = tx.recurringRuleId?.let { id -> rules.firstOrNull { it.id == id } }
    ItemDetailsSheet(tx, rule, actions, today, onDismiss, onEdit, series)
}

/**
 * What an item is (name, type, amount, date, whether and how it repeats) and everything that can be done with it:
 * accept with an editable amount, reject (after a warning), postpone, change it, and, for an item from a repeating
 * payment, change, end or delete the whole series.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItemDetailsSheet(
    tx: Transaction,
    rule: RecurringRule?,
    actions: ItemActions,
    today: LocalDate,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    series: SeriesContext = SeriesContext(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var amountText by remember(tx.id) { mutableStateOf(Money.formatInput(tx.amount)) }
    var pickingDate by remember(tx.id) { mutableStateOf(false) }

    val typed = Money.parse(amountText, maxDecimals = 2)?.takeIf { ItemEdits.canSave(it) }
    val label = actions.labelOf(tx)
    val income = tx.kind == TxKind.INCOME
    val canAnswer = ItemEdits.canAnswer(tx, today)

    fun close(after: () -> Unit) {
        after()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.details_title), style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.titleLarge)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(stringResource(R.string.detail_type), stringResource(if (income) R.string.detail_kind_income else R.string.detail_kind_expense))
                DetailRow(
                    stringResource(R.string.detail_amount),
                    Money.formatSigned(tx.kind, tx.amount),
                    valueColor = if (income) MaterialTheme.cashflow.positive else MaterialTheme.cashflow.negative,
                )
                DetailRow(stringResource(R.string.detail_date), AppDates.withWeekday(tx.date))
                DetailRow(
                    stringResource(R.string.detail_status),
                    stringResource(
                        when (tx.status) {
                            TxStatus.PENDING -> R.string.status_waiting
                            TxStatus.CONFIRMED -> R.string.status_done
                            TxStatus.SKIPPED -> R.string.status_skipped
                        },
                    ),
                )
                DetailRow(
                    stringResource(R.string.detail_repeat),
                    if (rule == null) {
                        stringResource(R.string.detail_one_time)
                    } else {
                        val every = scheduleSummary(rule.pattern)
                        rule.endDate?.let { stringResource(R.string.detail_series_line, every, AppDates.format(it)) } ?: every
                    },
                )
            }

            if (canAnswer) {
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
                // Red cross asks before rejecting; green check accepts with the amount above.
                AnswerButtons(
                    onReject = { close { actions.requestReject(tx) } },
                    onAccept = {
                        typed?.let { a ->
                            // Unchanged (also after rounding to 2 decimals) means: keep the stored amount exactly.
                            val changed = a != tx.amount && a != Money.roundToCents(tx.amount)
                            close { actions.confirm(tx, newAmount = a.takeIf { changed }) }
                        }
                    },
                    acceptEnabled = typed != null,
                )
            } else if (tx.status == TxStatus.PENDING) {
                Text(
                    stringResource(R.string.details_future_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AppButton(
                    stringResource(R.string.action_mark_waiting),
                    onClick = { close { actions.markWaiting(tx) } },
                    modifier = Modifier.fillMaxWidth(),
                    style = AppButtonStyle.Tonal,
                )
            }

            if (Postpone.canPostpone(tx)) {
                HorizontalDivider()
                Text(stringResource(R.string.action_postpone), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (rule != null) R.string.postpone_series_note else R.string.postpone_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // One suggestion, chosen from the nature of the item, and a date picker for everything else.
                val suggestion = Postpone.suggestionFor(rule?.pattern)
                val suggestedDate = Postpone.suggestedDate(tx, rule?.pattern, today)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (suggestedDate != null) {
                        AppButton(
                            stringResource(
                                R.string.postpone_suggestion,
                                stringResource(
                                    when (suggestion) {
                                        PostponeChoice.TOMORROW -> R.string.postpone_tomorrow
                                        PostponeChoice.IN_3_DAYS -> R.string.postpone_3_days
                                        PostponeChoice.IN_1_WEEK -> R.string.postpone_1_week
                                        PostponeChoice.NEXT_WEEK -> R.string.postpone_next_week
                                    },
                                ),
                                AppDates.formatShort(suggestedDate, today),
                            ),
                            onClick = { close { actions.postpone(tx, suggestedDate) } },
                            style = AppButtonStyle.Tonal,
                        )
                    }
                    AppButton(stringResource(R.string.postpone_pick), onClick = { pickingDate = true }, style = AppButtonStyle.Outlined)
                }
            }

            TextButton(onClick = { close { onEdit() } }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.details_edit_item), style = MaterialTheme.typography.titleSmall)
            }

            if (rule != null) {
                HorizontalDivider()
                Text(stringResource(R.string.series_section_title), style = MaterialTheme.typography.titleMedium)
                // Edit series, Extend, End series and Delete series: for an item of a series in ANY status (they only affect today and later).
                SeriesActions(rule, today, actions, series, onClose = { after -> close(after) })
            }
        }
    }

    if (pickingDate) {
        // Opens on the item's own date (selected) with today outlined by the picker; only later days can be chosen.
        DatePickerDialogFor(
            initial = tx.date,
            earliest = maxOf(tx.date, today),
            isValid = { Postpone.isValidDate(tx, it) },
            onPick = { date -> close { actions.postpone(tx, date) } },
            onDismiss = { pickingDate = false },
        )
    }

}

@Composable
private fun DetailRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (valueColor == androidx.compose.ui.graphics.Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor,
            modifier = Modifier.weight(0.6f),
        )
    }
}
