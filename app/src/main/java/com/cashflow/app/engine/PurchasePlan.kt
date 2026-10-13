package com.cashflow.app.engine

import java.time.LocalDate

/** Closing balances of today (index 0) and the days after it, from the balance engine, over the planning horizon. */
class PlanBaseline(val today: LocalDate, val closings: List<Long>) {
    val size get() = closings.size
    fun date(index: Int): LocalDate = today.plusDays(index.toLong())

    companion object {
        /** Today and the next 180 days. */
        const val HORIZON_DAYS = 180

        fun of(engine: BalanceEngine, horizon: Int = HORIZON_DAYS): PlanBaseline {
            val today = engine.today
            val byDate = engine.forecast(today, today.plusDays(horizon.toLong())).associate { it.date to it.closing }
            val fallback = engine.settings.startingBalance
            return PlanBaseline(today, (0..horizon).map { byDate[today.plusDays(it.toLong())] ?: fallback })
        }
    }
}

/** A hypothetical expense on day [dayIndex] (0 = today). */
data class Outflow(val dayIndex: Int, val amount: Long)

/** One way to pay: [amounts] on [dates] ([parts] = 1 is "pay all"). */
data class PurchaseOption(val parts: Int, val outflows: List<Outflow>, val dates: List<LocalDate>) {
    val amounts get() = outflows.map { it.amount }
    val startIndex get() = outflows.first().dayIndex
    val start get() = dates.first()
    val end get() = dates.last()
}

data class PurchaseResult(
    val price: Long,
    /** Pay all first (when it exists), then 2..6 parts; only the ones that can be placed within the horizon. */
    val options: List<PurchaseOption>,
    /** The earliest start; ties prefer fewer parts. Null when nothing is affordable. */
    val defaultParts: Int?,
    /** When [options] is empty: the smallest extra amount that would make paying all today safe. */
    val extraNeeded: Long?,
    /** Earliest day paying all is safe; null when it never is within the horizon. */
    val payAllDate: LocalDate?,
) {
    val affordable get() = options.isNotEmpty()
    fun option(parts: Int?): PurchaseOption? = options.firstOrNull { it.parts == parts } ?: options.firstOrNull { it.parts == defaultParts }
}

/**
 * "Plan a purchase": when can this price be spent without ever pushing the balance below what it would be anyway?
 *
 * A plan is SAFE when on every day d the simulated closing is at least min(0, baseline closing): money may not fall
 * below zero because of the purchase, and a day that is negative anyway may not get any worse. [headroom] is the
 * largest amount that can be spent on a day without breaking that, computed in one backward pass.
 */
object PurchasePlanner {
    const val MAX_PARTS = 6
    const val GAP_DAYS = 7
    const val ROUND_MILLIMES = 10L

    /** Baseline with the hypothetical [outflows] applied: every day from an outflow on is lower by its amount. */
    fun simulate(baseline: PlanBaseline, outflows: List<Outflow>): List<Long> {
        val delta = LongArray(baseline.size)
        for (o in outflows) if (o.dayIndex in 0 until baseline.size) delta[o.dayIndex] += o.amount
        var running = 0L
        return baseline.closings.mapIndexed { i, closing ->
            running += delta[i]
            closing - running
        }
    }

    /** The lowest a closing may sink on day i: zero, or the baseline itself when that is negative already. */
    private fun floor(baseline: PlanBaseline, i: Int) = minOf(0L, baseline.closings[i])

    fun isSafe(baseline: PlanBaseline, outflows: List<Outflow>): Boolean {
        val sim = simulate(baseline, outflows)
        return sim.indices.all { sim[it] >= floor(baseline, it) }
    }

    /**
     * headroom[d] = the most that can be spent on day d (on top of [simulated], default the baseline itself) without
     * breaking safety on day d or any later day. For the plain baseline this is min over t >= d of max(0, closing(t)).
     */
    fun headroom(baseline: PlanBaseline, simulated: List<Long> = baseline.closings): LongArray {
        val h = LongArray(baseline.size)
        var m = Long.MAX_VALUE
        for (i in baseline.size - 1 downTo 0) {
            m = minOf(m, maxOf(0L, simulated[i] - floor(baseline, i)))
            h[i] = m
        }
        return h
    }

    /** First day (index) on which [price] can be spent in one go, or null. */
    fun payAllIndex(baseline: PlanBaseline, price: Long): Int? {
        val h = headroom(baseline)
        return h.indices.firstOrNull { h[it] >= price }
    }

    /** [k] equal parts rounded to 0.01 TND, the last one absorbing the remainder so they add up to [price] exactly; null if a part would be below 0.01. */
    fun splitParts(price: Long, k: Int): List<Long>? {
        if (k < 1 || price <= 0) return null
        val part = (price / k + ROUND_MILLIMES / 2) / ROUND_MILLIMES * ROUND_MILLIMES
        val last = price - part * (k - 1)
        if (part < ROUND_MILLIMES || last < ROUND_MILLIMES) return null
        return List(k - 1) { part } + last
    }

    /** Greedy placement of [k] parts: the first on the earliest safe day, each next one at least 7 days later; null if one does not fit. */
    fun installments(baseline: PlanBaseline, price: Long, k: Int): List<Outflow>? {
        val parts = splitParts(price, k) ?: return null
        val placed = mutableListOf<Outflow>()
        var from = 0
        for (amount in parts) {
            val h = headroom(baseline, simulate(baseline, placed))
            val day = (from until baseline.size).firstOrNull { h[it] >= amount } ?: return null
            placed += Outflow(day, amount)
            from = day + GAP_DAYS
        }
        return placed
    }

    fun plan(baseline: PlanBaseline, price: Long): PurchaseResult {
        if (price <= 0) return PurchaseResult(price, emptyList(), null, null, null)
        val options = mutableListOf<PurchaseOption>()
        val payAll = payAllIndex(baseline, price)
        if (payAll != null) options += optionOf(baseline, listOf(Outflow(payAll, price)))
        for (k in 2..MAX_PARTS) installments(baseline, price, k)?.let { options += optionOf(baseline, it) }
        val default = options.minWithOrNull(compareBy<PurchaseOption> { it.startIndex }.thenBy { it.parts })?.parts
        val extra = if (options.isEmpty()) maxOf(0L, price - (baseline.closings.minOrNull() ?: 0L)) else null
        return PurchaseResult(price, options, default, extra, payAll?.let(baseline::date))
    }

    private fun optionOf(baseline: PlanBaseline, outflows: List<Outflow>) =
        PurchaseOption(outflows.size, outflows, outflows.map { baseline.date(it.dayIndex) })
}

/** One day of the calendar strip on the result screen. */
data class StripCell(
    val date: LocalDate,
    val dayNumber: Int,
    /** Closing balance with the chosen plan applied. */
    val closing: Long,
    /** The part of the purchase paid on this day, or null. */
    val purchase: Long?,
    val isToday: Boolean,
    /** "13", or "01 NOV" on the first cell and on the first day of a month. */
    val label: String,
) {
    val negative get() = closing < 0
    val closingText get() = CompactAmount.balance(closing)
    val purchaseText get() = purchase?.let { "-" + CompactAmount.format(it) }
}

object PurchaseStrip {
    const val DAYS = 60

    fun cells(baseline: PlanBaseline, option: PurchaseOption?, days: Int = DAYS): List<StripCell> {
        val sim = if (option == null) baseline.closings else PurchasePlanner.simulate(baseline, option.outflows)
        val byDay = option?.outflows?.associate { it.dayIndex to it.amount }.orEmpty()
        return (0 until minOf(days, baseline.size)).map { i ->
            val date = baseline.date(i)
            StripCell(date, date.dayOfMonth, sim[i], byDay[i], isToday = i == 0, label = if (i == 0 || date.dayOfMonth == 1) AppDates.dayMonth(date) else date.dayOfMonth.toString())
        }
    }

    fun firstPurchaseIndex(cells: List<StripCell>): Int? = cells.indexOfFirst { it.purchase != null }.takeIf { it >= 0 }

    /** "05 NOV" for pay all; "2 x 150 TND, 25 OCT to 08 NOV" for parts. */
    fun summary(option: PurchaseOption): String =
        if (option.parts == 1) {
            AppDates.dayMonth(option.start)
        } else {
            "${option.parts} x ${Money.formatDisplay(option.amounts.first())}, ${AppDates.dayMonth(option.start)} to ${AppDates.dayMonth(option.end)}"
        }

    /** The chip labels in order: "Pay all", "2", "3" ... */
    fun chipLabel(option: PurchaseOption): String = if (option.parts == 1) "Pay all" else option.parts.toString()
}
