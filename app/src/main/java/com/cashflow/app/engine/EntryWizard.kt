package com.cashflow.app.engine

import java.time.LocalDate

/** One screen of the Add expense / Add income wizard. */
enum class WizardStep {
    /** 1. The amount on the numpad. */
    AMOUNT,

    /** 2. The name (required for expenses, optional for income). */
    NAME,

    /** 3. Now, Later or Repeating. */
    WHEN,

    /** Later: the day, chosen on the live calendar. */
    LATER_DATE,

    /** Repeating: how often. */
    FREQUENCY,

    /** Repeating: the first day, chosen on the live calendar. */
    START_DATE,

    /** Repeating: an optional last day. */
    END_DATE,

    /** 4. Look it over and confirm. */
    REVIEW,
}

/** The wizard: the form being filled, where the user is, and whether they already tried to go on with a problem. */
data class WizardState(
    val form: EntryFormState,
    val today: LocalDate,
    val step: WizardStep = WizardStep.AMOUNT,
    /** Set when "Next" was pressed while the step had problems, so the screen can show them. */
    val attempted: Boolean = false,
)

/**
 * Pure state machine of the wizard. One decision per screen; the path depends on When: Now goes straight to the
 * review, Later adds the date, Repeating adds frequency, start and end. Every transition is decided here.
 */
object EntryWizard {
    /** A fresh wizard; opened from a calendar day it starts with that day prefilled (see [EntryFormState.forDay]). */
    fun start(kind: TxKind, today: LocalDate, day: LocalDate? = null): WizardState {
        val form = if (day != null) EntryFormState.forDay(kind, day, today) else EntryFormState.initial(kind, today)
        // A repeating payment never starts in the past.
        val fixed = if (form.startDate.isBefore(today)) withStartDate(form, today) else form
        return WizardState(fixed, today)
    }

    /** The screens this entry will go through, in order. */
    fun path(form: EntryFormState): List<WizardStep> = buildList {
        add(WizardStep.AMOUNT)
        add(WizardStep.NAME)
        add(WizardStep.WHEN)
        when (form.timing) {
            Timing.NOW -> Unit
            Timing.LATER -> add(WizardStep.LATER_DATE)
            Timing.REPEATS -> {
                add(WizardStep.FREQUENCY)
                add(WizardStep.START_DATE)
                add(WizardStep.END_DATE)
            }
        }
        add(WizardStep.REVIEW)
    }

    /** (1-based position, total) for the progress indicator. */
    fun progress(state: WizardState): Pair<Int, Int> {
        val path = path(state.form)
        return (path.indexOf(state.step).coerceAtLeast(0) + 1) to path.size
    }

    /** What is wrong on [step] (default: the current one). Empty means the user may go on. */
    fun problems(state: WizardState, step: WizardStep = state.step): List<FormProblem> {
        val f = state.form
        return when (step) {
            WizardStep.AMOUNT -> if (AmountInput.toMillimes(f.amountText) <= 0) listOf(FormProblem.AMOUNT_MISSING) else emptyList()
            WizardStep.NAME ->
                if (f.kind == TxKind.EXPENSE && f.description.isBlank()) listOf(FormProblem.NAME_MISSING) else emptyList()
            WizardStep.WHEN -> emptyList()
            WizardStep.LATER_DATE -> if (f.laterDate.isBefore(state.today)) listOf(FormProblem.DATE_IN_PAST) else emptyList()
            WizardStep.FREQUENCY -> buildList {
                if (f.choice == PatternChoice.EVERY_N && f.everyN.toIntOrNull()?.let { it >= 1 } != true) add(FormProblem.EVERY_N_INVALID)
                if (f.choice == PatternChoice.MONTH_DAY && f.dayOfMonth.toIntOrNull()?.let { it in 1..31 } != true) add(FormProblem.DAY_OF_MONTH_INVALID)
            }
            WizardStep.START_DATE -> if (f.startDate.isBefore(state.today)) listOf(FormProblem.DATE_IN_PAST) else emptyList()
            WizardStep.END_DATE -> if (f.hasEnd && f.endDate.isBefore(f.startDate)) listOf(FormProblem.END_BEFORE_START) else emptyList()
            WizardStep.REVIEW -> {
                // Everything on the path must hold, and the form itself must build.
                val earlier = path(f).filter { it != WizardStep.REVIEW }.flatMap { problems(state, it) }
                val built = (EntryForm.build(f, "x") as? FormResult.Invalid)?.problems.orEmpty()
                (earlier + built).distinct()
            }
        }
    }

    fun canAdvance(state: WizardState): Boolean = problems(state).isEmpty()

    /** Goes to the next screen, or stays (with [WizardState.attempted] set) when this one has problems. */
    fun next(state: WizardState): WizardState {
        if (!canAdvance(state)) return state.copy(attempted = true)
        val path = path(state.form)
        val i = path.indexOf(state.step)
        val target = path.getOrNull(i + 1) ?: return state
        return state.copy(step = target, attempted = false)
    }

    /** The previous screen; null when already on the first one (the wizard closes). Never changes what was typed. */
    fun back(state: WizardState): WizardState? {
        val path = path(state.form)
        val i = path.indexOf(state.step)
        if (i <= 0) return null
        return state.copy(step = path[i - 1], attempted = false)
    }

    /** Jumps to [step] if it is on the path (e.g. "Change date" in the red-dip warning returns to the When step). */
    fun goTo(state: WizardState, step: WizardStep): WizardState =
        if (step in path(state.form)) state.copy(step = step, attempted = false) else state

    fun update(state: WizardState, change: (EntryFormState) -> EntryFormState): WizardState = state.copy(form = change(state.form))

    /** The form with a new first day; weekday and day of month follow it (they define weekly and monthly payments). */
    fun withStartDate(form: EntryFormState, date: LocalDate): EntryFormState =
        form.copy(startDate = date, weekday = date.dayOfWeek, dayOfMonth = date.dayOfMonth.toString())

    /** Changes the frequency; the schedule details come from the start date. */
    fun withFrequency(form: EntryFormState, choice: PatternChoice): EntryFormState = withStartDate(form.copy(choice = choice), form.startDate)

    /**
     * The entry the form would save with [date] as its day: the Later date, or the first day of a repeating payment.
     * Used by the live calendar to show balances with the item applied. Null when the form cannot be built.
     */
    fun entryAt(form: EntryFormState, date: LocalDate, defaultDescription: String = "x", balanceStartDate: LocalDate? = null): NewEntry? {
        val changed = when (form.timing) {
            Timing.LATER -> form.copy(laterDate = date)
            Timing.REPEATS -> withStartDate(form, date)
            Timing.NOW -> form
        }
        return (EntryForm.build(changed, defaultDescription, balanceStartDate) as? FormResult.Valid)?.entry
    }

    /** The next [count] dates a repeating form produces (honouring the end date); empty for Now and Later. */
    fun occurrencePreview(form: EntryFormState, count: Int = 3): List<LocalDate> {
        if (form.timing != Timing.REPEATS) return emptyList()
        val entry = (EntryForm.build(form, "x") as? FormResult.Valid)?.entry as? NewEntry.Recurring ?: return emptyList()
        val rule = EntryPlanner.ruleFor(entry) ?: return emptyList()
        // Look far enough ahead for a yearly payment to show three dates.
        return Recurrence.occurrences(rule, rule.startDate, rule.startDate.plusYears(count.toLong() + 1)).take(count)
    }
}
