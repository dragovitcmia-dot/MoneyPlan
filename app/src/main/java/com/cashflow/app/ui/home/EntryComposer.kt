package com.cashflow.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.cashflow.app.ui.components.LabeledTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cashflow.app.R
import com.cashflow.app.engine.AmountInput
import com.cashflow.app.engine.EntryForm
import com.cashflow.app.engine.EntryFormState
import com.cashflow.app.engine.FormProblem
import com.cashflow.app.engine.FormResult
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.NewEntry
import com.cashflow.app.engine.PatternChoice
import com.cashflow.app.engine.Timing
import com.cashflow.app.engine.TxKind
import com.cashflow.app.engine.WalletMode
import com.cashflow.app.engine.WhatIfResult
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.fullBleed
import com.cashflow.app.ui.components.quantityString
import com.cashflow.app.ui.rememberHaptics
import com.cashflow.app.ui.settings.DateField
import com.cashflow.app.ui.theme.cashflow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@Composable
private fun PatternChoice.label() = stringResource(
    when (this) {
        PatternChoice.DAILY -> R.string.choice_daily
        PatternChoice.WEEKLY -> R.string.choice_weekly
        PatternChoice.EVERY_N -> R.string.choice_every_n
        PatternChoice.MONTH_FIRST -> R.string.choice_month_first
        PatternChoice.MONTH_LAST -> R.string.choice_month_last
        PatternChoice.MONTH_DAY -> R.string.choice_month_day
    },
)

@Composable
private fun Timing.label() = stringResource(
    when (this) {
        Timing.NOW -> R.string.timing_now
        Timing.LATER -> R.string.timing_later
        Timing.REPEATS -> R.string.timing_repeats
    },
)

@Composable
private fun FormProblem.message() = stringResource(
    when (this) {
        FormProblem.AMOUNT_MISSING -> R.string.problem_amount
        FormProblem.EVERY_N_INVALID -> R.string.problem_every_n
        FormProblem.DAY_OF_MONTH_INVALID -> R.string.problem_day
        FormProblem.END_BEFORE_START -> R.string.problem_end
    },
)

/**
 * Shared composer for EXPENSE and INCOME: amount on the keypad, then when it happens: Now, Later
 * or Repeats. Every expense is run through the what-if safety check before it is saved.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryComposer(
    kind: TxKind,
    ui: HomeUiState,
    whatIf: suspend (NewEntry) -> WhatIfResult?,
    onSave: suspend (NewEntry) -> Unit,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val colors = MaterialTheme.cashflow
    val isExpense = kind == TxKind.EXPENSE
    val accentFill = if (isExpense) colors.expenseFill else colors.incomeFill

    var form by remember { mutableStateOf(EntryFormState.initial(kind, ui.today)) }
    var tried by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf<Pair<NewEntry, WhatIfResult>?>(null) }

    val defaultText = stringResource(if (isExpense) R.string.item_expense else R.string.item_income)
    val result = EntryForm.build(form, defaultText, ui.balanceStartDate)
    val problems = (result as? FormResult.Invalid)?.problems.orEmpty()
    val choices = remember(kind) { EntryForm.choicesFor(kind) }

    val savedMessage = stringResource(
        when {
            form.timing == Timing.REPEATS && isExpense -> R.string.saved_repeating_expense
            form.timing == Timing.REPEATS -> R.string.saved_repeating_income
            isExpense -> R.string.saved_expense
            else -> R.string.saved_income
        },
    )

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun commit(entry: NewEntry) {
        scope.launch {
            saving = true
            onSave(entry)
            haptics.confirm()
            onSaved(savedMessage)
            dismiss()
        }
    }

    fun onSaveClicked() {
        val entry = (result as? FormResult.Valid)?.entry
        if (entry == null) {
            tried = true // show exactly which fields need attention
            haptics.reject()
            return
        }
        scope.launch {
            saving = true
            // Safety check: every expense is tested against the next 180 days before it is saved.
            val check = if (entry.kind == TxKind.EXPENSE) whatIf(entry) else null
            saving = false
            if (check != null && check.wouldCauseNegative) warning = entry to check else commit(entry)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header: a band in the kind color with white bold text, like the big button on Home.
            Box(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(accentFill).padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (isExpense) R.string.button_expense else R.string.button_income).uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.buttonLabel,
                )
            }

            // Amount display
            val amountMissing = tried && FormProblem.AMOUNT_MISSING in problems
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Text(
                    AmountInput.display(form.amountText),
                    style = MaterialTheme.typography.displayLarge,
                    color = when {
                        amountMissing -> colors.negative
                        form.amountText.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                )
                Text(
                    " " + Money.CURRENCY,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            if (amountMissing) {
                Text(FormProblem.AMOUNT_MISSING.message(), style = MaterialTheme.typography.bodyMedium, color = colors.negative)
            }

            Keypad(
                modifier = Modifier.fullBleed(24.dp),
                onKey = { key -> form = form.copy(amountText = AmountInput.press(form.amountText, key)); haptics.tick() },
                onBackspace = { form = form.copy(amountText = AmountInput.backspace(form.amountText)); haptics.tick() },
                onClear = { form = form.copy(amountText = ""); haptics.confirm() },
            )

            LabeledTextField(
                value = form.description,
                onValueChange = { form = form.copy(description = it) },
                label = { Text(stringResource(R.string.description_label)) },
                placeholder = { Text(defaultText) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Wrapping chips (not a segmented bar) so the three words never get squeezed at large font sizes.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Timing.entries.forEach { t ->
                    FilterChip(
                        selected = form.timing == t,
                        onClick = { form = form.copy(timing = t); haptics.tick() },
                        label = { Text(t.label(), style = MaterialTheme.typography.titleSmall) },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                }
            }

            when (form.timing) {
                Timing.NOW -> Text(
                    stringResource(if (isExpense) R.string.now_hint_expense else R.string.now_hint_income),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Timing.LATER -> {
                    DateField(stringResource(R.string.later_date_label), form.laterDate) { form = form.copy(laterDate = it) }
                    if (!form.laterDate.isAfter(ui.today)) {
                        Text(
                            stringResource(R.string.later_past_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Timing.REPEATS -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in choices) {
                            FilterChip(
                                selected = form.choice == c,
                                onClick = { form = form.copy(choice = c); haptics.tick() },
                                label = { Text(c.label()) },
                                modifier = Modifier.heightIn(min = 56.dp),
                            )
                        }
                    }
                    when (form.choice) {
                        PatternChoice.WEEKLY -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (dow in DayOfWeek.entries) {
                                FilterChip(
                                    selected = form.weekday == dow,
                                    onClick = { form = form.copy(weekday = dow) },
                                    label = { Text(dow.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                                    modifier = Modifier.heightIn(min = 56.dp),
                                )
                            }
                        }
                        PatternChoice.EVERY_N -> LabeledTextField(
                            value = form.everyN,
                            onValueChange = { form = form.copy(everyN = it.filter(Char::isDigit).take(3)) },
                            label = { Text(stringResource(R.string.every_n_label)) },
                            singleLine = true,
                            isError = FormProblem.EVERY_N_INVALID in problems,
                            supportingText = { if (FormProblem.EVERY_N_INVALID in problems) Text(FormProblem.EVERY_N_INVALID.message()) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PatternChoice.MONTH_DAY -> LabeledTextField(
                            value = form.dayOfMonth,
                            onValueChange = { form = form.copy(dayOfMonth = it.filter(Char::isDigit).take(2)) },
                            label = { Text(stringResource(R.string.day_of_month_label)) },
                            supportingText = {
                                Text(
                                    if (FormProblem.DAY_OF_MONTH_INVALID in problems) {
                                        FormProblem.DAY_OF_MONTH_INVALID.message()
                                    } else {
                                        stringResource(R.string.day_of_month_hint)
                                    },
                                )
                            },
                            singleLine = true,
                            isError = FormProblem.DAY_OF_MONTH_INVALID in problems,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        else -> Unit
                    }
                    DateField(stringResource(R.string.starts_label), form.startDate) { form = form.copy(startDate = it) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.end_date_switch), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = form.hasEnd, onCheckedChange = { form = form.copy(hasEnd = it) })
                    }
                    if (form.hasEnd) {
                        DateField(stringResource(R.string.ends_label), form.endDate) { form = form.copy(endDate = it) }
                        if (FormProblem.END_BEFORE_START in problems) {
                            Text(FormProblem.END_BEFORE_START.message(), style = MaterialTheme.typography.bodyMedium, color = colors.negative)
                        }
                    }
                    if (kind == TxKind.INCOME && ui.walletMode == WalletMode.STATIC) {
                        Text(
                            stringResource(R.string.static_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // The confirm key uses the same color as the header and the big button on Home.
            AppButton(
                text = stringResource(if (saving) R.string.saving else R.string.action_save),
                onClick = ::onSaveClicked,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentFill, contentColor = colors.buttonLabel),
                textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }

    warning?.let { (pendingEntry, check) ->
        AlertDialog(
            onDismissRequest = { warning = null; haptics.reject() },
            title = { Text(stringResource(R.string.warning_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(warningText(check)) } },
            confirmButton = {
                TextButton(onClick = { warning = null; commit(pendingEntry) }) { Text(stringResource(R.string.warning_add_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { warning = null; haptics.reject() }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun warningText(r: WhatIfResult): String {
    val unknownItem = stringResource(R.string.item_expense)
    val lines = ArrayList<String>()
    r.firstNegativeDate?.let { lines += stringResource(R.string.warning_negative_from, it.toString()) }
    r.lowestBalanceDate?.let { lines += stringResource(R.string.warning_lowest, Money.formatDisplay(r.lowestBalance), it.toString()) }
    r.firstNegativePlannedExpense?.let {
        lines += stringResource(R.string.warning_first_expense, it.description.ifBlank { unknownItem }, it.date.toString())
    }
    val days = r.newNegativeDays.size
    lines += quantityString(R.plurals.warning_days, days, days)
    return lines.joinToString("\n")
}
