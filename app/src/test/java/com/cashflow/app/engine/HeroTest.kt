package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

class OdometerTimingTest {
    @Test fun ninety_percent_of_the_change_happens_in_the_first_half_second() {
        assertEquals(0.90, OdometerTiming.progress(0.5), 0.005)
    }

    @Test fun the_last_ten_percent_takes_the_remaining_second() {
        assertTrue(OdometerTiming.progress(0.5) < 0.91)
        assertTrue(OdometerTiming.progress(1.4) < 1.0)
        assertTrue(OdometerTiming.progress(1.0) in 0.97..0.999)
    }

    @Test fun it_starts_at_zero_and_ends_at_exactly_one() {
        assertEquals(0.0, OdometerTiming.progress(0.0), 0.0)
        assertEquals(1.0, OdometerTiming.progress(1.5), 0.0)
    }

    @Test fun it_is_monotonic() {
        var last = -1.0
        for (i in 0..1500) {
            val p = OdometerTiming.progress(i / 1000.0)
            assertTrue(p >= last)
            last = p
        }
    }

    @Test fun it_is_clamped_outside_the_duration() {
        assertEquals(0.0, OdometerTiming.progress(-3.0), 0.0)
        assertEquals(1.0, OdometerTiming.progress(9.0), 0.0)
    }

    @Test fun the_duration_is_exactly_1500_ms_regardless_of_the_size_of_the_change() {
        assertEquals(1500, OdometerTiming.DURATION_MILLIS)
        // The same curve for a 1-cent change and a million-dinar change: the value is final at 1.5 s for both.
        assertEquals(101.0, OdometerTiming.valueAt(100, 101, 1.5), 0.0)
        assertEquals(100_000_000.0, OdometerTiming.valueAt(0, 100_000_000, 1.5), 0.0)
        assertEquals(90_000_000.0, OdometerTiming.valueAt(0, 100_000_000, 0.5), 100_000.0)
    }

    @Test fun values_move_monotonically_toward_the_target_in_both_directions() {
        var last = Double.MAX_VALUE
        for (i in 0..150) {
            val v = OdometerTiming.valueAt(99_999, 13_500, i / 100.0)
            assertTrue(v <= last && v >= 13_500)
            last = v
        }
    }
}

class HeroLaunchStartTest {
    @Test fun all_nines_with_the_same_number_of_integer_digits() {
        assertEquals(99_999L, HeroLaunch.startCents(13_500)) // 135 -> 999.99
        assertEquals(99_999L, HeroLaunch.startCents(10_000)) // 100 -> 999.99
        assertEquals(9_999_999L, HeroLaunch.startCents(1_000_000)) // 10 000 -> 99 999.99
        assertEquals(999L, HeroLaunch.startCents(700)) // 7 -> 9.99
        assertEquals(999L, HeroLaunch.startCents(0)) // 0 -> 9.99
    }

    @Test fun negative_targets_keep_the_sign() {
        assertEquals(-9_999L, HeroLaunch.startCents(-3_800)) // -38 -> -99.99
        assertEquals(-999L, HeroLaunch.startCents(-5)) // -0.05 -> -9.99
    }

    @Test fun a_target_that_is_already_all_nines_starts_from_zero() {
        assertEquals(0L, HeroLaunch.startCents(999)) // 9.99
        assertEquals(0L, HeroLaunch.startCents(9_999)) // 99.99
        assertEquals(0L, HeroLaunch.startCents(99_999))
        assertEquals(0L, HeroLaunch.startCents(-9_999))
    }

    @Test fun the_start_is_always_beyond_the_target_and_never_changes_the_integer_digit_count() {
        for (target in listOf(0L, 1L, 99L, 100L, 4_999L, 13_500L, 123_456L, -250L, -8_000L, 12_345_600L)) {
            val start = HeroLaunch.startCents(target)
            if (start != 0L) {
                assertTrue(abs(start) > abs(target))
                assertEquals(SlotLayout.digitsOf(target), SlotLayout.digitsOf(start))
                assertEquals(target < 0, start < 0 || target == 0L && false)
            }
        }
    }
}

class HeroLaunchDecisionTest {
    @Test fun a_fresh_launch_plays_the_countdown_on_the_first_value() {
        assertEquals(HeroAction.LAUNCH_COUNTDOWN, HeroLaunch.decide(freshLaunch = true, firstEmission = true, animationsEnabled = true))
    }

    @Test fun recreation_with_a_saved_state_never_replays_it() {
        // rotation, theme or font change, process restore: savedInstanceState != null
        assertEquals(HeroAction.SNAP, HeroLaunch.decide(freshLaunch = false, firstEmission = true, animationsEnabled = true))
    }

    @Test fun later_values_animate_from_the_old_to_the_new_value() {
        assertEquals(HeroAction.ANIMATE_CHANGE, HeroLaunch.decide(freshLaunch = true, firstEmission = false, animationsEnabled = true))
        assertEquals(HeroAction.ANIMATE_CHANGE, HeroLaunch.decide(freshLaunch = false, firstEmission = false, animationsEnabled = true))
    }

    @Test fun system_animations_off_means_the_final_value_at_once() {
        for (fresh in listOf(true, false)) for (first in listOf(true, false)) {
            assertEquals(HeroAction.SNAP, HeroLaunch.decide(fresh, first, animationsEnabled = false))
        }
    }
}

class HeroFormatTest {
    private val nb = " "

    @Test fun always_exactly_two_decimals_including_dot_zero_zero() {
        assertEquals("135.00", HeroFormat.format(135_000))
        assertEquals("0.00", HeroFormat.format(0))
        assertEquals("7.50", HeroFormat.format(7_500))
        assertEquals("0.05", HeroFormat.format(50))
        assertEquals("12.35", HeroFormat.format(12_345)) // rounded half up for display
        assertEquals("1.00", HeroFormat.format(999))
    }

    @Test fun grouped_thousands() {
        assertEquals("1${nb}250.00", HeroFormat.format(1_250_000))
        assertEquals("99${nb}999.99", HeroFormat.format(99_999_990))
        assertEquals("1${nb}000${nb}000.00", HeroFormat.format(1_000_000_000))
    }

    @Test fun negative_values_use_a_proper_minus_and_never_minus_zero() {
        assertEquals("−38.50", HeroFormat.format(-38_500))
        assertEquals("−1${nb}250.00", HeroFormat.format(-1_250_000))
        assertEquals("0.00", HeroFormat.format(-4))
    }

    @Test fun every_result_has_two_digits_after_the_point() {
        for (v in listOf(0L, 1L, 10L, 999L, 1_000L, 12_345L, -77L, -123_456L, 5_555_555L)) {
            val text = HeroFormat.format(v)
            assertEquals(text, 2, text.substringAfter('.').length)
        }
    }

    @Test fun the_spoken_form_has_no_forced_decimals() {
        assertEquals("135", HeroFormat.spoken(135_000))
        assertEquals("75.5", HeroFormat.spoken(75_500))
    }
}

class SlotLayoutTest {
    private val metrics = SlotMetrics(bigDigit = 30.0, smallDigit = 15.0, group = 9.0, point = 8.0, minus = 20.0, label = 50.0)

    private fun widthOf(centsAbs: Long, reserve: Int = 1, minus: Boolean = false) =
        SlotLayout.width(SlotLayout.forValue(centsAbs, reserve, minus), metrics)

    @Test fun total_width_depends_only_on_the_number_of_digits_never_on_which_digits_show() {
        for (digits in 1..6) {
            val widths = HashSet<Double>()
            val base = Math.pow(10.0, (digits - 1).toDouble()).toLong() * 100 // e.g. 100.00 for 3 digits
            // every digit 0..9 in every position (the leading digit 1..9, so the digit count stays the same)
            for (position in 0 until digits + 2) {
                for (d in 0..9) {
                    val unit = Math.pow(10.0, position.toDouble()).toLong()
                    val current = (base / unit) % 10
                    val value = base - current * unit + d * unit
                    if (SlotLayout.digitsOf(value) != digits) continue
                    widths += widthOf(value)
                }
            }
            assertEquals("digits=$digits widths=$widths", 1, widths.size)
        }
    }

    @Test fun grouping_and_decimal_slots_have_fixed_widths() {
        val slots = SlotLayout.slots(7, withMinus = false)
        assertTrue(slots.filter { it.kind == SlotKind.GROUP }.all { SlotLayout.widthOf(it.kind, metrics) == 9.0 })
        assertEquals(2, slots.count { it.kind == SlotKind.GROUP }) // 1 234 567
        assertEquals(1, slots.count { it.kind == SlotKind.POINT })
        assertEquals(8.0, SlotLayout.widthOf(SlotKind.POINT, metrics), 0.0)
        assertEquals(2, slots.count { it.kind == SlotKind.DIGIT_SMALL })
    }

    @Test fun slots_for_135_are_digits_point_decimals_and_label() {
        val kinds = SlotLayout.slots(3, withMinus = false).map { it.kind }
        assertEquals(
            listOf(SlotKind.DIGIT_BIG, SlotKind.DIGIT_BIG, SlotKind.DIGIT_BIG, SlotKind.POINT, SlotKind.DIGIT_SMALL, SlotKind.DIGIT_SMALL, SlotKind.LABEL),
            kinds,
        )
        assertEquals(listOf(4, 3, 2), SlotLayout.slots(3, false).filter { it.kind == SlotKind.DIGIT_BIG }.map { it.index })
    }

    @Test fun a_thousands_separator_sits_between_the_thousands_and_hundreds_digit() {
        val kinds = SlotLayout.slots(4, false).map { it.kind }
        assertEquals(SlotKind.GROUP, kinds[1])
        assertEquals(1, kinds.count { it == SlotKind.GROUP })
        assertEquals(0, SlotLayout.slots(3, false).count { it.kind == SlotKind.GROUP })
    }

    @Test fun the_minus_slot_adds_exactly_its_width() {
        assertEquals(widthOf(13_500) + 20.0, widthOf(13_500, minus = true), 0.0)
    }

    @Test fun reserving_more_digits_reserves_exactly_their_width() {
        assertEquals(widthOf(13_500) + 2 * 30.0 + 9.0 * 0, widthOf(13_500, reserve = 5) - 9.0 /* one group slot appears */, 0.0)
        assertEquals(SlotLayout.digitsOf(13_500), 3)
        assertEquals(SlotLayout.digitsOf(99_999_999), 6)
        assertEquals(SlotLayout.digitsOf(0), 1)
        assertEquals(SlotLayout.digitsOf(-12_345), 3)
    }
}

class OdometerWheelTest {
    @Test fun at_an_exact_value_every_wheel_stands_on_its_digit() {
        for (cents in listOf(0L, 5L, 99L, 100L, 13_500L, 99_999L, 123_456L, 1_000_000L, 9_999_999L)) {
            val text = cents.toString().padStart(8, '0')
            for (index in 0..7) {
                val frame = Odometer.wheel(cents.toDouble(), index)
                assertEquals("cents=$cents index=$index", text[7 - index] - '0', frame.digit)
                assertEquals(0.0, frame.carry, 1e-9)
            }
        }
    }

    @Test fun the_cents_wheel_rolls_continuously_and_a_higher_wheel_only_while_the_one_below_passes_nine() {
        val cents = Odometer.wheel(13_503.5, 0)
        assertEquals(3, cents.digit)
        assertEquals(0.5, cents.carry, 1e-9)
        assertEquals(0.0, Odometer.wheel(13_503.5, 1).carry, 1e-9) // tens-of-cents wheel not moving yet
        val rolling = Odometer.wheel(13_509.5, 1) // 9.5 on the cents wheel: the next wheel is half-way rolled
        assertEquals(0, rolling.digit)
        assertEquals(0.5, rolling.carry, 1e-9)
        assertEquals(1, rolling.next)
    }

    @Test fun carries_ripple_up_through_a_run_of_nines() {
        // 13 599.5: cents wheel 9.5 -> tens-of-cents 9.5 -> TND units wheel 5.5; the tens wheel (3) is not moving
        assertEquals(0.5, Odometer.wheel(13_599.5, 1).carry, 1e-9)
        assertEquals(0.5, Odometer.wheel(13_599.5, 2).carry, 1e-9)
        assertEquals(0.0, Odometer.wheel(13_599.5, 3).carry, 1e-9)
        // 13 999.5: the wheels up to the tens of TND roll together; the hundreds wheel (the tens wheel is at 3.5) stays
        assertEquals(0.5, Odometer.wheel(13_999.5, 3).carry, 1e-9)
        assertEquals(0.0, Odometer.wheel(13_999.5, 4).carry, 1e-9)
        // 19 999.5: the tens wheel is at 9.5 too, so the hundreds wheel rolls as well
        assertEquals(0.5, Odometer.wheel(19_999.5, 3).carry, 1e-9)
        assertEquals(0.5, Odometer.wheel(19_999.5, 4).carry, 1e-9)
    }

    @Test fun each_wheel_moves_continuously_and_at_most_as_fast_as_the_value_changes() {
        val step = 0.37
        var y = 0.0
        val limit = 30_000.0
        var previous = (0..6).map { val f = Odometer.wheel(y, it); f.digit + f.carry }
        while (y < limit) {
            y += step
            val now = (0..6).map { val f = Odometer.wheel(y, it); f.digit + f.carry }
            for (i in 0..6) {
                val raw = abs(now[i] - previous[i])
                val circular = minOf(raw, 10 - raw) // 9.99 -> 0.00 is a small move on a wheel
                assertTrue("wheel $i at y=$y moved $circular", circular <= step + 1e-6)
            }
            previous = now
        }
    }

    @Test fun the_cents_wheel_is_the_fastest_and_higher_wheels_change_digit_less_often() {
        val changes = IntArray(5)
        var last = IntArray(5) { Odometer.wheel(0.0, it).digit }
        for (cents in 1..100_000) {
            for (i in 0..4) {
                val d = Odometer.wheel(cents.toDouble(), i).digit
                if (d != last[i]) changes[i]++
                last[i] = d
            }
        }
        for (i in 1..4) assertTrue("wheel ${i - 1} vs $i: ${changes.toList()}", changes[i - 1] > changes[i])
    }

    @Test fun leading_zeros_above_the_units_digit_are_blank_but_the_units_digit_is_not() {
        assertTrue(Odometer.wheel(1_350.0, 4).blank) // 13.50: hundreds wheel is a leading zero
        assertFalse(Odometer.wheel(13_500.0, 4).blank)
        assertFalse(Odometer.wheel(50.0, 2).blank) // 0.50: the units digit still shows its 0
        assertFalse(Odometer.wheel(0.0, 0).blank)
        assertFalse(Odometer.wheel(0.0, 1).blank)
    }

    @Test fun the_next_digit_wraps_from_nine_to_zero() {
        assertEquals(0, Odometer.wheel(9.5, 0).next)
        assertEquals(9, Odometer.wheel(9.5, 0).digit)
    }
}

class TickSpacingTest {
    @Test fun ticks_closer_than_25_ms_are_dropped() {
        assertTrue(RouletteTicks.shouldFire(1_000, -1, 0))
        assertFalse(RouletteTicks.shouldFire(1_010, 1_000, 1))
        assertFalse(RouletteTicks.shouldFire(1_024, 1_000, 1))
        assertTrue(RouletteTicks.shouldFire(1_025, 1_000, 1))
    }

    @Test fun the_thirty_tick_cap_leaves_a_slot_for_the_arrival_tick() {
        assertTrue(RouletteTicks.shouldFire(10_000, 0, 28))
        assertFalse(RouletteTicks.shouldFire(10_000, 0, 29))
        assertFalse(RouletteTicks.shouldFire(10_000, 0, 30))
    }

    @Test fun a_simulated_animation_never_exceeds_the_cap_or_the_spacing() {
        val from = 99_999L
        val to = 13_500L
        val steps = RouletteTicks.stepCount(from * 10, to * 10)
        var passedBefore = 0
        var last = -1L
        var fired = 0
        val times = ArrayList<Long>()
        var t = 0L
        while (t <= 1_500) { // 60 fps frames
            val v = OdometerTiming.valueAt(from, to, t / 1000.0).toLong()
            val passed = RouletteTicks.thresholdsPassed(from, to, v, steps)
            if (passed > passedBefore) {
                passedBefore = passed
                if (RouletteTicks.shouldFire(t, last, fired)) { fired++; last = t; times += t }
            }
            t += 16
        }
        assertTrue(fired + 1 <= RouletteTicks.MAX_TICKS)
        assertTrue(times.zipWithNext { a, b -> b - a }.all { it >= RouletteTicks.MIN_SPACING_MILLIS })
        assertTrue(fired > 5)
        // rapid at first, sparse at the end
        val gaps = times.zipWithNext { a, b -> b - a }
        assertTrue(gaps.last() > gaps.first())
    }
}
