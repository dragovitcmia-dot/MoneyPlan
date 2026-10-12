package com.cashflow.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cashflow.app.R
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.RecurrencePattern
import com.cashflow.app.engine.RecurringRule
import com.cashflow.app.engine.TxKind
import com.cashflow.app.ui.components.LabeledTextField
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

internal enum class ScheduleKind(@StringRes val label: Int) {
    DAILY(R.string.choice_daily),
    WEEKLY(R.string.choice_weekly),
    EVERY_N(R.string.choice_every_n),
    MONTH_DAY(R.string.choice_month_day),
    MONTH_FIRST(R.string.choice_month_first),
    MONTH_LAST(R.string.choice_month_last),
    ;

    companion object {
        /** "Every day" is for income only, "every few days" for expenses only (the same rule as the composer). */
        fun optionsFor(kind: TxKind): List<ScheduleKind> =
            if (kind == TxKind.INCOME) listOf(DAILY, WEEKLY, MONTH_DAY, MONTH_FIRST, MONTH_LAST)
            else listOf(WEEKLY, EVERY_N, MONTH_DAY, MONTH_FIRST, MONTH_LAST)
    }
}

private fun RecurrencePattern.toKind() = when (this) {
    RecurrencePattern.Daily -> ScheduleKind.DAILY
    is RecurrencePattern.Weekly -> ScheduleKind.WEEKLY
    is RecurrencePattern.DayOfMonth -> ScheduleKind.MONTH_DAY
    RecurrencePattern.MonthFirst -> ScheduleKind.MONTH_FIRST
    RecurrencePattern.MonthLast -> ScheduleKind.MONTH_LAST
    is RecurrencePattern.EveryNDays -> ScheduleKind.EVERY_N
}

/**
 * Add or change a whole series: a regular income (INCOME) or a repeating payment (EXPENSE).
 * Saving regenerates only the entries that are still waiting in the future. An income can be deleted;
 * a repeating payment can be stopped (it ends today, its waiting future entries are removed).
 */
@Composable
internal fun RuleDialog(
    kind: TxKind,
    initial: RecurringRule?,
    newStart: LocalDate,
    onSave: (RecurringRule) -> Unit,
    onDelete: (() -> Unit)?,
    onStop: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val isExpense = kind == TxKind.EXPENSE
    val options = remember(kind) { ScheduleKind.optionsFor(kind) }
    var name by remember { mutableStateOf(initial?.description ?: "") }
    var amountText by remember { mutableStateOf(initial?.let { Money.format(it.amount, withCurrency = false) } ?: "") }
    var schedule by remember { mutableStateOf(initial?.pattern?.toKind()?.takeIf { it in options } ?: options.first()) }
    var weekday by remember { mutableStateOf((initial?.pattern as? RecurrencePattern.Weekly)?.dayOfWeek ?: DayOfWeek.MONDAY) }
    var dayText by remember { mutableStateOf(((initial?.pattern as? RecurrencePattern.DayOfMonth)?.day ?: 1).toString()) }
    var everyNText by remember { mutableStateOf(((initial?.pattern as? RecurrencePattern.EveryNDays)?.n ?: 7).toString()) }
    var hasEnd by remember { mutableStateOf(isExpense && initial?.endDate != null) }
    var endDate by remember { mutableStateOf(initial?.endDate ?: LocalDate.now().plusMonths(1)) }
    var confirmTime by remember { mutableStateOf(initial?.confirmTime ?: LocalTime.NOON) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }

    val amount = Money.parse(amountText)?.takeIf { it > 0 }
    val day = dayText.toIntOrNull()?.takeIf { it in 1..31 }
    val everyN = everyNText.toIntOrNull()?.takeIf { it >= 1 }
    val valid = name.isNotBlank() && amount != null &&
        (schedule != ScheduleKind.MONTH_DAY || day != null) &&
        (schedule != ScheduleKind.EVERY_N || everyN != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when {
                        isExpense -> R.string.edit_repeating_title
                        initial == null -> R.string.add_regular_income
                        else -> R.string.edit_regular_income
                    },
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(if (isExpense) R.string.repeating_name_label else R.string.source_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(stringResource(R.string.amount_label)) },
                    singleLine = true,
                    isError = amountText.isNotEmpty() && amount == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.source_how_often), style = MaterialTheme.typography.labelLarge)
                for (option in options) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { schedule = option },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = schedule == option, onClick = { schedule = option })
                        Text(stringResource(option.label))
                    }
                }
                if (schedule == ScheduleKind.WEEKLY) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (dow in DayOfWeek.entries) {
                            FilterChip(
                                selected = weekday == dow,
                                onClick = { weekday = dow },
                                label = { Text(dow.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                                modifier = Modifier.heightIn(min = 56.dp),
                            )
                        }
                    }
                }
                if (schedule == ScheduleKind.EVERY_N) {
                    LabeledTextField(
                        value = everyNText,
                        onValueChange = { everyNText = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.every_n_label)) },
                        singleLine = true,
                        isError = everyN == null,
                        supportingText = { if (everyN == null) Text(stringResource(R.string.problem_every_n)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (schedule == ScheduleKind.MONTH_DAY) {
                    LabeledTextField(
                        value = dayText,
                        onValueChange = { dayText = it },
                        label = { Text(stringResource(R.string.day_of_month_label)) },
                        singleLine = true,
                        isError = day == null,
                        supportingText = { Text(stringResource(R.string.day_of_month_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (isExpense) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.repeating_ends_switch), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = hasEnd, onCheckedChange = { hasEnd = it })
                    }
                    if (hasEnd) DateField(stringResource(R.string.repeating_ends_label), endDate) { endDate = it }
                } else {
                    TimeField(label = stringResource(R.string.source_ask_time), value = confirmTime, onPick = { confirmTime = it })
                }
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.delete_regular_income), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (onStop != null) {
                    TextButton(onClick = { confirmStop = true }, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.stop_repeating), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                modifier = Modifier.heightIn(min = 56.dp),
                onClick = {
                    val pattern = when (schedule) {
                        ScheduleKind.DAILY -> RecurrencePattern.Daily
                        ScheduleKind.WEEKLY -> RecurrencePattern.Weekly(weekday)
                        ScheduleKind.EVERY_N ->
                            RecurrencePattern.EveryNDays(everyN!!, (initial?.pattern as? RecurrencePattern.EveryNDays)?.anchorDate ?: initial?.startDate ?: newStart)
                        ScheduleKind.MONTH_DAY -> RecurrencePattern.DayOfMonth(day!!)
                        ScheduleKind.MONTH_FIRST -> RecurrencePattern.MonthFirst
                        ScheduleKind.MONTH_LAST -> RecurrencePattern.MonthLast
                    }
                    onSave(
                        RecurringRule(
                            id = initial?.id ?: 0,
                            amount = amount!!,
                            kind = kind,
                            description = name.trim(),
                            pattern = pattern,
                            startDate = initial?.startDate ?: newStart,
                            endDate = if (isExpense) endDate.takeIf { hasEnd } else initial?.endDate,
                            enabled = initial?.enabled ?: true,
                            confirmTime = confirmTime,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title, initial?.description.orEmpty())) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.delete_body)) } },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmStop && onStop != null) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.stop_title, initial?.description.orEmpty())) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.stop_body)) } },
            confirmButton = { TextButton(onClick = { confirmStop = false; onStop() }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.stop_repeating)) } },
            dismissButton = { TextButton(onClick = { confirmStop = false }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
