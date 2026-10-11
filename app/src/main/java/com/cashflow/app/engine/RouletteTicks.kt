package com.cashflow.app.engine

/**
 * Pure rules for the haptic "roulette" while the Available number counts to a new value: evenly
 * spaced value steps between start and target, one light tick per step crossed. Because the
 * count-up decelerates, ticks come fast at first and spread out as the number slows down.
 */
object RouletteTicks {
    const val MAX_STEPS = 24
    const val MAX_TICKS = 30

    /** 0 when nothing changes; about one step per TND for small changes, capped at [MAX_STEPS]. */
    fun stepCount(from: Long, to: Long): Int {
        val delta = if (to >= from) to - from else from - to
        if (delta == 0L) return 0
        val perTnd = (delta / Money.MILLIMES_PER_TND).coerceAtMost(MAX_STEPS.toLong()).toInt()
        return perTnd.coerceAtLeast(1)
    }

    /**
     * How many of the thresholds strictly inside (from, to) the animated [value] has passed so far.
     * Thresholds sit at from + k * (to - from) / steps for k = 1 .. steps - 1; arrival (k = steps)
     * gets its own stronger tick and is not counted here.
     */
    fun thresholdsPassed(from: Long, to: Long, value: Long, steps: Int): Int {
        if (steps <= 1 || from == to) return 0
        val fraction = ((value - from).toDouble() / (to - from).toDouble()).coerceIn(0.0, 1.0)
        return (fraction * steps).toInt().coerceAtMost(steps - 1)
    }
}
