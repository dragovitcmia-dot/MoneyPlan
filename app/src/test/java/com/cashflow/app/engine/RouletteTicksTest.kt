package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouletteTicksTest {
    @Test fun no_change_means_no_steps() = assertEquals(0, RouletteTicks.stepCount(5_000, 5_000))

    @Test fun small_changes_get_few_steps_and_big_ones_are_capped() {
        assertEquals(1, RouletteTicks.stepCount(0, 500)) // half a dinar
        assertEquals(1, RouletteTicks.stepCount(0, 1_000))
        assertEquals(5, RouletteTicks.stepCount(0, 5_000))
        assertEquals(24, RouletteTicks.stepCount(0, 24_000))
        assertEquals(24, RouletteTicks.stepCount(0, 1_000_000))
        assertEquals(24, RouletteTicks.stepCount(1_000_000, 0)) // downwards counts the same
    }

    @Test fun the_step_cap_stays_under_the_tick_limit() {
        assertTrue(RouletteTicks.MAX_STEPS < RouletteTicks.MAX_TICKS)
    }

    @Test fun nothing_is_passed_at_the_start_and_arrival_is_not_a_threshold() {
        assertEquals(0, RouletteTicks.thresholdsPassed(0, 100_000, 0, 10))
        assertEquals(9, RouletteTicks.thresholdsPassed(0, 100_000, 100_000, 10)) // steps - 1
    }

    @Test fun thresholds_are_evenly_spaced_in_value() {
        // 10 steps over 0..100_000: thresholds every 10_000.
        assertEquals(0, RouletteTicks.thresholdsPassed(0, 100_000, 9_999, 10))
        assertEquals(1, RouletteTicks.thresholdsPassed(0, 100_000, 10_000, 10))
        assertEquals(3, RouletteTicks.thresholdsPassed(0, 100_000, 35_000, 10))
        assertEquals(9, RouletteTicks.thresholdsPassed(0, 100_000, 99_999, 10))
    }

    @Test fun it_works_for_downward_counts_and_negative_values() {
        assertEquals(3, RouletteTicks.thresholdsPassed(100_000, 0, 65_000, 10))
        assertEquals(1, RouletteTicks.thresholdsPassed(-50_000, 50_000, -25_000, 4))
        assertEquals(2, RouletteTicks.thresholdsPassed(-50_000, 50_000, 0, 4))
    }

    @Test fun values_outside_the_range_are_clamped() {
        assertEquals(0, RouletteTicks.thresholdsPassed(0, 100_000, -5_000, 10))
        assertEquals(9, RouletteTicks.thresholdsPassed(0, 100_000, 500_000, 10))
    }

    @Test fun degenerate_cases_never_tick() {
        assertEquals(0, RouletteTicks.thresholdsPassed(0, 0, 0, 10))
        assertEquals(0, RouletteTicks.thresholdsPassed(0, 100, 50, 1))
        assertEquals(0, RouletteTicks.thresholdsPassed(0, 100, 50, 0))
    }

    @Test fun a_decelerating_animation_ticks_often_early_and_rarely_late() {
        // Simulate the animation with the same curve family: value = 1 - (1 - t)^4 (fast start, slow end).
        val from = 0L
        val to = 24_000L
        val steps = RouletteTicks.stepCount(from, to)
        var last = 0
        val tickTimes = ArrayList<Double>()
        for (frame in 1..600) {
            val t = frame / 600.0
            val value = (to * (1 - Math.pow(1 - t, 4.0))).toLong()
            val passed = RouletteTicks.thresholdsPassed(from, to, value, steps)
            if (passed > last) {
                tickTimes += t
                last = passed
            }
        }
        assertEquals(steps - 1, tickTimes.size)
        assertTrue(tickTimes.size + 1 <= RouletteTicks.MAX_TICKS) // plus the arrival tick
        val gaps = tickTimes.zipWithNext { a, b -> b - a }
        assertTrue("gaps should widen: ${gaps.first()} vs ${gaps.last()}", gaps.last() > gaps.first() * 3)
    }
}
