package com.cashflow.app.engine

import java.time.LocalDate

/** One screen of the Add expense / Add income wizard. */
enum class WizardStep {
    /** The amount on the numpad. */
    AMOUNT,

    /** The name (required for expenses, optional for income). */
    NAME,

    /** Now, Later or Repeating, with the date and schedule fields on the same screen. */
    WHEN,

    /** Only when the item would take money below zero: lists the days that go negative. */
    BELOW_ZERO,

    /** A last look, then Add. */
    REVIEW,
}

/** The wizard: the form being filled, where the user is, and whether they already tried to go on with a problem. */
data class WizardState(
    val form: EntryFormState,
    val today: LocalDate,
    val step: WizardStep = WizardStep.AMOUNT,
    /** Set when "Next" was pressed while the step had problems, so the screen can show them. */
    val attempted: Boolean = false,
    /** The check after the When step found days below zero, so the Below-zero screen is part of the path. */
    val belowZero: Boolean = false,
)

/**
 * Pure state machine of the wizard: Amount, Name, When, [Below zero, only when needed], Review. Everything about
 * Later and Repeating (dates, weekdays, end date) lives on the When screen. Every transition is decided here.
 */
object EntryWizard {
    /** A fresh wizard; opened from a calendar day it starts with that day prefilled (see [EntryFormState.forDay]). */
    fun start(kind: TxKind, today: LocalDate, day: LocalDate? = null, amountText: String = "", description: String = ""): WizardState {
        val base = if (day != null) EntryFormState.forDay(kind, day, today) else EntryFormState.initial(kind, today)
        // "Add as a normal expense" from Plan a purchase arrives with the price and the name filled in.
        val form = base.copy(amountText = amountText, description = description)
        // A repeating payment never starts in the past.
        val fixed = if (form.startDate.isBefore(today)) withStartDate(form, today) else form
        return WizardState(fixed, today)
    }

    /** The screens this entry goes through, in order. */
    fun path(state: WizardState): List<WizardStep> = buildList {
        add(WizardStep.AMOUNT)
        add(WizardStep.NAME)
        add(WizardStep.WHEN)
        if (state.belowZero) add(WizardStep.BELOW_ZERO)
        add(WizardStep.REVIEW)
    }

    /** (1-based position, total) for the progress indicator. */
    fun progress(state: WizardState): Pair<Int, Int> {
        val path = path(state)
        return (path.indexOf(state.step).coerceAtLeast(0) + 1) to path.size
    }

    /** What is wrong on [step] (default: the current one). Empty means the user may go on. */
    fun problems(state: WizardState, step: WizardStep = state.step): List<FormProblem> {
        val f = state.form
        return when (step) {
            WizardStep.AMOUNT -> if (AmountInput.toMillimes(f.amountText) <= 0) listOf(FormProblem.AMOUNT_MISSING) else emptyList()
            WizardStep.NAME ->
                if (f.kind == TxKind.EXPENSE && f.description.isBlank()) listOf(FormProblem.NAME_MISSING) else emptyList()
            WizardStep.WHEN -> when (f.timing) {
                Timing.NOW -> emptyList()
                Timing.LATER -> if (f.laterDate.isBefore(state.today)) listOf(FormProblem.DATE_IN_PAST) else emptyList()
                Timing.REPEATS -> buildList {
                    if (f.choice == PatternChoice.WEEKLY && f.weekdays.isEmpty()) add(FormProblem.WEEKDAYS_EMPTY)
                    if (f.choice == PatternChoice.EVERY_N && f.everyN.toIntOrNull()?.let { it >= 1 } != true) add(FormProblem.EVERY_N_INVALID)
                    if (f.choice == PatternChoice.MONTH_DAY && f.dayOfMonth.toIntOrNull()?.let { it in 1..31 } != true) add(FormProblem.DAY_OF_MONTH_INVALID)
                    if (f.startDate.isBefore(state.today)) add(FormProblem.DATE_IN_PAST)
                    if (f.hasEnd && f.endDate.isBefore(f.startDate)) add(FormProblem.END_BEFORE_START)
                }
            }
            WizardStep.BELOW_ZERO -> emptyList()
            WizardStep.REVIEW -> {
                // Everything on the way must hold, and the form itself must build.
                val earlier = listOf(WizardStep.AMOUNT, WizardStep.NAME, WizardStep.WHEN).flatMap { problems(state, it) }
                val built = (EntryForm.build(f, "x") as? FormResult.Invalid)?.problems.orEmpty()
                (earlier + built).distinct()
            }
        }
    }

    fun canAdvance(state: WizardState): Boolean = problems(state).isEmpty()

    /**
     * Goes to the next screen, or stays (with [WizardState.attempted] set) when this one has problems. From When it
     * goes to Below zero only when [WizardState.belowZero] is set (see [afterWhen]), otherwise to the review.
     */
    fun next(state: WizardState): WizardState {
        if (!canAdvance(state)) return state.copy(attempted = true)
        val path = path(state)
        val target = path.getOrNull(path.indexOf(state.step) + 1) ?: return state
        return state.copy(step = target, attempted = false)
    }

    /**
     * Leaves the When screen once the below-zero check has run: to the Below-zero screen when [hasDip], straight to
     * the review when not. Stays on When (with the problems shown) when its fields are not valid.
     */
    fun afterWhen(state: WizardState, hasDip: Boolean): WizardState {
        if (state.step != WizardStep.WHEN) return state
        if (!canAdvance(state)) return state.copy(attempted = true)
        val flagged = state.copy(belowZero = hasDip, attempted = false)
        return flagged.copy(step = if (hasDip) WizardStep.BELOW_ZERO else WizardStep.REVIEW)
    }

    /**
     * The previous screen; null when already on the first one (the wizard asks before closing). Never changes what was
     * typed. Back from the Below-zero screen or the review returns to When and forgets the check, which runs again.
     */
    fun back(state: WizardState): WizardState? = when (state.step) {
        WizardStep.AMOUNT -> null
        WizardStep.NAME -> state.copy(step = WizardStep.AMOUNT, attempted = false)
        WizardStep.WHEN -> state.copy(step = WizardStep.NAME, attempted = false)
        WizardStep.BELOW_ZERO, WizardStep.REVIEW -> state.copy(step = WizardStep.WHEN, belowZero = false, attempted = false)
    }

    /** "Change" on the Below-zero screen: back to When. */
    fun change(state: WizardState): WizardState = state.copy(step = WizardStep.WHEN, belowZero = false, attempted = false)

    /** Edits the form; any earlier below-zero finding is no longer valid. */
    fun update(state: WizardState, change: (EntryFormState) -> EntryFormState): WizardState =
        state.copy(form = change(state.form), belowZero = false)

    /** The form with a new first day. */
    fun withStartDate(form: EntryFormState, date: LocalDate): EntryFormState = form.copy(startDate = date)

    /** The entry the form saves; null while it is not complete. */
    fun entryOf(form: EntryFormState, defaultDescription: String, balanceStartDate: LocalDate? = null): NewEntry? =
        (EntryForm.build(form, defaultDescription, balanceStartDate) as? FormResult.Valid)?.entry

    /** The next [count] dates a repeating form produces (honouring the end date); empty for Now and Later. */
    fun occurrencePreview(form: EntryFormState, count: Int = 3): List<LocalDate> {
        if (form.timing != Timing.REPEATS) return emptyList()
        val entry = (EntryForm.build(form, "x") as? FormResult.Valid)?.entry as? NewEntry.Recurring ?: return emptyList()
        val rule = EntryPlanner.ruleFor(entry) ?: return emptyList()
        // Look far enough ahead for a yearly payment to show three dates.
        return Recurrence.occurrences(rule, rule.startDate, rule.startDate.plusYears(count.toLong() + 1)).take(count)
    }
}

/** What to do when the user tries to leave the wizard (swipe down, tap outside, system Back). */
enum class CloseDecision { CLOSE, ASK }

/** The "Discard?" question: whether it is showing. */
data class DiscardState(val asking: Boolean = false) {
    /** The user tried to leave. Closes at once only when nothing has been entered yet; otherwise asks. */
    fun onCloseRequested(current: WizardState, initial: WizardState): Pair<DiscardState, CloseDecision> =
        if (DiscardGuard.hasProgress(current, initial)) DiscardState(asking = true) to CloseDecision.ASK else this to CloseDecision.CLOSE

    /** "Keep going": the question goes away and nothing else changes. */
    fun keepGoing(): DiscardState = DiscardState(asking = false)

    /** "Discard": the wizard closes. */
    fun discard(): DiscardState = DiscardState(asking = false)
}

object DiscardGuard {
    /** Anything typed or chosen, or a screen past the first one, would be lost by closing. */
    fun hasProgress(current: WizardState, initial: WizardState): Boolean =
        current.step != WizardStep.AMOUNT || current.form != initial.form
}
