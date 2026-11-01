package com.cashflow.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.LaunchedEffect
import com.cashflow.app.CashflowApp
import com.cashflow.app.backup.BackupSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import androidx.compose.ui.platform.LocalContext
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
    /** The full-screen Backups screen is open (hoisted so the Lab can open it by itself). */
    backupsOpen: Boolean = false,
    onBackupsOpen: (Boolean) -> Unit = {},
) {
    if (backupsOpen) {
        BackHandler { onBackupsOpen(false) }
        BackupsScreen(contentPadding, onBack = { onBackupsOpen(false) })
        return
    }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Bumped by the debug actions so the rows re-read the replaced data.
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
            // Which groups, in which order, and how they behave is decided by the pure SettingsPlan (and tested there).
            val layout = SettingsPlan.build()
            for (card in layout.cards) {
                when (card) {
                    SettingsCard.INCOME -> key(generation) { IncomeCard(viewModel, current) }
                    SettingsCard.NOTIFICATIONS -> NotificationsCard(viewModel, current)
                    SettingsCard.BACKUP -> BackupsGroup { onBackupsOpen(true) }
                    SettingsCard.STARTING_POINT -> key(generation) { StartingPointCard(viewModel, current, layout) }
                    SettingsCard.APPEARANCE -> AppearanceCard()
                    SettingsCard.APP_UPDATES -> AppUpdatesSection(updateManager)
                    SettingsCard.DEVELOPER_TOOLS -> DebugSection(repository) { viewModel.reload { generation++ } }
                }
            }
        }
    }
}

/** One switch: frosted glass on the bottom bar, sheets and the notification center. Applies at once; kept in preferences, not the database. */
@Composable
private fun AppearanceCard() {
    val app = LocalContext.current.applicationContext as CashflowApp
    val on by app.uiPrefs.glassEffects.collectAsStateWithLifecycle()
    SettingsGroup(stringResource(R.string.appearance_title)) {
        SwitchRow(
            title = stringResource(R.string.glass_effects_title),
            subtitle = stringResource(R.string.glass_effects_helper),
            checked = on,
            onChange = { app.uiPrefs.setGlassEffects(it) },
        )
    }
}

/** ONE row: "Backups" with the last backup and how many are saved; it opens the full Backups screen. */
@Composable
private fun BackupsGroup(onOpen: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CashflowApp
    val snapshots by app.backupManager.snapshots.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { app.backupManager.refresh() } }
    SettingsGroup(stringResource(R.string.settings_data_title)) {
        ChevronRow(stringResource(R.string.settings_backups_title), BackupSummary.line(snapshots, zone), onOpen)
    }
}

/** "Money I started with" and "Starting date" as two rows (a value and a chevron each). Plain values (with a note) when [SettingsConfig.STARTING_POINT_EDITABLE] is false. */
@Composable
private fun StartingPointCard(viewModel: SettingsViewModel, current: AppSettings, layout: SettingsLayout) {
    SettingsGroup(stringResource(R.string.starting_title)) {
        if (layout.startingPointReadOnly) {
            SettingsRow(stringResource(R.string.money_started_label), Money.formatDisplay(current.startingBalance))
            RowDivider()
            SettingsRow(stringResource(R.string.started_on_label), AppDates.format(current.balanceStartDate))
            if (layout.startingPointNote) {
                RowDivider()
                SettingsRow(stringResource(R.string.starting_locked_note), null)
            }
        } else {
            AmountRow(
                label = stringResource(R.string.money_started_label),
                initial = current.startingBalance,
                allowNegative = true,
                onValid = { v -> viewModel.update { it.copy(startingBalance = v) } },
            )
            RowDivider()
            DateRow(
                label = stringResource(R.string.started_on_label),
                value = current.balanceStartDate,
                onPick = { d -> viewModel.update { it.copy(balanceStartDate = d) } },
            )
        }
    }
}

/** A value row that opens a small dialog with the amount field (same validation as before). */
@Composable
private fun AmountRow(label: String, initial: Long, allowNegative: Boolean, onValid: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(initial) }
    ChevronRow(label, Money.formatDisplay(value), onClick = { open = true })
    if (open) {
        var text by remember { mutableStateOf(Money.formatInput(value)) }
        val parsed = Money.parse(text, maxDecimals = 2)
        val valid = parsed != null && (allowNegative || parsed >= 0)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                LabeledTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    isError = !valid,
                    supportingText = { if (!valid) Text(stringResource(R.string.amount_error_decimals)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { parsed?.let { value = it; onValid(it) }; open = false },
                    enabled = valid,
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DateRow(label: String, value: LocalDate, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(value) }
    ChevronRow(label, AppDates.format(shown), onClick = { open = true })
    if (open) DatePickerDialogFor(initial = shown, onPick = { shown = it; onPick(it) }, onDismiss = { open = false })
}

@Composable
internal fun DateField(label: String, value: LocalDate, display: String? = null, earliest: LocalDate? = null, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.field_value_format, label, display ?: AppDates.format(value)))
    }
    if (open) {
        DatePickerDialogFor(initial = value, onPick = onPick, onDismiss = { open = false }, earliest = earliest)
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
    /** A line above the calendar ("Only this day moves"). */
    caption: String? = null,
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
    ) {
        Column {
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.m),
                )
            }
            DatePicker(state = state)
            com.cashflow.app.lab.LabHooks.PickerPanel() // Lab build only: the check panel, in a small window of its own at the top
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerDialogFor(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) } },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimePicker(state = state) } },
    )
}

/** "Remind me at" as a value row: the time and a chevron; it opens the time picker. */
@Composable
internal fun TimeRow(label: String, value: LocalTime, onPick: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ChevronRow(label, value.format(TimeFormat), onClick = { open = true })
    if (open) TimePickerDialogFor(value, onPick, onDismiss = { open = false })
}
