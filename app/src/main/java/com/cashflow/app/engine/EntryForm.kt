package com.cashflow.app.engine

import java.time.DayOfWeek
import java.time.LocalDate

enum class Timing { NOW, LATER, REPEATS }

enum class PatternChoice { DAILY, WEEKLY, EVERY_N, MONTH_FIRST, MONTH_LAST, MONTH_DAY, EVERY_2_WEEKS, YEARLY }

/** A specific field that needs fixing, so the UI can highlight it with a plain-language message. */
enum class FormProblem { AMOUNT_MISSING, EVERY_N_INVALID, DAY_OF_MONTH_INVALID, END_BEFORE_START, NAME_MISSING, DATE_IN_PAST, WEEKDAYS_EMPTY }

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
    /** The weekdays of a weekly payment (one or more). */
    val weekdays: Set<DayOfWeek>,
    val everyN: String = "7",
    val dayOfMonth: String,
    val startDate: LocalDate,
    val hasEnd: Boolean = false,
    val endDate: LocalDate,
    /** Date of a "Now" entry: null = today; a past calendar day for "already happened on that day". */
    val nowDate: LocalDate? = null,
    /** The timing choices on offer (a past day has no "Later", a future day has no "Now"). */
    val timings: List<Timing> = Timing.entries,
) {
    companion object {
        fun initial(kind: TxKind, today: LocalDate) = EntryFormState(
            kind = kind,
            laterDate = today.plusDays(1),
            weekdays = setOf(today.dayOfWeek),
            dayOfMonth = today.dayOfMonth.toString(),
            startDate = today,
            endDate = today.plusMonths(1),
        )

        /**
         * The form opened from a calendar day. Past day: already happened on that date (only "Repeats" is the
         * other choice). Today: like the Home buttons. Future day: Later on that date ("Now" is not offered).
         */
        fun forDay(kind: TxKind, day: LocalDate, today: LocalDate): EntryFormState {
            val base = initial(kind, today).copy(
                weekdays = setOf(day.dayOfWeek),
                dayOfMonth = day.dayOfMonth.toString(),
                startDate = day,
                endDate = day.plusMonths(1),
            )
            return when {
                day.isBefore(today) -> base.copy(timing = Timing.NOW, nowDate = day, timings = listOf(Timing.NOW, Timing.REPEATS))
                day == today -> base
                else -> base.copy(timing = Timing.LATER, laterDate = day, timings = listOf(Timing.LATER, Timing.REPEATS))
            }
        }
    }
}

sealed interface FormResult {
    data class Valid(val entry: NewEntry) : FormResult
    data class Invalid(val problems: List<FormProblem>) : FormResult
}

object EntryForm {
    /**
     * Frequencies offered for [kind] (the same for expenses and income): every day; every week on chosen weekdays;
     * every 2 weeks; every month on a chosen day; first day of every month; last day of every month; every year.
     */
    @Suppress("UNUSED_PARAMETER")
    fun choicesFor(kind: TxKind): List<PatternChoice> = listOf(
        PatternChoice.DAILY, PatternChoice.WEEKLY, PatternChoice.EVERY_2_WEEKS,
        PatternChoice.MONTH_DAY, PatternChoice.MONTH_FIRST, PatternChoice.MONTH_LAST, PatternChoice.YEARLY,
    )

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
            Timing.NOW -> NewEntry.Now(state.kind, amount, description, state.nowDate)
            Timing.LATER -> NewEntry.Planned(state.kind, amount, description, state.laterDate)
            Timing.REPEATS -> {
                val n = state.everyN.toIntOrNull()?.takeIf { it >= 1 }
                val day = state.dayOfMonth.toIntOrNull()?.takeIf { it in 1..31 }
                val pattern: RecurrencePattern? = when (state.choice) {
                    PatternChoice.DAILY -> RecurrencePattern.Daily
                    PatternChoice.WEEKLY -> when (state.weekdays.size) {
                        0 -> null
                        1 -> RecurrencePattern.Weekly(state.weekdays.first())
                        else -> RecurrencePattern.WeeklyDays(state.weekdays)
                    }
                    PatternChoice.EVERY_N -> n?.let { RecurrencePattern.EveryNDays(it, state.startDate) }
                    PatternChoice.MONTH_FIRST -> RecurrencePattern.MonthFirst
                    PatternChoice.MONTH_LAST -> RecurrencePattern.MonthLast
                    PatternChoice.MONTH_DAY -> day?.let { RecurrencePattern.DayOfMonth(it) }
                    PatternChoice.EVERY_2_WEEKS -> RecurrencePattern.EveryNDays(14, state.startDate)
                    PatternChoice.YEARLY -> RecurrencePattern.Yearly(state.startDate.monthValue, state.startDate.dayOfMonth)
                }
                if (state.choice == PatternChoice.EVERY_N && n == null) problems += FormProblem.EVERY_N_INVALID
                if (state.choice == PatternChoice.WEEKLY && state.weekdays.isEmpty()) problems += FormProblem.WEEKDAYS_EMPTY
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
