package com.cashflow.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.debug.DebugSection
import com.cashflow.app.engine.Money
import com.cashflow.app.update.UpdateManager
import com.cashflow.app.engine.WalletMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    repository: LedgerRepository,
    updateManager: UpdateManager,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Bumped by the debug actions so the text fields re-read the replaced data.
    var generation by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        val current = settings
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (current != null) {
                key(generation) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Dynamic wallet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (current.walletMode == WalletMode.DYNAMIC) {
                                    "Income sources add money on schedule."
                                } else {
                                    "Static: income sources are ignored; only logged transactions change the balance."
                                },
                                style = MaterialTheme.typography.bodySmall,
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
                    AmountField(
                        label = "Starting balance (TND)",
                        initial = current.startingBalance,
                        allowNegative = true,
                        onValid = { v -> viewModel.update { it.copy(startingBalance = v) } },
                    )
                    DateField(
                        label = "Balance start date",
                        value = current.balanceStartDate,
                        onPick = { d -> viewModel.update { it.copy(balanceStartDate = d) } },
                    )
                    TimeField(
                        label = "Reminder time",
                        value = current.reminderTime,
                        onPick = { t -> viewModel.update { it.copy(reminderTime = t) } },
                    )
                }
                IncomeSourcesSection(viewModel)
                AppUpdatesSection(updateManager)
                DebugSection(repository) { viewModel.reload { generation++ } }
            }
        }
    }
}

@Composable
private fun AmountField(label: String, initial: Long, allowNegative: Boolean, onValid: (Long) -> Unit) {
    var text by remember { mutableStateOf(Money.format(initial, withCurrency = false)) }
    val parsed = Money.parse(text)
    val valid = parsed != null && (allowNegative || parsed >= 0)
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new
            val v = Money.parse(new)
            if (v != null && (allowNegative || v >= 0)) onValid(v)
        },
        label = { Text(label) },
        singleLine = true,
        isError = !valid,
        supportingText = { if (!valid) Text("Enter an amount with up to 3 decimals") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: LocalDate, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("$label: $value")
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
                        onPick(java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeField(label: String, value: LocalTime, onPick: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("$label: ${value.format(TimeFormat)}")
    }
    if (open) {
        val state = rememberTimePickerState(initialHour = value.hour, initialMinute = value.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(LocalTime.of(state.hour, state.minute))
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}
