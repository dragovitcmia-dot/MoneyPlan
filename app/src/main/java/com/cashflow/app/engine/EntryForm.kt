package com.cashflow.app.engine

import java.time.DayOfWeek
import java.time.LocalDate

enum class Timing { NOW, LATER, REPEATS }

enum class PatternChoice { DAILY, WEEKLY, EVERY_N, MONTH_FIRST, MONTH_LAST, MONTH_DAY }

/** A specific field that needs fixing, so the UI can highlight it with a plain-language message. */
enum class FormProblem { AMOUNT_MISSING, EVERY_N_INVALID, DAY_OF_MONTH_INVALID, END_BEFORE_START }

/**
 * Everything the Add expense / Add income form holds. The defaults are a valid form once an amount
 * is typed: timing Now; Later = tomorrow; Repeats = weekly on today's weekday, starting today.
 */
data class EntryFormState(
    val kind: TxKind,
    val amountText: String = "",
    val description: String = "",
    val timing: Timing = Timing.NOW,
    val laterDate: LocalDate,
    val choice: PatternChoice = PatternChoice.WEEKLY,
    val weekday: DayOfWeek,
    val everyN: String = "7",
    val dayOfMonth: String,
    val startDate: LocalDate,
    val hasEnd: Boolean = false,
    val endDate: LocalDate,
) {
    companion object {
        fun initial(kind: TxKind, today: LocalDate) = EntryFormState(
            kind = kind,
            laterDate = today.plusDays(1),
            weekday = today.dayOfWeek,
            dayOfMonth = today.dayOfMonth.toString(),
            startDate = today,
            endDate = today.plusMonths(1),
        )
    }
}

sealed interface FormResult {
    data class Valid(val entry: NewEntry) : FormResult
    data class Invalid(val problems: List<FormProblem>) : FormResult
}

object EntryForm {
    /** Schedule choices offered for [kind]: "every day" is for income, "every N days" for expenses. */
    fun choicesFor(kind: TxKind): List<PatternChoice> =
        if (kind == TxKind.INCOME) {
            listOf(PatternChoice.WEEKLY, PatternChoice.DAILY, PatternChoice.MONTH_FIRST, PatternChoice.MONTH_LAST, PatternChoice.MONTH_DAY)
        } else {
            listOf(PatternChoice.WEEKLY, PatternChoice.EVERY_N, PatternChoice.MONTH_FIRST, PatternChoice.MONTH_LAST, PatternChoice.MONTH_DAY)
        }

    /**
     * Turns the form into an entry, or lists exactly which fields are wrong.
     * [description] is the text to use when the user typed none (already translated by the UI).
     * [balanceStartDate] keeps a daily income from starting on the balance's first day.
     */
    fun build(
        state: EntryFormState,
        defaultDescription: String,
        balanceStartDate: LocalDate? = null,
    ): FormResult {
        val problems = ArrayList<FormProblem>()
        val amount = AmountInput.toMillimes(state.amountText)
        if (amount <= 0) problems += FormProblem.AMOUNT_MISSING
        val description = state.description.trim().ifEmpty { defaultDescription }

        val entry: NewEntry? = when (state.timing) {
            Timing.NOW -> NewEntry.Now(state.kind, amount, description)
            Timing.LATER -> NewEntry.Planned(state.kind, amount, description, state.laterDate)
            Timing.REPEATS -> {
                val n = state.everyN.toIntOrNull()?.takeIf { it >= 1 }
                val day = state.dayOfMonth.toIntOrNull()?.takeIf { it in 1..31 }
                val pattern: RecurrencePattern? = when (state.choice) {
                    PatternChoice.DAILY -> RecurrencePattern.Daily
                    PatternChoice.WEEKLY -> RecurrencePattern.Weekly(state.weekday)
                    PatternChoice.EVERY_N -> n?.let { RecurrencePattern.EveryNDays(it, state.startDate) }
                    PatternChoice.MONTH_FIRST -> RecurrencePattern.MonthFirst
                    PatternChoice.MONTH_LAST -> RecurrencePattern.MonthLast
                    PatternChoice.MONTH_DAY -> day?.let { RecurrencePattern.DayOfMonth(it) }
                }
                if (state.choice == PatternChoice.EVERY_N && n == null) problems += FormProblem.EVERY_N_INVALID
                if (state.choice == PatternChoice.MONTH_DAY && day == null) problems += FormProblem.DAY_OF_MONTH_INVALID
                if (state.hasEnd && state.endDate.isBefore(state.startDate)) problems += FormProblem.END_BEFORE_START
                val start = if (state.kind == TxKind.INCOME && state.choice == PatternChoice.DAILY && balanceStartDate != null) {
                    maxOf(state.startDate, balanceStartDate.plusDays(1))
                } else {
                    state.startDate
                }
                pattern?.let {
                    NewEntry.Recurring(state.kind, amount, description, it, start, state.endDate.takeIf { state.hasEnd })
                }
            }
        }
        if (problems.isNotEmpty() || entry == null) return FormResult.Invalid(problems)
        // The planner's own rules (e.g. which schedules a kind may use) are the last line of defence.
        return if (EntryPlanner.validate(entry) == null) FormResult.Valid(entry) else FormResult.Invalid(problems)
    }
}
