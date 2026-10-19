package com.cashflow.app.ui.items

import androidx.compose.foundation.layout.Arrangement
import com.cashflow.app.ui.theme.Spacing
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.R
import com.cashflow.app.engine.AppDates
import com.cashflow.app.engine.EntryFormState
import com.cashflow.app.engine.ExtendChoice
import com.cashflow.app.engine.FormProblem
import com.cashflow.app.engine.ItemEdits
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.PaidAverage
import com.cashflow.app.engine.RecurringRule
import com.cashflow.app.engine.SeriesContext
import com.cashflow.app.engine.SeriesForm
import com.cashflow.app.engine.SeriesText
import com.cashflow.app.engine.TxKind
import com.cashflow.app.engine.TxStatus
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.LabeledTextField
import com.cashflow.app.ui.components.selectedChipColors
import com.cashflow.app.ui.home.EntryComposer
import com.cashflow.app.ui.home.HomeViewModel
import com.cashflow.app.ui.home.WhenStep
import com.cashflow.app.ui.settings.DatePickerDialogFor
import com.cashflow.app.ui.theme.cashflow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The sheet of one repeating series: what it is, what was really paid, the "ask me the amount" switch and the four
 * actions. [extendOnTop] (opened from the last-payment reminder) puts the Extend choices first and offers [onAddNew].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesSheet(
    ruleId: Long,
    home: HomeViewModel,
    actions: ItemActions,
    today: LocalDate,
    onDismiss: () -> Unit,
    extendOnTop: Boolean = false,
    onAddNew: ((RecurringRule) -> Unit)? = null,
) {
    val rules by home.rules.collectAsStateWithLifecycle()
    val series by home.series.collectAsStateWithLifecycle()
    val rows by home.transactions.collectAsStateWithLifecycle()
    val rule = rules.firstOrNull { it.id == ruleId }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun close(after: () -> Unit) {
        after()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).padding(bottom = Spacing.l).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            if (rule == null) {
                Text(stringResource(R.string.series_sheet_none), style = MaterialTheme.typography.titleMedium)
                return@Column
            }
            val income = rule.kind == TxKind.INCOME
            Text(
                rule.description.ifBlank { stringResource(if (income) R.string.item_income else R.string.item_expense) },
                style = MaterialTheme.typography.headlineSmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(SeriesText.schedule(rule.pattern), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    Money.formatSigned(rule.kind, rule.amount),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (income) MaterialTheme.cashflow.positive else MaterialTheme.cashflow.negative,
                )
            }
            Text(SeriesText.endLine(rule), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (extendOnTop) {
                Text(stringResource(R.string.series_extend_title), style = MaterialTheme.typography.titleMedium)
                ExtendChoices(rule, today) { choice -> close { actions.extendSeries(rule, choice) } }
                if (onAddNew != null) {
                    AppButton(
                        stringResource(R.string.series_add_new),
                        onClick = { close { onAddNew(rule) } },
                        modifier = Modifier.fillMaxWidth(),
                        style = AppButtonStyle.Tonal,
                    )
                }
                HorizontalDivider()
            }

            // What was really paid, from the last accepted occurrences.
            val paid = remember(rule, rows) {
                val confirmed = rows.filter { it.recurringRuleId == rule.id && it.status == TxStatus.CONFIRMED }.sortedByDescending { it.date }.map { it.amount }
                PaidAverage.of(rule.amount, confirmed)
            }
            if (paid != null) {
                Text(paid.text, style = MaterialTheme.typography.bodyLarge)
                AppButton(
                    paid.updateLabel,
                    onClick = { close { actions.editSeries(rule, paid.average, rule.description) } },
                    modifier = Modifier.fillMaxWidth(),
                    style = AppButtonStyle.Outlined,
                )
            }

            val ask = rule.id in series.askAmountRuleIds
            val askTitle = stringResource(R.string.series_ask_amount)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = Spacing.m)) {
                    Text(askTitle, style = MaterialTheme.typography.titleMedium)
                }
                Switch(
                    modifier = Modifier.semantics { contentDescription = askTitle },
                    checked = ask,
                    onCheckedChange = { actions.setAskAmount(rule, it) },
                )
            }

            if (!extendOnTop) {
                Text(stringResource(R.string.series_extend_title), style = MaterialTheme.typography.titleMedium)
                ExtendChoices(rule, today) { choice -> close { actions.extendSeries(rule, choice) } }
            }
            HorizontalDivider()
            SeriesActions(rule, today, actions, series, onClose = { after -> close(after) }, showExtend = false)
        }
    }
}

/** The quick Extend choices as chips, plus "Pick a date". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExtendChoices(rule: RecurringRule, today: LocalDate, onPick: (ExtendChoice) -> Unit) {
    var picking by remember(rule.id) { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        for (choice in ExtendChoice.QUICK) {
            val label = when (choice) {
                is ExtendChoice.Months -> stringResource(if (choice.n == 1) R.string.extend_1_month else if (choice.n == 3) R.string.extend_3_months else R.string.extend_1_year)
                ExtendChoice.NoEnd -> stringResource(R.string.extend_no_end)
                is ExtendChoice.Until -> ""
            }
            FilterChip(
                selected = false,
                onClick = { onPick(choice) },
                label = { Text(label, style = MaterialTheme.typography.titleSmall) },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = selectedChipColors(),
            )
        }
        FilterChip(
            selected = false,
            onClick = { picking = true },
            label = { Text(stringResource(R.string.extend_pick), style = MaterialTheme.typography.titleSmall) },
            modifier = Modifier.heightIn(min = 48.dp),
            colors = selectedChipColors(),
        )
    }
    if (picking) {
        val after = maxOf(rule.endDate ?: today, today).plusDays(1)
        DatePickerDialogFor(
            initial = after,
            earliest = (rule.endDate ?: today).plusDays(1).let { maxOf(it, today) },
            onPick = { onPick(ExtendChoice.Until(it)) },
            onDismiss = { picking = false },
        )
    }
}

/** Edit, Extend, End series and Delete series with their dialogs: shared by the series sheet and the item details. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeriesActions(
    rule: RecurringRule,
    today: LocalDate,
    actions: ItemActions,
    series: SeriesContext,
    onClose: (() -> Unit) -> Unit,
    showExtend: Boolean = true,
    /** One outlined "Repeating payment" row that opens a menu (the item sheet), instead of a row of buttons. */
    asMenu: Boolean = false,
) {
    var menuOpen by remember(rule.id) { mutableStateOf(false) }
    var editing by remember(rule.id) { mutableStateOf(false) }
    var extending by remember(rule.id) { mutableStateOf(false) }
    var ending by remember(rule.id) { mutableStateOf(false) }
    var deleting by remember(rule.id) { mutableStateOf(false) }
    val label = rule.description.ifBlank { stringResource(if (rule.kind == TxKind.INCOME) R.string.item_income else R.string.item_expense) }

    if (asMenu) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
            AppButton(
                stringResource(R.string.series_menu_title),
                onClick = { menuOpen = true },
                modifier = Modifier.fillMaxWidth(),
                style = AppButtonStyle.Outlined,
            )
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.series_edit_action), style = MaterialTheme.typography.titleSmall) },
                    onClick = { menuOpen = false; editing = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.series_extend_action), style = MaterialTheme.typography.titleSmall) },
                    onClick = { menuOpen = false; extending = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.series_end), style = MaterialTheme.typography.titleSmall) },
                    onClick = { menuOpen = false; ending = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.series_delete), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; deleting = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            AppButton(stringResource(R.string.series_edit_action), onClick = { editing = true }, style = AppButtonStyle.Tonal)
            if (showExtend) AppButton(stringResource(R.string.series_extend_action), onClick = { extending = true }, style = AppButtonStyle.Tonal)
            AppButton(stringResource(R.string.series_end), onClick = { ending = true }, style = AppButtonStyle.Outlined)
            AppButton(
                stringResource(R.string.series_delete),
                onClick = { deleting = true },
                style = AppButtonStyle.Outlined,
                contentColor = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (editing) {
        SeriesEditSheet(
            rule = rule,
            today = today,
            askAmount = rule.id in series.askAmountRuleIds,
            onSave = { edited, ask -> editing = false; onClose { actions.saveSeries(edited, ask) } },
            onDismiss = { editing = false },
        )
    }
    if (extending) {
        AlertDialog(
            onDismissRequest = { extending = false },
            title = { Text(stringResource(R.string.series_extend_title)) },
            text = { ExtendChoices(rule, today) { choice -> extending = false; onClose { actions.extendSeries(rule, choice) } } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { extending = false }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (ending) {
        SeriesEndDialog(
            rule = rule,
            earliest = today,
            initial = (rule.endDate?.takeIf { !it.isBefore(today) }?.plusDays(1) ?: today.plusDays(1)),
            onEnd = { from -> ending = false; onClose { actions.endSeries(rule, from) } },
            onDismiss = { ending = false },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.series_delete_title, label)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.series_delete_body)) } },
            confirmButton = {
                TextButton(onClick = { deleting = false; onClose { actions.deleteSeries(rule) } }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** "End series": pick the first day the payment should no longer appear. */
@Composable
internal fun SeriesEndDialog(rule: RecurringRule, earliest: LocalDate, initial: LocalDate, onEnd: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    var from by remember(rule.id) { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.series_end_title, rule.description)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(stringResource(R.string.series_end_body), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.field_value_format, stringResource(R.string.series_end_from), AppDates.format(from)))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEnd(from) }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.series_end)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
    if (picking) {
        DatePickerDialogFor(initial = from, earliest = earliest, onPick = { from = it }, onDismiss = { picking = false })
    }
}

/**
 * Edit a series in one compact form: name, amount, the wizard's own When controls (schedule and end date) prefilled from
 * the series, and the "ask me the amount each time" switch. Saving applies from today on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeriesEditSheet(
    rule: RecurringRule,
    today: LocalDate,
    askAmount: Boolean,
    onSave: (RecurringRule, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var form by remember(rule.id) { mutableStateOf<EntryFormState>(SeriesForm.formFor(rule, today)) }
    var ask by remember(rule.id) { mutableStateOf(askAmount) }
    val edited = SeriesForm.ruleFrom(rule, form)
    val amountOk = Money.parse(form.amountText, maxDecimals = 2)?.takeIf { ItemEdits.canSave(it) } != null
    val problems = if (edited == null) com.cashflow.app.engine.EntryForm.let {
        (it.build(form, "x") as? com.cashflow.app.engine.FormResult.Invalid)?.problems.orEmpty().filter { p -> p != FormProblem.AMOUNT_MISSING }
    } else emptyList()
    val askTitle = stringResource(R.string.series_ask_amount)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).padding(bottom = Spacing.l).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(stringResource(R.string.series_edit_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.series_edit_future_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LabeledTextField(
                value = form.description,
                onValueChange = { form = form.copy(description = it) },
                label = { Text(stringResource(R.string.series_form_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LabeledTextField(
                value = form.amountText,
                onValueChange = { form = form.copy(amountText = it) },
                label = { Text(stringResource(R.string.amount_label)) },
                singleLine = true,
                isError = !amountOk,
                supportingText = { if (!amountOk) Text(stringResource(R.string.amount_error_decimals)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            WhenStep(form, rule.kind == TxKind.EXPENSE, today, problems, showTimings = false) { change -> form = change(form) }
            val haptics = com.cashflow.app.ui.rememberHaptics()
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = Spacing.m)) {
                    Text(askTitle, style = MaterialTheme.typography.titleMedium)
                }
                Switch(modifier = Modifier.semantics { contentDescription = askTitle }, checked = ask, onCheckedChange = { ask = it; haptics.tick() })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                AppButton(stringResource(R.string.action_cancel), onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 56.dp), style = AppButtonStyle.Outlined)
                AppButton(
                    stringResource(R.string.action_save),
                    onClick = { edited?.let { onSave(it, ask) } },
                    enabled = edited != null && amountOk,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                )
            }
        }
    }
}

/**
 * Opens the series sheet for a tapped notification (or bell row): with the Extend choices on top and "Add a new one",
 * which starts the add wizard filled in with the series' name, amount and type.
 */
@Composable
fun SeriesFromNotification(
    ruleId: Long,
    home: HomeViewModel,
    snackbar: androidx.compose.material3.SnackbarHostState,
    onDismiss: () -> Unit,
) {
    val actions = rememberItemActions(home, snackbar)
    val ui by home.state.collectAsStateWithLifecycle()
    var adding by remember(ruleId) { mutableStateOf<RecurringRule?>(null) }
    val state = ui ?: return
    val addRule = adding
    if (addRule == null) {
        SeriesSheet(
            ruleId = ruleId,
            home = home,
            actions = actions,
            today = state.today,
            onDismiss = onDismiss,
            extendOnTop = true,
            onAddNew = { adding = it },
        )
    } else {
        EntryComposer(
            kind = addRule.kind,
            ui = state,
            redDip = home::redDip,
            resultingBalance = home::resultingBalance,
            onSave = home::save,
            onSaved = { message -> actions.announce(message) },
            onDismiss = onDismiss,
            prefillAmount = Money.formatInput(addRule.amount),
            prefillName = addRule.description,
        )
    }
}
