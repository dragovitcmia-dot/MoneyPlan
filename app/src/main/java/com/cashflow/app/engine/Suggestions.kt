package com.cashflow.app.engine

import java.time.LocalDate

/** "Same as last time": the amount of the most recent matching payment and, when it repeats, its series (the schedule). */
data class Suggestion(val amount: Long, /** The series the match belongs to; null for a one-time payment. */ val rule: RecurringRule?) {
    val repeating get() = rule != null
}

/** Smart defaults for the add wizard: pure functions from the history, no Android classes. */
object Suggestions {
    private fun key(name: String) = name.trim().lowercase()

    /**
     * The most recent earlier payment of the same [kind] whose name matches [name] (ignoring case and surrounding spaces); null for
     * a blank or new name. Series count by their start date (their latest amount is the series amount), one-time payments by their
     * date; rows that a series generated are covered by the series itself and skipped rows never count. Expenses and income never
     * mix: a name used only for income gives nothing for an expense.
     */
    fun sameAsLast(name: String, kind: TxKind, transactions: List<Transaction>, rules: List<RecurringRule>): Suggestion? {
        val wanted = key(name)
        if (wanted.isEmpty()) return null
        class Candidate(val date: LocalDate, val order: Long, val suggestion: Suggestion)
        val candidates = buildList {
            for (r in rules) if (r.kind == kind && key(r.description) == wanted) add(Candidate(r.startDate, r.id, Suggestion(r.amount, r)))
            for (t in transactions) {
                if (t.kind != kind || t.recurringRuleId != null || t.status == TxStatus.SKIPPED || key(t.description) != wanted) continue
                add(Candidate(t.date, t.id, Suggestion(t.amount, null)))
            }
        }
        return candidates.maxWithOrNull(compareBy<Candidate> { it.date }.thenBy { it.order })?.suggestion
    }

    /** "every day", "every week", "every 2 weeks", "every 10 days", "every month", "every year". */
    fun scheduleWords(pattern: RecurrencePattern): String = when (pattern) {
        RecurrencePattern.Daily -> "every day"
        is RecurrencePattern.Weekly, is RecurrencePattern.WeeklyDays -> "every week"
        is RecurrencePattern.EveryNDays -> when {
            pattern.n == 7 -> "every week"
            pattern.n % 7 == 0 -> "every ${pattern.n / 7} weeks"
            else -> "every ${pattern.n} days"
        }
        RecurrencePattern.MonthFirst, RecurrencePattern.MonthLast, is RecurrencePattern.DayOfMonth -> "every month"
        is RecurrencePattern.Yearly -> "every year"
    }

    /** The one tappable line: "Same as last time: 75 TND, every week" (repeating) or "Same as last time: 75 TND" (one-time). */
    fun line(s: Suggestion): String {
        val amount = Money.formatDisplay(s.amount)
        return "Same as last time: " + amount + (s.rule?.let { ", " + scheduleWords(it.pattern) } ?: "")
    }

    /** The form after a tap: the amount and, for a series, its schedule; the typed name and the kind stay. */
    fun apply(form: EntryFormState, s: Suggestion, today: LocalDate): EntryFormState {
        val rule = s.rule ?: return form.copy(amountText = Money.formatInput(s.amount))
        val fromSeries = SeriesForm.formFor(rule, today)
        return fromSeries.copy(kind = form.kind, description = form.description, nowDate = form.nowDate, timings = form.timings)
    }

    /**
     * Up to [limit] recent amounts of [kind], newest first, each once: payments dated today or earlier (series by their start
     * date), skipped ones left out. Empty when there is no history.
     */
    fun recentAmounts(kind: TxKind, transactions: List<Transaction>, rules: List<RecurringRule>, today: LocalDate, limit: Int = 3): List<Long> {
        class Dated(val date: LocalDate, val order: Long, val amount: Long)
        val all = buildList {
            for (r in rules) if (r.kind == kind && !r.startDate.isAfter(today)) add(Dated(r.startDate, r.id, r.amount))
            for (t in transactions) {
                if (t.kind != kind || t.status == TxStatus.SKIPPED || t.recurringRuleId != null || t.date.isAfter(today)) continue
                add(Dated(t.date, t.id, t.amount))
            }
        }
        return all.sortedWith(compareByDescending<Dated> { it.date }.thenByDescending { it.order }).map { it.amount }.distinct().take(limit)
    }
}
