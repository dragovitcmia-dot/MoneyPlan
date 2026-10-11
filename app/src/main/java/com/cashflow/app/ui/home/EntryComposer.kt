package com.cashflow.app.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cashflow.app.engine.AmountInput
import com.cashflow.app.engine.EntryPlanner
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.NewEntry
import com.cashflow.app.engine.RecurrencePattern
import com.cashflow.app.engine.TxKind
import com.cashflow.app.engine.WalletMode
import com.cashflow.app.engine.WhatIfResult
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.pressScale
import com.cashflow.app.ui.rememberHaptics
import com.cashflow.app.ui.settings.DateField
import com.cashflow.app.ui.theme.cashflow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private enum class Timing(val label: String) { NOW("Now"), PLANNED("Planned"), RECURRING("Recurring") }

private enum class PatternChoice(val label: String) {
    DAILY("Every day"),
    WEEKLY("Weekly"),
    EVERY_N("Every N days"),
    MONTH_FIRST("First of month"),
    MONTH_LAST("Last of month"),
    MONTH_DAY("Day of month"),
}

private fun choicesFor(kind: TxKind): List<PatternChoice> =
    if (kind == TxKind.INCOME) {
        listOf(PatternChoice.DAILY, PatternChoice.WEEKLY, PatternChoice.MONTH_FIRST, PatternChoice.MONTH_LAST, PatternChoice.MONTH_DAY)
    } else {
        listOf(PatternChoice.WEEKLY, PatternChoice.EVERY_N, PatternChoice.MONTH_FIRST, PatternChoice.MONTH_LAST, PatternChoice.MONTH_DAY)
    }

/**
 * Shared composer for "Add expense" and "Add income". Amount via the in-app keypad, then when it
 * happens: Now (already happened), Planned (one date) or Recurring. Planned and recurring expenses
 * run through the what-if safety check before saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryComposer(
    kind: TxKind,
    ui: HomeUiState,
    whatIf: (NewEntry) -> WhatIfResult?,
    onSave: suspend (NewEntry) -> Unit,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    var amountText by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var timing by remember { mutableStateOf(Timing.NOW) }
    var plannedDate by remember { mutableStateOf(ui.today.plusDays(1)) }
    val choices = remember(kind) { choicesFor(kind) }
    var choice by remember { mutableStateOf(choices.first()) }
    var weekday by remember { mutableStateOf(ui.today.dayOfWeek) }
    var everyN by remember { mutableStateOf("7") }
    var dayOfMonth by remember { mutableStateOf(ui.today.dayOfMonth.toString()) }
    val defaultStart = if (kind == TxKind.INCOME) ui.newSourceStart else ui.today
    var startDate by remember { mutableStateOf(defaultStart) }
    var hasEnd by remember { mutableStateOf(false) }
    var endDate by remember { mutableStateOf(defaultStart.plusMonths(1)) }
    var saving by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf<Pair<NewEntry, WhatIfResult>?>(null) }

    val amount = AmountInput.toMillimes(amountText)
    val nValue = everyN.toIntOrNull()?.takeIf { it >= 1 }
    val dayValue = dayOfMonth.toIntOrNull()?.takeIf { it in 1..31 }

    val pattern: RecurrencePattern? = when (choice) {
        PatternChoice.DAILY -> RecurrencePattern.Daily
        PatternChoice.WEEKLY -> RecurrencePattern.Weekly(weekday)
        PatternChoice.EVERY_N -> nValue?.let { RecurrencePattern.EveryNDays(it, startDate) }
        PatternChoice.MONTH_FIRST -> RecurrencePattern.MonthFirst
        PatternChoice.MONTH_LAST -> RecurrencePattern.MonthLast
        PatternChoice.MONTH_DAY -> dayValue?.let { RecurrencePattern.DayOfMonth(it) }
    }

    val entry: NewEntry? = when (timing) {
        Timing.NOW -> NewEntry.Now(kind, amount, description)
        Timing.PLANNED -> NewEntry.Planned(kind, amount, description, plannedDate)
        Timing.RECURRING -> pattern?.let { NewEntry.Recurring(kind, amount, description, it, startDate, endDate.takeIf { hasEnd }) }
    }
    val problem = entry?.let { EntryPlanner.validate(it) } ?: "Complete the schedule."
    val canSave = entry != null && problem == null && !saving

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun commit(e: NewEntry) {
        scope.launch {
            saving = true
            onSave(e)
            haptics.confirm()
            onSaved(
                when (e) {
                    is NewEntry.Recurring -> if (e.kind == TxKind.EXPENSE) "Recurring expense added" else "Income source added"
                    else -> if (e.kind == TxKind.EXPENSE) "Expense saved" else "Income saved"
                },
            )
            dismiss()
        }
    }

    fun onSaveClicked() {
        val e = entry ?: return
        // Safety check: planned and recurring expenses are tested against the next 180 days.
        val check = if (e.kind == TxKind.EXPENSE && e !is NewEntry.Now) whatIf(e) else null
        if (check != null && check.wouldCauseNegative) {
            warning = e to check
        } else {
            commit(e)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(if (kind == TxKind.EXPENSE) "Add expense" else "Add income", style = MaterialTheme.typography.titleLarge)

            // Amount display
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Text(
                    AmountInput.display(amountText),
                    style = MaterialTheme.typography.displayLarge,
                    color = if (amountText.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    " TND",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            Keypad(
                onKey = { amountText = AmountInput.press(amountText, it); haptics.tick() },
                onBackspace = { amountText = AmountInput.backspace(amountText); haptics.tick() },
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                placeholder = { Text(EntryPlanner.defaultDescription(kind)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Timing.entries.forEachIndexed { index, t ->
                    SegmentedButton(
                        selected = timing == t,
                        onClick = { timing = t; haptics.tick() },
                        shape = SegmentedButtonDefaults.itemShape(index, Timing.entries.size),
                    ) { Text(t.label) }
                }
            }

            when (timing) {
                Timing.NOW -> Text(
                    if (kind == TxKind.EXPENSE) "Already spent: counts today." else "Already received: counts today.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Timing.PLANNED -> {
                    DateField("Date", plannedDate) { plannedDate = it }
                    if (!plannedDate.isAfter(ui.today)) {
                        Text(
                            "Today or earlier: it will show up in To confirm.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Timing.RECURRING -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in choices) {
                            FilterChip(selected = choice == c, onClick = { choice = c; haptics.tick() }, label = { Text(c.label) })
                        }
                    }
                    when (choice) {
                        PatternChoice.WEEKLY -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (dow in DayOfWeek.entries) {
                                FilterChip(
                                    selected = weekday == dow,
                                    onClick = { weekday = dow },
                                    label = { Text(dow.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                                )
                            }
                        }
                        PatternChoice.EVERY_N -> OutlinedTextField(
                            value = everyN,
                            onValueChange = { everyN = it.filter(Char::isDigit).take(3) },
                            label = { Text("Every N days") },
                            singleLine = true,
                            isError = nValue == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PatternChoice.MONTH_DAY -> OutlinedTextField(
                            value = dayOfMonth,
                            onValueChange = { dayOfMonth = it.filter(Char::isDigit).take(2) },
                            label = { Text("Day of month (1-31)") },
                            supportingText = { Text("Shorter months use their last day.") },
                            singleLine = true,
                            isError = dayValue == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        else -> Unit
                    }
                    DateField("Starts", startDate) { startDate = it }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("End date", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = hasEnd, onCheckedChange = { hasEnd = it })
                    }
                    if (hasEnd) DateField("Ends", endDate) { endDate = it }
                    if (kind == TxKind.INCOME && ui.walletMode == WalletMode.STATIC) {
                        Text(
                            "Wallet mode is Static, so income sources are ignored until you switch back to Dynamic.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (amount > 0 && problem != null && entry != null) {
                Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.cashflow.negative)
            }

            AppButton(
                text = if (saving) "Saving…" else "Save",
                onClick = ::onSaveClicked,
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            )
        }
    }

    warning?.let { (pendingEntry, result) ->
        AlertDialog(
            onDismissRequest = { warning = null; haptics.reject() },
            title = { Text("This would put you below zero") },
            text = { Text(warningText(result)) },
            confirmButton = {
                TextButton(onClick = { warning = null; commit(pendingEntry) }) { Text("Save anyway") }
            },
            dismissButton = {
                TextButton(onClick = { warning = null; haptics.reject() }) { Text("Cancel") }
            },
        )
    }
}

private fun warningText(r: WhatIfResult): String = buildString {
    r.firstNegativeDate?.let { append("Your balance would be negative from $it.") }
    r.lowestBalanceDate?.let { append("\nLowest point: ${Money.formatDisplay(r.lowestBalance)} on $it.") }
    r.firstNegativePlannedExpense?.let {
        append("\nFirst planned expense you couldn't cover: ${it.description.ifBlank { "Expense" }} on ${it.date}.")
    }
    val days = r.newNegativeDays.size
    append("\n$days day${if (days == 1) "" else "s"} would be newly negative (or worse) in the next 180 days.")
}

@Composable
private fun Keypad(onKey: (Char) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf("123", "456", "789", ".0<")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (key in row) {
                    KeypadKey(
                        label = if (key == '<') "⌫" else key.toString(),
                        onClick = { if (key == '<') onBackspace() else onKey(key) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun KeypadKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier.height(52.dp).pressScale(source),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.cashflow.elevated,
        interactionSource = source,
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}
