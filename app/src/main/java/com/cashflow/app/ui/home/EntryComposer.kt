package com.cashflow.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cashflow.app.R
import com.cashflow.app.engine.AmountInput
import com.cashflow.app.engine.DipDay
import com.cashflow.app.engine.EntryForm
import com.cashflow.app.engine.EntryFormState
import com.cashflow.app.engine.EntryWizard
import com.cashflow.app.engine.FormProblem
import com.cashflow.app.engine.FormResult
import com.cashflow.app.engine.LiveMonth
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.NewEntry
import com.cashflow.app.engine.PatternChoice
import com.cashflow.app.engine.RedDip
import com.cashflow.app.engine.Timing
import com.cashflow.app.engine.TxKind
import com.cashflow.app.engine.WizardState
import com.cashflow.app.engine.WizardStep
import com.cashflow.app.ui.calendar.LiveCalendarPicker
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.LabeledTextField
import com.cashflow.app.ui.components.fullBleed
import com.cashflow.app.ui.rememberHaptics
import com.cashflow.app.ui.settings.DateField
import com.cashflow.app.ui.theme.cashflow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val ReviewDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
private val MediumDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@Composable
private fun PatternChoice.label() = stringResource(
    when (this) {
        PatternChoice.DAILY -> R.string.choice_daily
        PatternChoice.WEEKLY -> R.string.choice_weekly
        PatternChoice.EVERY_2_WEEKS -> R.string.choice_every_2_weeks
        PatternChoice.MONTH_DAY, PatternChoice.MONTH_FIRST, PatternChoice.MONTH_LAST -> R.string.choice_monthly
        PatternChoice.YEARLY -> R.string.choice_yearly
        PatternChoice.EVERY_N -> R.string.choice_every_n
    },
)

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
    },
)

/**
 * The Add expense / Add income wizard: one decision per screen (amount, name, when, then the date or the schedule,
 * then a review), with Back and a progress bar. Later and Repeating choose their days on a live calendar that shows
 * the balance with the new item applied. Only when the user confirms is the item checked against the next 90 days;
 * if it would take money below zero a warning sheet comes first.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryComposer(
    kind: TxKind,
    ui: HomeUiState,
    redDip: suspend (NewEntry) -> RedDip?,
    liveMonth: suspend (EntryFormState, YearMonth, LocalDate?, DayOfWeek) -> LiveMonth?,
    onSave: suspend (NewEntry) -> Unit,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Opened from a calendar day: that day is prefilled (see [EntryFormState.forDay]). */
    day: LocalDate? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val colors = MaterialTheme.cashflow
    val isExpense = kind == TxKind.EXPENSE
    val accentFill = if (isExpense) colors.expenseFill else colors.incomeFill

    var wizard by remember { mutableStateOf(EntryWizard.start(kind, ui.today, day)) }
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

    /** "Add": only now is the item checked against the next 90 days. */
    fun confirm() {
        val entry = (EntryForm.build(form, defaultText, ui.balanceStartDate) as? FormResult.Valid)?.entry
        if (entry == null) {
            haptics.reject()
            return
        }
        scope.launch {
            saving = true
            val check = redDip(entry)
            saving = false
            if (check != null) dip = check else commit(entry)
        }
    }

    fun goBack() {
        val back = EntryWizard.back(wizard)
        if (back == null) dismiss() else wizard = back
        haptics.tick()
    }

    fun goNext() {
        val next = EntryWizard.next(wizard)
        if (next.step == wizard.step && !EntryWizard.canAdvance(wizard)) haptics.reject() else haptics.tick()
        wizard = next
    }

    BackHandler(enabled = dip == null) { goBack() }

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

            val (position, total) = EntryWizard.progress(wizard)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(
                    progress = { position.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = accentFill,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    stringResource(R.string.wizard_step_of, position, total),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val problems = if (wizard.attempted) EntryWizard.problems(wizard) else emptyList()

            when (wizard.step) {
                WizardStep.AMOUNT -> AmountStep(form, problems) { wizard = EntryWizard.update(wizard, it) }
                WizardStep.NAME -> NameStep(form, isExpense, names, problems) { wizard = EntryWizard.update(wizard, it) }
                WizardStep.WHEN -> WhenStep(form, isExpense) { wizard = EntryWizard.update(wizard, it) }
                WizardStep.LATER_DATE -> {
                    StepTitle(stringResource(R.string.wizard_later_title))
                    LiveCalendarPicker(
                        selected = form.laterDate,
                        onPick = { picked -> wizard = EntryWizard.update(wizard) { it.copy(laterDate = picked) }; haptics.tick() },
                        projectionKey = form,
                        project = { month, selected, firstDay -> liveMonth(form, month, selected, firstDay) },
                    )
                    if (!form.laterDate.isAfter(ui.today)) {
                        Text(stringResource(R.string.later_past_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ProblemList(problems)
                }
                WizardStep.FREQUENCY -> {
                    StepTitle(stringResource(R.string.wizard_freq_title))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in EntryForm.choicesFor(kind)) {
                            FilterChip(
                                selected = form.choice == c,
                                onClick = { wizard = EntryWizard.update(wizard) { f -> EntryWizard.withFrequency(f, c) }; haptics.tick() },
                                label = { Text(c.label(), style = MaterialTheme.typography.titleSmall) },
                                modifier = Modifier.heightIn(min = 56.dp),
                            )
                        }
                    }
                    OccurrencePreview(form)
                    ProblemList(problems)
                }
                WizardStep.START_DATE -> {
                    StepTitle(stringResource(R.string.wizard_start_title))
                    LiveCalendarPicker(
                        selected = form.startDate,
                        onPick = { picked -> wizard = EntryWizard.update(wizard) { f -> EntryWizard.withStartDate(f, picked) }; haptics.tick() },
                        projectionKey = form,
                        project = { month, selected, firstDay -> liveMonth(form, month, selected, firstDay) },
                    )
                    OccurrencePreview(form)
                    ProblemList(problems)
                }
                WizardStep.END_DATE -> {
                    StepTitle(stringResource(R.string.wizard_end_title))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.end_date_switch), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = form.hasEnd, onCheckedChange = { on -> wizard = EntryWizard.update(wizard) { it.copy(hasEnd = on) } })
                    }
                    if (form.hasEnd) {
                        DateField(stringResource(R.string.ends_label), form.endDate) { picked -> wizard = EntryWizard.update(wizard) { it.copy(endDate = picked) } }
                    } else {
                        Text(stringResource(R.string.wizard_end_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OccurrencePreview(form)
                    ProblemList(problems)
                }
                WizardStep.REVIEW -> ReviewStep(form, defaultText, ui.today)
            }

            // Back and Next / Add: white bold text on the kind color.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    onClick = { if (last) confirm() else goNext() },
                    enabled = !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentFill, contentColor = colors.buttonLabel),
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
    }

    dip?.let { warning ->
        RedDipSheet(
            dip = warning,
            accentFill = accentFill,
            onAddAnyway = {
                dip = null
                (EntryForm.build(form, defaultText, ui.balanceStartDate) as? FormResult.Valid)?.entry?.let(::commit)
            },
            onChangeDate = {
                dip = null
                wizard = EntryWizard.goTo(wizard, WizardStep.WHEN)
                haptics.reject()
            },
            onCancel = {
                dip = null
                haptics.reject()
                dismiss()
            },
            onDismissRequest = { dip = null },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameStep(
    form: EntryFormState,
    isExpense: Boolean,
    suggestions: List<String>,
    problems: List<FormProblem>,
    change: ((EntryFormState) -> EntryFormState) -> Unit,
) {
    StepTitle(stringResource(R.string.wizard_name_title))
    LabeledTextField(
        value = form.description,
        onValueChange = { text -> change { it.copy(description = text) } },
        label = { Text(stringResource(if (isExpense) R.string.name_label_required else R.string.name_label_optional)) },
        singleLine = true,
        isError = FormProblem.NAME_MISSING in problems,
        modifier = Modifier.fillMaxWidth(),
    )
    ProblemList(problems)
    if (suggestions.isNotEmpty()) {
        Text(stringResource(R.string.wizard_recent_names), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (name in suggestions) {
                FilterChip(
                    selected = form.description.trim().equals(name, ignoreCase = true),
                    onClick = { change { it.copy(description = name) } },
                    label = { Text(name, style = MaterialTheme.typography.titleSmall) },
                    modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhenStep(form: EntryFormState, isExpense: Boolean, change: ((EntryFormState) -> EntryFormState) -> Unit) {
    val haptics = rememberHaptics()
    StepTitle(stringResource(R.string.wizard_when_title))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        form.timings.forEach { t ->
            FilterChip(
                selected = form.timing == t,
                onClick = { change { it.copy(timing = t) }; haptics.tick() },
                label = { Text(t.label(form.nowDate != null), style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.heightIn(min = 56.dp),
            )
        }
    }
    Text(
        when (form.timing) {
            Timing.NOW -> form.nowDate?.let { date ->
                stringResource(if (isExpense) R.string.now_hint_past_expense else R.string.now_hint_past_income, date.format(MediumDate))
            } ?: stringResource(if (isExpense) R.string.now_hint_expense else R.string.now_hint_income)
            Timing.LATER -> stringResource(R.string.when_later_hint)
            Timing.REPEATS -> stringResource(R.string.when_repeats_hint)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OccurrencePreview(form: EntryFormState) {
    val dates = remember(form) { EntryWizard.occurrencePreview(form) }
    if (dates.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.wizard_next_dates), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (date in dates) Text(date.format(ReviewDate), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ReviewStep(form: EntryFormState, defaultText: String, today: LocalDate) {
    val colors = MaterialTheme.cashflow
    val income = form.kind == TxKind.INCOME
    val amount = AmountInput.toMillimes(form.amountText)
    StepTitle(stringResource(R.string.wizard_review_title))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                Money.formatSigned(form.kind, amount),
                style = MaterialTheme.typography.displaySmall,
                color = if (income) colors.positive else colors.negative,
            )
            Text(form.description.trim().ifEmpty { defaultText }, style = MaterialTheme.typography.titleLarge)
            Text(
                when (form.timing) {
                    Timing.NOW -> form.nowDate?.format(ReviewDate) ?: stringResource(R.string.review_when_now)
                    Timing.LATER -> form.laterDate.format(ReviewDate)
                    Timing.REPEATS -> stringResource(R.string.review_when_repeats, form.choice.label(), form.startDate.format(MediumDate))
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (form.timing == Timing.REPEATS) {
                Text(
                    if (form.hasEnd) stringResource(R.string.review_until, form.endDate.format(MediumDate)) else stringResource(R.string.review_no_end),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OccurrencePreview(form)
            }
        }
    }
}

/** The warning before an item that would take money below zero is saved. Shown only when adding. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RedDipSheet(
    dip: RedDip,
    accentFill: androidx.compose.ui.graphics.Color,
    onAddAnyway: () -> Unit,
    onChangeDate: () -> Unit,
    onCancel: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val colors = MaterialTheme.cashflow
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.dip_title), style = MaterialTheme.typography.headlineSmall, color = colors.negative, modifier = Modifier.semantics { heading() })
            Text(
                stringResource(R.string.dip_lowest, Money.formatDisplay(dip.lowest, withCurrency = false), dip.lowestDate.format(MediumDate)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.dip_shortfall, Money.formatDisplay(dip.shortfall, withCurrency = false)),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.negative,
            )
            Text(stringResource(R.string.dip_days_header), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // A strip of the first days that turn red, each with its balance.
            val shown = dip.days.take(MAX_STRIP_DAYS)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (day in shown) DipChip(day)
            }
            if (dip.days.size > shown.size) {
                Text(
                    stringResource(R.string.dip_more, dip.days.size - shown.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppButton(
                stringResource(R.string.dip_add_anyway),
                onClick = onAddAnyway,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentFill, contentColor = colors.buttonLabel),
                textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            AppButton(stringResource(R.string.dip_change_date), onClick = onChangeDate, modifier = Modifier.fillMaxWidth(), style = AppButtonStyle.Outlined)
            AppButton(stringResource(R.string.action_cancel), onClick = onCancel, modifier = Modifier.fillMaxWidth(), style = AppButtonStyle.Tonal)
        }
    }
}

private const val MAX_STRIP_DAYS = 14

@Composable
private fun DipChip(day: DipDay) {
    val colors = MaterialTheme.cashflow
    Column(
        Modifier.clip(MaterialTheme.shapes.small).background(colors.dayNegativeFill).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            day.date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = com.cashflow.app.ui.theme.Palette.DAY_NUMBER_ALPHA),
        )
        Text(
            com.cashflow.app.engine.CompactAmount.balance(day.closing),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.negative,
        )
    }
}
