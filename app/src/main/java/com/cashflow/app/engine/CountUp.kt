package com.cashflow.app.engine

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The count-up of the Available number, as pure functions: a strong ease-out (progress(t) = 1 - (1 - t)^4)
 * over a duration that grows with the size of the change, so the last digits visibly land.
 */
object CountUp {
    const val MIN_MILLIS = 1400
    const val MAX_MILLIS = 3600

    /** Share of the value change covered after fraction [t] (0..1) of the duration. */
    fun progress(t: Double): Double {
        val c = t.coerceIn(0.0, 1.0)
        return 1.0 - (1.0 - c).pow(4)
    }

    /** clamp(1400 + 800 * log10(1 + |delta in TND|), 1400, 3600) ms. */
    fun durationMillis(from: Long, to: Long): Int {
        val deltaTnd = abs(to - from).toDouble() / Money.MILLIMES_PER_TND
        return (MIN_MILLIS + 800.0 * log10(1.0 + deltaTnd)).coerceIn(MIN_MILLIS.toDouble(), MAX_MILLIS.toDouble()).toInt()
    }

    /** The value shown after fraction [t] of the duration; exactly [to] at t = 1. */
    fun valueAt(from: Long, to: Long, t: Double): Long =
        if (t >= 1.0) to else from + ((to - from).toDouble() * progress(t)).roundToLong()

    /** Fraction of the duration at which the value has covered [share] (0..1) of the change. */
    fun timeToReach(share: Double): Double = 1.0 - (1.0 - share.coerceIn(0.0, 1.0)).pow(0.25)
}
