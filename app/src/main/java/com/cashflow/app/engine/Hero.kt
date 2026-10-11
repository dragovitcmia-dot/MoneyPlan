package com.cashflow.app.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/** Timing of the hero odometer: always 1.5 s; 90% of the change happens in the first 0.5 s. */
object OdometerTiming {
    const val DURATION_MILLIS = 1500
    private const val DURATION_SECONDS = 1.5
    private const val EXPONENT = 5.68

    /** Share of the change done after [tSeconds]: 1 - (1 - t/1.5)^5.68, clamped to [0, 1.5] s. */
    fun progress(tSeconds: Double): Double {
        val t = tSeconds.coerceIn(0.0, DURATION_SECONDS)
        return 1.0 - (1.0 - t / DURATION_SECONDS).pow(EXPONENT)
    }

    /** Value (in cents) at time [tSeconds] of an animation from [fromCents] to [toCents]; exact at the end. */
    fun valueAt(fromCents: Long, toCents: Long, tSeconds: Double): Double =
        if (tSeconds >= DURATION_SECONDS) toCents.toDouble() else fromCents + (toCents - fromCents) * progress(tSeconds)
}

/** What the hero number does when a new value arrives. */
enum class HeroAction { LAUNCH_COUNTDOWN, ANIMATE_CHANGE, SNAP }

object HeroLaunch {
    /**
     * The launch animation plays ONLY on the first value after a fresh start of the activity
     * (savedInstanceState == null). Returning from the background, rotation / theme / font changes
     * (recreation with a saved state) and tab switches never replay it. With system animations off
     * the final value is shown at once.
     */
    fun decide(freshLaunch: Boolean, firstEmission: Boolean, animationsEnabled: Boolean): HeroAction = when {
        !animationsEnabled -> HeroAction.SNAP
        firstEmission -> if (freshLaunch) HeroAction.LAUNCH_COUNTDOWN else HeroAction.SNAP
        else -> HeroAction.ANIMATE_CHANGE
    }

    /**
     * Where the launch count-down starts, in cents: all nines with the same number of integer digits
     * as the target (135 -> 999.99, 10 000 -> 99 999.99, 7 or 0 -> 9.99), keeping the sign. If that is
     * not above the target in magnitude (the target is already all nines) it starts from 0.
     */
    fun startCents(targetCents: Long): Long {
        val magnitude = abs(targetCents)
        val nines = 10.0.pow(SlotLayout.digitsOf(magnitude) + 2).toLong() - 1
        if (nines <= magnitude) return 0
        return if (targetCents < 0) -nines else nines
    }
}

object HeroFormat {
    /** The hero text: always 2 decimals, grouped thousands, proper minus sign: "135.00", "1 250.00", "−38.50". */
    fun format(millimes: Long): String {
        val cents = Money.roundToCents(millimes) / 10
        val magnitude = abs(cents)
        val whole = Money.group((magnitude / 100).toString())
        val fraction = (magnitude % 100).toString().padStart(2, '0')
        return (if (cents < 0) "−" else "") + whole + "." + fraction
    }

    /** Spoken form for TalkBack, without the rolling digits: "135", "1 250.5". */
    fun spoken(millimes: Long): String = Money.formatDisplay(millimes, withCurrency = false)
}

/** One wheel of the odometer as drawn: the current digit, the digit rolling in below it, and the roll. */
class WheelFrame(
    /** The digit shown now. */
    val digit: Int,
    /** The digit sliding in; it sits one digit height below and moves up as [carry] grows. */
    val next: Int,
    /** 0..1: how far the wheel has rolled toward [next]. */
    val carry: Double,
    /** A leading zero above the number: not drawn. */
    val blank: Boolean,
)

/**
 * Classic odometer. All wheels are driven by ONE value (|value| in cents). The cents wheel (index 0)
 * rolls continuously; a higher wheel only moves while the wheel below it passes from 9 to 0, so the
 * cents spin fastest, the tens slower, the hundreds slowest, and at an exact value every wheel stands
 * exactly on its digit (no half-rolled digits).
 */
object Odometer {
    fun wheel(centsAbs: Double, index: Int): WheelFrame {
        val y = max(centsAbs, 0.0)
        val place = 10.0.pow(index)
        val whole = floor(y / place)
        val digit = (whole.toLong() % 10).toInt()
        val carry = if (index == 0) y - floor(y) else carryInto(y, index)
        val blank = index >= 3 && whole == 0.0 // above the units digit of the TND part
        return WheelFrame(digit, (digit + 1) % 10, carry, blank)
    }

    private fun carryInto(y: Double, index: Int): Double {
        var p = y % 10.0 // continuous position of the cents wheel
        var carry = 0.0
        for (k in 1..index) {
            carry = max(0.0, p - 9.0)
            val digit = (floor(y / 10.0.pow(k)).toLong() % 10).toDouble()
            p = digit + carry
        }
        return carry
    }
}

enum class SlotKind { MINUS, DIGIT_BIG, GROUP, POINT, DIGIT_SMALL, LABEL }

/** A fixed-width slot of the hero. [index] is the cents position of a digit (0 = cents, 2 = TND units). */
data class Slot(val kind: SlotKind, val index: Int = 0)

/** Measured glyph widths (any unit): the widest digit at each size and the fixed separator widths. */
class SlotMetrics(
    val bigDigit: Double,
    val smallDigit: Double,
    val group: Double,
    val point: Double,
    val minus: Double,
    val label: Double,
)

/** Slots depend only on the number of digits (and the sign slot), never on which digits are showing. */
object SlotLayout {
    /** Integer digits of the TND part of [centsAbs], at least 1. */
    fun digitsOf(centsAbs: Long): Int = (abs(centsAbs) / 100).toString().length

    fun slots(integerDigits: Int, withMinus: Boolean): List<Slot> {
        val out = ArrayList<Slot>()
        if (withMinus) out += Slot(SlotKind.MINUS)
        for (position in integerDigits - 1 downTo 0) {
            out += Slot(SlotKind.DIGIT_BIG, position + 2)
            if (position > 0 && position % 3 == 0) out += Slot(SlotKind.GROUP)
        }
        out += Slot(SlotKind.POINT)
        out += Slot(SlotKind.DIGIT_SMALL, 1)
        out += Slot(SlotKind.DIGIT_SMALL, 0)
        out += Slot(SlotKind.LABEL)
        return out
    }

    /** Slots for showing [centsAbs] with room reserved for at least [reserveDigits] integer digits. */
    fun forValue(centsAbs: Long, reserveDigits: Int, withMinus: Boolean): List<Slot> =
        slots(max(digitsOf(centsAbs), reserveDigits), withMinus)

    fun width(slots: List<Slot>, m: SlotMetrics): Double = slots.sumOf { widthOf(it.kind, m) }

    fun widthOf(kind: SlotKind, m: SlotMetrics): Double = when (kind) {
        SlotKind.MINUS -> m.minus
        SlotKind.DIGIT_BIG -> m.bigDigit
        SlotKind.GROUP -> m.group
        SlotKind.POINT -> m.point
        SlotKind.DIGIT_SMALL -> m.smallDigit
        SlotKind.LABEL -> m.label
    }
}
