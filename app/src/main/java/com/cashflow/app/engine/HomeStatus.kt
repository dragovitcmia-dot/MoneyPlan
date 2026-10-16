package com.cashflow.app.engine

import java.time.LocalDate

/** The one quiet line under Home's balance. */
sealed interface HomeStatus {
    /** A negative day is coming: how short the first one closes and when. */
    data class Short(val amount: Long, val day: LocalDate) : HomeStatus

    /** The next expense that is waiting. */
    data class Next(val description: String, val amount: Long, val date: LocalDate) : HomeStatus

    data object None : HomeStatus
}

object HomeStatusLine {
    /** A coming shortage wins over the next expense; nothing to say when there is neither. */
    fun of(engine: BalanceEngine): HomeStatus {
        val today = engine.today
        val firstShort = engine.forecast(today, today.plusDays(Recurrence.HORIZON_DAYS)).firstOrNull { it.closing < 0 }
        if (firstShort != null) return HomeStatus.Short(-firstShort.closing, firstShort.date)
        engine.nextExpense()?.let { return HomeStatus.Next(it.description.trim(), it.amount, it.date) }
        return HomeStatus.None
    }

    /** "Next: Rent -300 TND, 05 NOV" or "Short by 100 TND before 10 NOV"; null for [HomeStatus.None]. */
    fun text(status: HomeStatus, today: LocalDate): String? = when (status) {
        is HomeStatus.Short -> "Short by ${Money.formatDisplay(status.amount)} before ${AppDates.formatShort(status.day, today)}"
        is HomeStatus.Next -> {
            val name = if (status.description.isBlank()) "" else status.description + " "
            "Next: $name${Money.formatSigned(TxKind.EXPENSE, status.amount)}, ${AppDates.formatShort(status.date, today)}"
        }
        HomeStatus.None -> null
    }

    /** The day a tap on the line opens in the Calendar: only the shortage line is tappable. */
    fun tapDay(status: HomeStatus): LocalDate? = (status as? HomeStatus.Short)?.day
}
