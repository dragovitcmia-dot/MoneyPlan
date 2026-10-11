package com.cashflow.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.RecurrencePattern
import com.cashflow.app.engine.RecurringRule
import com.cashflow.app.engine.TxKind
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

private enum class ScheduleKind(val label: String) {
    DAILY("Every day"),
    WEEKLY("Every week"),
    MONTH_DAY("Day of month"),
    MONTH_FIRST("First day of month"),
    MONTH_LAST("Last day of month"),
}

private fun RecurrencePattern.toKind() = when (this) {
    RecurrencePattern.Daily -> ScheduleKind.DAILY
    is RecurrencePattern.Weekly -> ScheduleKind.WEEKLY
    is RecurrencePattern.DayOfMonth -> ScheduleKind.MONTH_DAY
    RecurrencePattern.MonthFirst -> ScheduleKind.MONTH_FIRST
    RecurrencePattern.MonthLast -> ScheduleKind.MONTH_LAST
    is RecurrencePattern.EveryNDays -> ScheduleKind.DAILY // not offered for income sources
}

private fun dayName(d: DayOfWeek) = d.getDisplayName(TextStyle.FULL, Locale.getDefault())

internal fun scheduleSummary(p: RecurrencePattern): String = when (p) {
    RecurrencePattern.Daily -> "Every day"
    is RecurrencePattern.Weekly -> "Every ${dayName(p.dayOfWeek)}"
    is RecurrencePattern.DayOfMonth -> "Day ${p.day} of each month"
    RecurrencePattern.MonthFirst -> "First day of each month"
    RecurrencePattern.MonthLast -> "Last day of each month"
    is RecurrencePattern.EveryNDays -> "Every ${p.n} days"
}

@Composable
internal fun IncomeSourcesSection(viewModel: SettingsViewModel) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    // null = closed, 0-id rule = adding, otherwise editing that source
    var editing by remember { mutableStateOf<RecurringRule?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Income sources", style = MaterialTheme.typography.titleMedium)
        if (sources.isEmpty()) {
            Text("No income sources yet.", style = MaterialTheme.typography.bodyMedium)
        }
        for (source in sources) {
            Card(Modifier.fillMaxWidth().clickable { editing = source }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.description, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${Money.format(source.amount)} · ${scheduleSummary(source.pattern)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = source.enabled,
                        onCheckedChange = { viewModel.setSourceEnabled(source.id, it) },
                    )
                }
            }
        }
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add income source") }
    }

    if (adding) {
        SourceDialog(
            initial = null,
            newStart = viewModel.newSourceStartDate(),
            onSave = { viewModel.saveSource(it); adding = false },
            onDelete = null,
            onDismiss = { adding = false },
        )
    }
    editing?.let { source ->
        SourceDialog(
            initial = source,
            newStart = source.startDate,
            onSave = { viewModel.saveSource(it); editing = null },
            onDelete = { viewModel.deleteSource(source.id); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SourceDialog(
    initial: RecurringRule?,
    newStart: java.time.LocalDate,
    onSave: (RecurringRule) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.description ?: "") }
    var amountText by remember { mutableStateOf(initial?.let { Money.format(it.amount, withCurrency = false) } ?: "") }
    var kind by remember { mutableStateOf(initial?.pattern?.toKind() ?: ScheduleKind.DAILY) }
    var weekday by remember { mutableStateOf((initial?.pattern as? RecurrencePattern.Weekly)?.dayOfWeek ?: DayOfWeek.MONDAY) }
    var dayText by remember { mutableStateOf(((initial?.pattern as? RecurrencePattern.DayOfMonth)?.day ?: 1).toString()) }
    var confirmTime by remember { mutableStateOf(initial?.confirmTime ?: LocalTime.NOON) }
    var confirmDelete by remember { mutableStateOf(false) }

    val amount = Money.parse(amountText)?.takeIf { it > 0 }
    val day = dayText.toIntOrNull()?.takeIf { it in 1..31 }
    val valid = name.isNotBlank() && amount != null && (kind != ScheduleKind.MONTH_DAY || day != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add income source" else "Edit income source") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (TND)") },
                    singleLine = true,
                    isError = amountText.isNotEmpty() && amount == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Schedule", style = MaterialTheme.typography.labelLarge)
                for (option in ScheduleKind.entries) {
                    Row(
                        Modifier.fillMaxWidth().clickable { kind = option },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kind == option, onClick = { kind = option })
                        Text(option.label)
                    }
                }
                if (kind == ScheduleKind.WEEKLY) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (dow in DayOfWeek.entries) {
                            FilterChip(
                                selected = weekday == dow,
                                onClick = { weekday = dow },
                                label = { Text(dow.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                            )
                        }
                    }
                }
                if (kind == ScheduleKind.MONTH_DAY) {
                    OutlinedTextField(
                        value = dayText,
                        onValueChange = { dayText = it },
                        label = { Text("Day of month (1-31)") },
                        singleLine = true,
                        isError = day == null,
                        supportingText = { Text("Shorter months use their last day.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TimeField(label = "Confirm time", value = confirmTime, onPick = { confirmTime = it })
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete source", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val pattern = when (kind) {
                        ScheduleKind.DAILY -> RecurrencePattern.Daily
                        ScheduleKind.WEEKLY -> RecurrencePattern.Weekly(weekday)
                        ScheduleKind.MONTH_DAY -> RecurrencePattern.DayOfMonth(day!!)
                        ScheduleKind.MONTH_FIRST -> RecurrencePattern.MonthFirst
                        ScheduleKind.MONTH_LAST -> RecurrencePattern.MonthLast
                    }
                    onSave(
                        RecurringRule(
                            id = initial?.id ?: 0,
                            amount = amount!!,
                            kind = TxKind.INCOME,
                            description = name.trim(),
                            pattern = pattern,
                            startDate = initial?.startDate ?: newStart,
                            endDate = initial?.endDate,
                            enabled = initial?.enabled ?: true,
                            confirmTime = confirmTime,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${initial?.description}\"?") },
            text = { Text("Future pending occurrences are removed. Confirmed and skipped history is kept.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
