package com.cashflow.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
import com.cashflow.app.engine.DiscardGuard
import androidx.compose.material3.SuggestionChip
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cashflow.app.R
import com.cashflow.app.ui.notifications.LocalNotificationPermission
import com.cashflow.app.ui.components.selectedChipColors
import com.cashflow.app.engine.AmountInput
import com.cashflow.app.engine.AppDates
import com.cashflow.app.engine.CompactAmount
import com.cashflow.app.engine.DiscardState
import com.cashflow.app.engine.CloseDecision
import com.cashflow.app.engine.EntryForm
import com.cashflow.app.engine.EntryFormState
import com.cashflow.app.engine.EntryWizard
import com.cashflow.app.engine.FormProblem
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.NewEntry
import com.cashflow.app.engine.PatternChoice
import com.cashflow.app.engine.RedDip
import com.cashflow.app.engine.ResultingBalance
import com.cashflow.app.engine.Timing
import com.cashflow.app.engine.TxKind
import com.cashflow.app.engine.WizardState
import com.cashflow.app.engine.WizardStep
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.LabeledTextField
import com.cashflow.app.ui.components.fullBleed
import com.cashflow.app.ui.rememberHaptics
import com.cashflow.app.ui.settings.DatePickerDialogFor
import com.cashflow.app.ui.theme.cashflow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
private fun Timing.label(happenedOnADay: Boolean = false) = stringResource(
    when (this) {
        Timing.NOW -> if (happenedOnADay) R.string.timing_happened else R.string.timing_now
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
        FormProblem.NAME_MISSING -> R.string.problem_name
        FormProblem.DATE_IN_PAST -> R.string.problem_date_past
        FormProblem.WEEKDAYS_EMPTY -> R.string.problem_weekdays
    },
)

@Composable
private fun PatternChoice.label() = stringResource(
    when (this) {
        PatternChoice.DAILY -> R.string.choice_daily
        PatternChoice.WEEKLY -> R.string.choice_weekly
        PatternChoice.EVERY_2_WEEKS -> R.string.choice_every_2_weeks
        PatternChoice.MONTH_DAY -> R.string.choice_monthly_day
        PatternChoice.MONTH_FIRST -> R.string.choice_month_first_long
        PatternChoice.MONTH_LAST -> R.string.choice_month_last_long
        PatternChoice.YEARLY -> R.string.choice_yearly
        PatternChoice.EVERY_N -> R.string.choice_every_n
    },
)

/**
 * The Add expense / Add income wizard, full screen so a swipe or a tap outside can never close it by accident:
 * Amount, Name, When (Now, Later or Repeating, all on one screen), then Below zero (only when needed) and Review.
 * Back, the system Back button and a swipe all ask "Discard?" instead of losing what was entered.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryComposer(
    kind: TxKind,
    ui: HomeUiState,
    redDip: suspend (NewEntry) -> RedDip?,
    resultingBalance: suspend (NewEntry) -> ResultingBalance?,
    onSave: suspend (NewEntry) -> Unit,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Opened from a calendar day: that day is prefilled (see [EntryFormState.forDay]). */
    day: LocalDate? = null,
) {
    val scope = rememberCoroutineScope()
    val permission = LocalNotificationPermission.current
    val haptics = rememberHaptics()
    val colors = MaterialTheme.cashflow
    val isExpense = kind == TxKind.EXPENSE
    val accentFill = if (isExpense) colors.expenseFill else colors.incomeFill

    val initial = remember { EntryWizard.start(kind, ui.today, day) }
    var wizard by remember { mutableStateOf(initial) }
    var discard by remember { mutableStateOf(DiscardState()) }
    var saving by remember { mutableStateOf(false) }
    var dip by remember { mutableStateOf<RedDip?>(null) }
    val form = wizard.form
    val names = if (isExpense) ui.expenseNames else ui.incomeNames

    val defaultText = stringResource(if (isExpense) R.string.item_expense else R.string.item_income)
    val savedMessage = stringResource(
        when {
            form.timing == Timing.REPEATS && isExpense -> R.string.saved_repeating_expense
            form.timing == Timing.REPEATS -> R.string.saved_repeating_income
            isExpense -> R.string.saved_expense
            else -> R.string.saved_income
        },
    )

    fun entry(): NewEntry? = EntryWizard.entryOf(form, defaultText, ui.balanceStartDate)

    /** Swipe, tap outside and system Back all land here: ask first when something would be lost. */
    fun requestClose() {
        val (state, decision) = discard.onCloseRequested(wizard, initial)
        discard = state
        if (decision == CloseDecision.CLOSE) onDismiss()
    }

    fun goBack() {
        val back = EntryWizard.back(wizard)
        if (back == null) requestClose() else wizard = back
        haptics.tick()
    }

    fun goNext() {
        if (wizard.step == WizardStep.WHEN) {
            // Leaving When: only now is the item checked against the next 90 days.
            val built = entry()
            if (!EntryWizard.canAdvance(wizard) || built == null) {
                wizard = wizard.copy(attempted = true)
                haptics.reject()
                return
            }
            scope.launch {
                saving = true
                val check = redDip(built)
                saving = false
                dip = check
                wizard = EntryWizard.afterWhen(wizard, hasDip = check != null)
                haptics.tick()
            }
            return
        }
        val next = EntryWizard.next(wizard)
        if (next.step == wizard.step) haptics.reject() else haptics.tick()
        wizard = next
    }

    fun add() {
        val built = entry() ?: return
        scope.launch {
            saving = true
            onSave(built)
            permission?.onPaymentAdded() // the first payment is the moment to ask for the notification permission
            haptics.confirm()
            onSaved(savedMessage)
            onDismiss()
        }
    }

    // Swiping the drawer down, tapping outside and system Back never lose progress: while anything is entered, hiding
    // is refused and the "Discard?" question is shown instead; an untouched wizard just closes.
    val latest by rememberUpdatedState(wizard)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            when {
                target != SheetValue.Hidden -> true
                !DiscardGuard.hasProgress(latest, initial) -> true
                else -> {
                    discard = DiscardState(asking = true)
                    false
                }
            }
        },
    )
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp

    ModalBottomSheet(
        onDismissRequest = ::requestClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        BackHandler(enabled = !discard.asking) { requestClose() }
        Column(Modifier.fillMaxWidth().heightIn(max = maxSheetHeight)) {
        // Header: a band in the kind color with white bold text, like the big button on Home.
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).clip(MaterialTheme.shapes.large).background(accentFill).padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(if (isExpense) R.string.button_expense else R.string.button_income).uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.buttonLabel,
            )
        }
        val (position, total) = EntryWizard.progress(wizard)
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(
                progress = { position.toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
                color = accentFill,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                drawStopIndicator = {},
            )
            Text(
                stringResource(R.string.wizard_step_of, position, total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val problems = if (wizard.attempted) EntryWizard.problems(wizard) else emptyList()
        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (wizard.step) {
                WizardStep.AMOUNT -> AmountStep(form, problems) { wizard = EntryWizard.update(wizard, it) }
                WizardStep.NAME -> NameStep(form, isExpense, names, problems, onNext = ::goNext) { wizard = EntryWizard.update(wizard, it) }
                WizardStep.WHEN -> WhenStep(form, isExpense, ui.today, problems) { wizard = EntryWizard.update(wizard, it) }
                WizardStep.BELOW_ZERO -> BelowZeroStep(dip, accentFill, onAddAnyway = { wizard = EntryWizard.next(wizard); haptics.tick() }, onChange = { wizard = EntryWizard.change(wizard); haptics.reject() }, onCancel = { onDismiss() })
                WizardStep.REVIEW -> ReviewStep(form, defaultText, accentFill, ui, entry(), resultingBalance)
            }
        }

        if (wizard.step != WizardStep.BELOW_ZERO) {
            // Back and Next / Add: white bold text on the kind color.
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton(
                    stringResource(if (EntryWizard.back(wizard) == null) R.string.action_cancel else R.string.wizard_back),
                    onClick = ::goBack,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                    style = AppButtonStyle.Outlined,
                )
                val last = wizard.step == WizardStep.REVIEW
                AppButton(
                    text = stringResource(
                        when {
                            saving -> R.string.saving
                            last && isExpense -> R.string.wizard_add_expense
                            last -> R.string.wizard_add_income
                            else -> R.string.wizard_next
                        },
                    ),
                    onClick = { if (last) add() else goNext() },
                    enabled = !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentFill, contentColor = colors.buttonLabel),
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
        }

            // Room for the system navigation bar, or for the keyboard when it is open: the buttons are always fully visible.
            Box(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.ime.union(WindowInsets.navigationBars)))
        }
    }

    if (discard.asking) {
        AlertDialog(
            onDismissRequest = { discard = discard.keepGoing() },
            title = { Text(stringResource(R.string.wizard_discard_title)) },
            text = { Text(stringResource(R.string.wizard_discard_body)) },
            confirmButton = {
                TextButton(onClick = { discard = discard.discard(); onDismiss() }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.wizard_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { discard = discard.keepGoing() }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.wizard_keep_going))
                }
            },
        )
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun ProblemList(problems: List<FormProblem>) {
    for (p in problems) Text(p.message(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.cashflow.negative)
}

@Composable
private fun AmountStep(form: EntryFormState, problems: List<FormProblem>, change: ((EntryFormState) -> EntryFormState) -> Unit) {
    val colors = MaterialTheme.cashflow
    val haptics = rememberHaptics()
    StepTitle(stringResource(R.string.wizard_amount_title))
    val missing = FormProblem.AMOUNT_MISSING in problems
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
        Text(
            AmountInput.display(form.amountText),
            style = MaterialTheme.typography.displayLarge,
            color = when {
                missing -> colors.negative
                form.amountText.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
        Text(" " + Money.CURRENCY, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
    }
    ProblemList(problems)
    Keypad(
        modifier = Modifier.fullBleed(24.dp),
        onKey = { key -> change { it.copy(amountText = AmountInput.press(it.amountText, key)) }; haptics.tick() },
        onBackspace = { change { it.copy(amountText = AmountInput.backspace(it.amountText)) }; haptics.tick() },
        onClear = { change { it.copy(amountText = "") }; haptics.confirm() },
    )
}

/**
 * The name: the field is focused with the keyboard open as soon as this screen shows; Done / Enter goes to the next
 * step. The three most used names are small chips above the field; a tap fills the name and closes the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameStep(
    form: EntryFormState,
    isExpense: Boolean,
    frequent: List<String>,
    problems: List<FormProblem>,
    onNext: () -> Unit,
    change: ((EntryFormState) -> EntryFormState) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    StepTitle(stringResource(R.string.wizard_name_title))
    if (frequent.isNotEmpty()) {
        // Small chips in one row (it scrolls sideways if they ever do not fit), never stacked bricks.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (name in frequent) {
                SuggestionChip(
                    onClick = {
                        change { it.copy(description = name) }
                        keyboard?.hide()
                        focusManager.clearFocus()
                    },
                    label = { Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                )
            }
        }
    }
    LabeledTextField(
        value = form.description,
        onValueChange = { text -> change { it.copy(description = text) } },
        label = { Text(stringResource(if (isExpense) R.string.name_label_required else R.string.name_label_optional)) },
        singleLine = true,
        isError = FormProblem.NAME_MISSING in problems,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onNext() }),
        modifier = Modifier.fillMaxWidth(),
        fieldModifier = Modifier.focusRequester(focus),
    )
    ProblemList(problems)
}

/** Now / Later / Repeating on one screen; Later and Repeating open their fields right below. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun WhenStep(
    form: EntryFormState,
    isExpense: Boolean,
    today: LocalDate,
    problems: List<FormProblem>,
    change: ((EntryFormState) -> EntryFormState) -> Unit,
) {
    val haptics = rememberHaptics()
    StepTitle(stringResource(R.string.wizard_when_title))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        form.timings.forEach { t ->
            FilterChip(
                selected = form.timing == t,
                onClick = { change { it.copy(timing = t) }; haptics.tick() },
                label = { Text(t.label(form.nowDate != null), style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.heightIn(min = 56.dp),
                colors = selectedChipColors(),
            )
        }
    }
    when (form.timing) {
        Timing.NOW -> Text(
            form.nowDate?.let { date ->
                stringResource(if (isExpense) R.string.now_hint_past_expense else R.string.now_hint_past_income, AppDates.format(date))
            } ?: stringResource(if (isExpense) R.string.now_hint_expense else R.string.now_hint_income),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Timing.LATER -> {
            CompactDateField(stringResource(R.string.wizard_date_label), form.laterDate, earliest = today) { picked -> change { it.copy(laterDate = picked) } }
            ProblemList(problems)
        }
        Timing.REPEATS -> {
            var open by remember { mutableStateOf(false) }
            val choices = remember { EntryForm.choicesFor(form.kind) }
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                OutlinedTextField(
                    value = form.choice.label(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.wizard_repeat_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (c in choices) {
                        DropdownMenuItem(
                            text = { Text(c.label()) },
                            onClick = {
                                change { it.copy(choice = c) }
                                open = false
                                haptics.tick()
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
            when (form.choice) {
                PatternChoice.WEEKLY -> {
                    Text(stringResource(R.string.wizard_weekdays_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WeekdayGrid(form.weekdays) { dow ->
                        change { f -> f.copy(weekdays = if (dow in f.weekdays) f.weekdays - dow else f.weekdays + dow) }
                        haptics.tick()
                    }
                }
                PatternChoice.MONTH_DAY -> LabeledTextField(
                    value = form.dayOfMonth,
                    onValueChange = { text -> change { it.copy(dayOfMonth = text.filter(Char::isDigit).take(2)) } },
                    label = { Text(stringResource(R.string.day_of_month_label)) },
                    singleLine = true,
                    isError = FormProblem.DAY_OF_MONTH_INVALID in problems,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> Unit
            }
            // Every year has one date field (the first occurrence); the other schedules have a start date.
            if (form.choice == PatternChoice.YEARLY) {
                CompactDateField(stringResource(R.string.wizard_year_date_label), form.startDate, earliest = today) { picked -> change { EntryWizard.withStartDate(it, picked) } }
            } else {
                CompactDateField(stringResource(R.string.wizard_start_label), form.startDate, earliest = today) { picked -> change { EntryWizard.withStartDate(it, picked) } }
            }
            OptionalDateField(
                label = stringResource(R.string.wizard_end_label),
                date = form.endDate.takeIf { form.hasEnd },
                earliest = form.startDate,
                onPick = { picked -> change { it.copy(hasEnd = true, endDate = picked) } },
                onClear = { change { it.copy(hasEnd = false) } },
            )
            ProblemList(problems)
            OccurrencePreview(form)
        }
    }
}

/** A date shown like "05 OCT 2026"; tapping it opens a compact date picker (which also accepts typing). */
@Composable
private fun CompactDateField(label: String, date: LocalDate, earliest: LocalDate?, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(AppDates.format(date), style = MaterialTheme.typography.titleMedium)
        }
    }
    if (open) DatePickerDialogFor(initial = date, earliest = earliest, onPick = onPick, onDismiss = { open = false })
}

/** The optional end date: "Never" until one is picked, with a way back to "Never". */
@Composable
private fun OptionalDateField(label: String, date: LocalDate?, earliest: LocalDate, onPick: (LocalDate) -> Unit, onClear: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Text(date?.let(AppDates::format) ?: stringResource(R.string.wizard_never), style = MaterialTheme.typography.titleMedium)
            }
            if (date != null) {
                TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.wizard_never)) }
            }
        }
    }
    if (open) DatePickerDialogFor(initial = date ?: earliest, earliest = earliest, onPick = onPick, onDismiss = { open = false })
}

@Composable
private fun OccurrencePreview(form: EntryFormState) {
    val dates = remember(form) { EntryWizard.occurrencePreview(form) }
    if (dates.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.wizard_next_dates), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (date in dates) Text(AppDates.format(date), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Shown only when the item would take money below zero: every affected day with its balance, in red. */
@Composable
private fun BelowZeroStep(dip: RedDip?, accentFill: androidx.compose.ui.graphics.Color, onAddAnyway: () -> Unit, onChange: () -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.cashflow
    StepTitle(stringResource(R.string.dip_title))
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(dip?.days.orEmpty(), key = { it.date.toEpochDay() }) { day ->
            val date = AppDates.format(day.date)
            val balance = Money.formatDisplay(day.closing, withCurrency = false)
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(colors.dayNegativeFill).padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { contentDescription = resources.getString(R.string.dip_row_description, date, balance) },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(date, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(CompactAmount.balance(day.closing), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.negative)
            }
        }
    }
    AppButton(
        stringResource(R.string.dip_add_anyway),
        onClick = onAddAnyway,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accentFill, contentColor = colors.buttonLabel),
        textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
    )
    AppButton(stringResource(R.string.dip_change), onClick = onChange, modifier = Modifier.fillMaxWidth(), style = AppButtonStyle.Outlined)
    AppButton(stringResource(R.string.action_cancel), onClick = onCancel, modifier = Modifier.fillMaxWidth(), style = AppButtonStyle.Tonal)
}

/** A clear last look: a card in the income / expense color with the amount, the name, when, and the resulting balance. */
@Composable
private fun ReviewStep(
    form: EntryFormState,
    defaultText: String,
    accentFill: androidx.compose.ui.graphics.Color,
    ui: HomeUiState,
    entry: NewEntry?,
    resultingBalance: suspend (NewEntry) -> ResultingBalance?,
) {
    val colors = MaterialTheme.cashflow
    val amount = AmountInput.toMillimes(form.amountText)
    StepTitle(stringResource(R.string.wizard_review_title))
    val balance by produceState<ResultingBalance?>(null, entry) { value = entry?.let { resultingBalance(it) } }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = accentFill, contentColor = colors.buttonLabel)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(Money.formatSigned(form.kind, amount), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(form.description.trim().ifEmpty { defaultText }, style = MaterialTheme.typography.titleLarge)
            Text(timingLine(form), style = MaterialTheme.typography.bodyLarge)
            balance?.let { b ->
                val shown = Money.formatDisplay(b.balance, withCurrency = false)
                Text(
                    if (form.timing == Timing.NOW) stringResource(R.string.review_balance_now, shown)
                    else stringResource(R.string.review_balance_on, AppDates.format(b.date), shown),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
    if (form.timing == Timing.REPEATS) OccurrencePreview(form)
}

/** A short human line for when it happens, e.g. "Every week on Mon, Thu - from 05 OCT 2026 until 31 DEC 2026". */
@Composable
private fun timingLine(form: EntryFormState): String = when (form.timing) {
    Timing.NOW -> form.nowDate?.let { stringResource(R.string.review_on_day, AppDates.format(it)) } ?: stringResource(R.string.review_now)
    Timing.LATER -> stringResource(R.string.review_later, AppDates.format(form.laterDate))
    Timing.REPEATS -> {
        val every = when (form.choice) {
            PatternChoice.DAILY -> stringResource(R.string.review_every_day)
            PatternChoice.WEEKLY -> stringResource(R.string.review_every_week_on, AppDates.weekdaysText(form.weekdays))
            PatternChoice.EVERY_2_WEEKS -> stringResource(R.string.review_every_2_weeks)
            PatternChoice.MONTH_DAY -> stringResource(R.string.review_every_month_on, form.dayOfMonth)
            PatternChoice.MONTH_FIRST -> stringResource(R.string.review_first_of_month)
            PatternChoice.MONTH_LAST -> stringResource(R.string.review_last_of_month)
            PatternChoice.YEARLY -> stringResource(R.string.review_every_year_on, AppDates.formatShort(form.startDate, form.startDate))
            PatternChoice.EVERY_N -> stringResource(R.string.choice_every_n)
        }
        if (form.hasEnd) stringResource(R.string.review_from_until, every, AppDates.format(form.startDate), AppDates.format(form.endDate))
        else stringResource(R.string.review_from, every, AppDates.format(form.startDate))
    }
}

/** The seven days as round buttons in ONE row, equal widths, in the week's order for the region. */
@Composable
private fun WeekdayGrid(selected: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        for (dow in AppDates.weekOrder()) WeekdayCircle(dow, dow in selected, { onToggle(dow) }, Modifier.weight(1f))
    }
}

/**
 * The tap area is the full column width by 48 dp; the visible circle is 38 dp. Selected: accent fill with a dark bold
 * letter. Unselected: an outline. Spoken as the full day name.
 */
@Composable
private fun WeekdayCircle(dow: DayOfWeek, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val name = AppDates.weekdayFull(dow)
    Box(
        modifier.height(48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.Checkbox)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(if (selected) colors.primary else Color.Transparent)
                .border(1.dp, if (selected) colors.primary else colors.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                AppDates.weekdayLetter(dow),
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) colors.onPrimary else colors.onSurface,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}
