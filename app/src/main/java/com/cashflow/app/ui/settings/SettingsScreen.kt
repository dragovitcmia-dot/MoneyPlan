package com.cashflow.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.cashflow.app.ui.components.LabeledTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.R
import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.debug.DebugSection
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.WalletMode
import com.cashflow.app.ui.components.SectionCard
import com.cashflow.app.update.UpdateManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val DateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    repository: LedgerRepository,
    updateManager: UpdateManager,
    contentPadding: PaddingValues,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Bumped by the debug actions so the text fields re-read the replaced data.
    var generation by remember { mutableIntStateOf(0) }
    val current = settings

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        if (current != null) {
            key(generation) {
                SectionCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(stringResource(R.string.auto_income_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(if (current.walletMode == WalletMode.DYNAMIC) R.string.auto_income_on else R.string.auto_income_off),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = current.walletMode == WalletMode.DYNAMIC,
                            onCheckedChange = { dynamic ->
                                viewModel.update {
                                    it.copy(walletMode = if (dynamic) WalletMode.DYNAMIC else WalletMode.STATIC)
                                }
                            },
                        )
                    }
                }
                SectionCard {
                    AmountField(
                        label = stringResource(R.string.money_started_label),
                        initial = current.startingBalance,
                        allowNegative = true,
                        onValid = { v -> viewModel.update { it.copy(startingBalance = v) } },
                    )
                    DateField(
                        label = stringResource(R.string.started_on_label),
                        value = current.balanceStartDate,
                        onPick = { d -> viewModel.update { it.copy(balanceStartDate = d) } },
                    )
                    TimeField(
                        label = stringResource(R.string.remind_label),
                        value = current.reminderTime,
                        onPick = { t -> viewModel.update { it.copy(reminderTime = t) } },
                    )
                }
            }
            IncomeSourcesSection(viewModel)
            AppUpdatesSection(updateManager)
            DebugSection(repository) { viewModel.reload { generation++ } }
        }
    }
}

@Composable
private fun AmountField(label: String, initial: Long, allowNegative: Boolean, onValid: (Long) -> Unit) {
    var text by remember { mutableStateOf(Money.formatInput(initial)) }
    val parsed = Money.parse(text, maxDecimals = 2)
    val valid = parsed != null && (allowNegative || parsed >= 0)
    LabeledTextField(
        value = text,
        onValueChange = { new ->
            text = new
            val v = Money.parse(new, maxDecimals = 2)
            if (v != null && (allowNegative || v >= 0)) onValid(v)
        },
        label = { Text(label) },
        singleLine = true,
        isError = !valid,
        supportingText = { if (!valid) Text(stringResource(R.string.amount_error_decimals)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(label: String, value: LocalDate, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.field_value_format, label, value.format(DateFormat)))
    }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        // The picker works in UTC midnights; read the calendar date back in UTC.
                        onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    open = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeField(label: String, value: LocalTime, onPick: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.field_value_format, label, value.format(TimeFormat)))
    }
    if (open) {
        val state = rememberTimePickerState(initialHour = value.hour, initialMinute = value.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(LocalTime.of(state.hour, state.minute))
                    open = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimePicker(state = state) } },
        )
    }
}
