package com.cashflow.app.ui.settings

import androidx.compose.foundation.ScrollState
import com.cashflow.app.ui.theme.Spacing
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
import androidx.compose.material3.SelectableDates
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.R
import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.debug.DebugSection
import com.cashflow.app.engine.AppDates
import com.cashflow.app.engine.AppSettings
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.SettingsCard
import com.cashflow.app.engine.SettingsLayout
import com.cashflow.app.engine.SettingsPlan
import com.cashflow.app.ui.components.SectionCard
import com.cashflow.app.update.UpdateManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ENGLISH)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    repository: LedgerRepository,
    updateManager: UpdateManager,
    contentPadding: PaddingValues,
    scrollState: ScrollState,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Bumped by the debug actions so the text fields re-read the replaced data.
    var generation by remember { mutableIntStateOf(0) }
    val current = settings

    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(
            start = Spacing.l,
            end = Spacing.l,
            top = contentPadding.calculateTopPadding() + Spacing.l,
            bottom = contentPadding.calculateBottomPadding() + Spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        if (current != null) {
            // Which cards, in which order, and how they behave is decided by the pure SettingsPlan (and tested there).
            val layout = SettingsPlan.build()
            for (card in layout.cards) {
                when (card) {
                    SettingsCard.INCOME -> key(generation) { IncomeCard(viewModel, current) }
                    SettingsCard.NOTIFICATIONS -> NotificationsCard(viewModel, current)
                    SettingsCard.BACKUP -> BackupCard()
                    SettingsCard.STARTING_POINT -> key(generation) { StartingPointCard(viewModel, current, layout) }
                    SettingsCard.APP_UPDATES -> AppUpdatesSection(updateManager)
                    SettingsCard.DEVELOPER_TOOLS -> DebugSection(repository) { viewModel.reload { generation++ } }
                }
            }
        }
    }
}

/** "Money I started with" and "Starting date". Read-only (with a note) when [SettingsConfig.STARTING_POINT_EDITABLE] is false. */
@Composable
private fun StartingPointCard(viewModel: SettingsViewModel, current: AppSettings, layout: SettingsLayout) {
    SectionCard {
        Text(stringResource(R.string.starting_title), style = MaterialTheme.typography.titleLarge)
        if (layout.startingPointReadOnly) {
            ReadOnlyValue(stringResource(R.string.money_started_label), Money.formatDisplay(current.startingBalance))
            ReadOnlyValue(stringResource(R.string.started_on_label), AppDates.format(current.balanceStartDate))
            if (layout.startingPointNote) {
                Text(
                    stringResource(R.string.starting_locked_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
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
        }
    }
}

@Composable
private fun ReadOnlyValue(label: String, value: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
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

@Composable
internal fun DateField(label: String, value: LocalDate, display: String? = null, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.field_value_format, label, display ?: AppDates.format(value)))
    }
    if (open) {
        DatePickerDialogFor(initial = value, onPick = onPick, onDismiss = { open = false })
    }
}

/** The date picker in a dialog. Days before [earliest] cannot be chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickerDialogFor(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    earliest: LocalDate? = null,
    /** The OK button is enabled only for dates this accepts (e.g. not the item's own date when postponing). */
    isValid: (LocalDate) -> Boolean = { true },
) {
    val earliestMillis = earliest?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = earliestMillis == null || utcTimeMillis >= earliestMillis
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val picked = state.selectedDateMillis?.let { ms ->
                // The picker works in UTC midnights; read the calendar date back in UTC.
                Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
            }
            TextButton(
                onClick = {
                    picked?.let(onPick)
                    onDismiss()
                },
                enabled = picked != null && isValid(picked),
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) { DatePicker(state = state) }
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
