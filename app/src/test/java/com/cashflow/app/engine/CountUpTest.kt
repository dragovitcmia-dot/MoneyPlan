package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountUpTest {
    @Test fun progress_is_a_strong_ease_out_of_the_form_one_minus_one_minus_t_to_the_fourth() {
        assertEquals(0.0, CountUp.progress(0.0), 1e-12)
        assertEquals(1.0, CountUp.progress(1.0), 1e-12)
        assertEquals(1 - Math.pow(0.5, 4.0), CountUp.progress(0.5), 1e-12)
        assertEquals(1 - Math.pow(0.9, 4.0), CountUp.progress(0.1), 1e-12)
    }

    @Test fun progress_is_monotonic_and_clamped() {
        var last = -1.0
        for (i in 0..1000) {
            val p = CountUp.progress(i / 1000.0)
            assertTrue(p >= last)
            last = p
        }
        assertEquals(0.0, CountUp.progress(-1.0), 0.0)
        assertEquals(1.0, CountUp.progress(7.0), 0.0)
    }

    @Test fun at_least_40_percent_of_the_time_covers_the_last_5_percent_of_the_change() {
        val tAt95 = CountUp.timeToReach(0.95)
        val timeShare = 1.0 - tAt95
        assertTrue("last 5% takes ${timeShare * 100}% of the time", timeShare >= 0.40)
        // Cross-check against the function itself, not just the inverse formula.
        assertTrue(CountUp.progress(tAt95) in 0.9499..0.9501)
        assertTrue(CountUp.progress(tAt95 - 0.01) < 0.95) // still short of 95% just before that point
    }

    @Test fun at_least_15_percent_of_the_time_covers_the_last_1_percent_of_the_change() {
        val tAt99 = CountUp.timeToReach(0.99)
        val timeShare = 1.0 - tAt99
        assertTrue("last 1% takes ${timeShare * 100}% of the time", timeShare >= 0.15)
        assertTrue(CountUp.progress(tAt99) in 0.9899..0.9901)
    }

    @Test fun the_value_is_exact_at_the_end_and_starts_at_the_beginning() {
        assertEquals(0L, CountUp.valueAt(0, 123_456, 0.0))
        assertEquals(123_456L, CountUp.valueAt(0, 123_456, 1.0))
        assertEquals(-77_000L, CountUp.valueAt(0, -77_000, 1.0)) // negative targets count down from 0
        assertEquals(10_000L, CountUp.valueAt(10_000, 50_000, 0.0))
    }

    @Test fun counting_toward_a_negative_value_never_overshoots() {
        for (i in 0..100) {
            val v = CountUp.valueAt(0, -50_000, i / 100.0)
            assertTrue(v <= 0 && v >= -50_000)
        }
    }

    @Test fun duration_is_clamped_between_1400_and_3600() {
        assertEquals(1400, CountUp.durationMillis(5_000, 5_000))
        assertEquals(1400, CountUp.durationMillis(0, 0))
        assertTrue(CountUp.durationMillis(0, 1) >= 1400)
        assertEquals(3600, CountUp.durationMillis(0, 5_000_000_000)) // millions of TND
        for (delta in listOf(0L, 1L, 500L, 10_000L, 123_456L, 99_999_999L)) {
            val d = CountUp.durationMillis(0, delta)
            assertTrue(d in 1400..3600)
        }
    }

    @Test fun duration_follows_the_log_formula() {
        // 100 TND: 1400 + 800 * log10(101) = 3004 ms ("large changes take about 3 seconds")
        assertEquals(3004.0, CountUp.durationMillis(0, 100_000).toDouble(), 1.0)
        // 9 TND: 1400 + 800 * log10(10) = 2200
        assertEquals(2200.0, CountUp.durationMillis(0, 9_000).toDouble(), 1.0)
        // sign of the change does not matter
        assertEquals(CountUp.durationMillis(0, 100_000), CountUp.durationMillis(100_000, 0))
        assertEquals(CountUp.durationMillis(0, -100_000), CountUp.durationMillis(0, 100_000))
    }

    @Test fun duration_grows_with_the_size_of_the_change() {
        val a = CountUp.durationMillis(0, 1_000)
        val b = CountUp.durationMillis(0, 100_000)
        val c = CountUp.durationMillis(0, 10_000_000)
        assertTrue(a < b && b < c)
    }

    @Test fun displayed_values_have_at_most_two_decimals_the_whole_way() {
        val from = 0L
        val to = 123_456L
        for (i in 0..2000) {
            val shown = Money.formatDisplay(CountUp.valueAt(from, to, i / 2000.0), withCurrency = false)
            assertTrue(shown, shown.substringAfter('.', "").length <= 2)
        }
    }

    @Test fun the_last_digits_visibly_count_up_slowly_at_the_end() {
        // Over the final quarter of the duration (at 100 TND, about 750 ms) the displayed cents keep changing
        // many times, and consecutive frames at the very end differ by at most a cent or two.
        val to = 100_000L
        val steps = 3004 / 16 // ~16 ms frames
        val shown = (0..steps).map { Money.roundToCents(CountUp.valueAt(0, to, it / steps.toDouble())) }
        val lastQuarter = shown.takeLast(steps / 4).toSet()
        assertTrue("distinct values in the last quarter: ${lastQuarter.size}", lastQuarter.size >= 10)
        assertTrue(shown.takeLast(5).zipWithNext { a, b -> b - a }.all { it in 0..20 })
    }

    @Test fun ticks_get_spaced_out_as_the_count_slows_down() {
        val from = 0L
        val to = 100_000L
        val steps = RouletteTicks.stepCount(from, to)
        val frames = CountUp.durationMillis(from, to) / 8
        var passedBefore = 0
        val tickTimes = ArrayList<Double>()
        for (f in 1..frames) {
            val t = f / frames.toDouble()
            val passed = RouletteTicks.thresholdsPassed(from, to, CountUp.valueAt(from, to, t), steps)
            if (passed > passedBefore) { tickTimes += t; passedBefore = passed }
        }
        assertTrue(tickTimes.size >= steps - 2)
        val gaps = tickTimes.zipWithNext { a, b -> b - a }
        assertTrue("gaps widen: ${gaps.first()} -> ${gaps.last()}", gaps.last() > gaps.first() * 4)
        assertTrue(tickTimes.size + 1 <= RouletteTicks.MAX_TICKS)
    }
}
