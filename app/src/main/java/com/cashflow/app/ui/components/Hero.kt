package com.cashflow.app.ui.components

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.cashflow.app.R
import com.cashflow.app.engine.HeroAction
import com.cashflow.app.engine.HeroFormat
import com.cashflow.app.engine.HeroLaunch
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.Odometer
import com.cashflow.app.engine.OdometerTiming
import com.cashflow.app.engine.RouletteTicks
import com.cashflow.app.engine.Slot
import com.cashflow.app.engine.SlotKind
import com.cashflow.app.engine.SlotLayout
import com.cashflow.app.engine.SlotMetrics
import com.cashflow.app.ui.Haptics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * State of the hero "Available" number, shared by the whole app (it lives above the tabs, so switching
 * tabs never touches it). ONE animated value drives every wheel of the odometer. The per-frame state
 * ([timeFraction], [settle]) is only read in the draw phase of [HeroNumber], so nothing recomposes
 * while it spins.
 */
private const val TAG = "CashflowHero"
private const val MAX_STARTUP_WAIT_MILLIS = 800L
private const val STEADY_FRAME_NANOS = 40_000_000L

@Stable
class HeroOdometer {
    var fromCents by mutableLongStateOf(0L)
        private set
    var toCents by mutableLongStateOf(0L)
        private set

    /** Integer digit slots currently reserved (the larger count while a change is animating). */
    var reservedDigits by mutableIntStateOf(1)
        private set
    var hasValue by mutableStateOf(false)
        private set

    private var timeFraction by mutableFloatStateOf(1f)

    /** 0 = full reserved width, 1 = final width (the 150 ms settle after the digit count shrank). */
    val settle = Animatable(1f)

    /** The animated value in cents (signed). Read only in draw code. */
    fun currentCents(): Double =
        OdometerTiming.valueAt(fromCents, toCents, timeFraction.toDouble() * OdometerTiming.DURATION_MILLIS / 1000.0)

    /**
     * The first frames of a cold start are slow (class loading, first layout). An animation started inside
     * them would lose its first part to one long frame, so the start value is shown still until frames
     * are arriving normally again (at most [MAX_STARTUP_WAIT_MILLIS]).
     */
    private suspend fun awaitSteadyFrames() {
        val begin = SystemClock.uptimeMillis()
        var last = withFrameNanos { it }
        var calm = 0
        while (calm < 3 && SystemClock.uptimeMillis() - begin < MAX_STARTUP_WAIT_MILLIS) {
            val now = withFrameNanos { it }
            calm = if (now - last < STEADY_FRAME_NANOS) calm + 1 else 0
            last = now
        }
    }

    /**
     * Shows [targetMillis]: snaps, counts down from all nines (launch) or rolls from the current value
     * (change). Cancelling this coroutine (a new target arrived) drops the remaining ticks and the
     * arrival tick.
     */
    suspend fun show(targetMillis: Long, action: HeroAction, haptics: Haptics) {
        val target = Money.roundToCents(targetMillis) / 10
        val start = when (action) {
            HeroAction.SNAP -> target
            HeroAction.LAUNCH_COUNTDOWN -> HeroLaunch.startCents(target)
            HeroAction.ANIMATE_CHANGE -> if (hasValue) currentCents().roundToLong() else target
        }
        Log.i(TAG, "show action=$action from=$start to=$target t=${SystemClock.uptimeMillis()}")
        settle.snapTo(if (start == target) 1f else 0f)
        // Written together, without a suspension point in between, so no frame mixes old and new.
        fromCents = start
        toCents = target
        reservedDigits = max(SlotLayout.digitsOf(start), SlotLayout.digitsOf(target))
        timeFraction = if (start == target) 1f else 0f
        hasValue = true
        if (start == target) {
            reservedDigits = SlotLayout.digitsOf(target)
            return
        }

        if (action == HeroAction.LAUNCH_COUNTDOWN) awaitSteadyFrames()
        val steps = RouletteTicks.stepCount(start * 10, target * 10)
        coroutineScope {
            val ticker = launch {
                var passedBefore = 0
                var ticks = 0
                var lastTick = -1L
                snapshotFlow { timeFraction }.collect { f ->
                    val value = OdometerTiming.valueAt(start, target, f.toDouble() * OdometerTiming.DURATION_MILLIS / 1000.0).roundToLong()
                    val passed = RouletteTicks.thresholdsPassed(start, target, value, steps)
                    if (passed > passedBefore) {
                        passedBefore = passed
                        val now = SystemClock.uptimeMillis()
                        if (RouletteTicks.shouldFire(now, lastTick, ticks)) {
                            ticks++
                            lastTick = now
                            haptics.tick()
                        }
                    }
                }
            }
            animate(0f, 1f, animationSpec = tween(OdometerTiming.DURATION_MILLIS, easing = LinearEasing)) { value, _ ->
                timeFraction = value
            }
            Log.i(TAG, "settled t=${SystemClock.uptimeMillis()}")
            ticker.cancel()
        }
        haptics.arrive()
        if (reservedDigits > SlotLayout.digitsOf(target)) {
            settle.animateTo(1f, tween(150)) // leading reserved slots give their width back
        }
        reservedDigits = SlotLayout.digitsOf(target)
        settle.snapTo(1f)
    }
}

/** Everything measured for one font size: glyph layouts, slot metrics and the resulting widths (px). */
private class HeroGlyphs(
    val big: List<TextLayoutResult>,
    val small: List<TextLayoutResult>,
    val minus: TextLayoutResult,
    val point: TextLayoutResult,
    val label: TextLayoutResult,
    val metrics: SlotMetrics,
    val slots: List<Slot>,
    val reservedWidth: Float,
    val finalWidth: Float,
    val height: Float,
    val smallTop: Float,
    val labelTop: Float,
    val labelGap: Float,
)

private fun measureHero(
    measurer: TextMeasurer,
    base: TextStyle,
    sizeSp: Float,
    reservedDigits: Int,
    finalDigits: Int,
    withMinus: Boolean,
): HeroGlyphs {
    val bigStyle = base.copy(fontSize = sizeSp.sp)
    val smallStyle = base.copy(fontSize = (sizeSp * 0.5f).sp, fontWeight = FontWeight.Medium)
    fun layout(text: String, style: TextStyle) = measurer.measure(text, style, maxLines = 1, softWrap = false)
    val big = (0..9).map { layout(it.toString(), bigStyle) }
    val small = (0..9).map { layout(it.toString(), smallStyle) }
    val minus = layout("−", bigStyle)
    val point = layout(".", smallStyle)
    val label = layout("TND", smallStyle)
    // Every digit slot is as wide as the widest digit, so the width never depends on which digits show.
    val bigDigit = big.maxOf { it.size.width }.toDouble()
    val smallDigit = small.maxOf { it.size.width }.toDouble()
    val group = max(layout(" ", bigStyle).size.width.toDouble(), bigDigit * 0.28)
    val metrics = SlotMetrics(
        bigDigit = bigDigit,
        smallDigit = smallDigit,
        group = group,
        point = max(point.size.width.toDouble(), smallDigit * 0.45),
        minus = minus.size.width.toDouble(),
        label = label.size.width + bigDigit * 0.25,
    )
    val slots = SlotLayout.slots(reservedDigits, withMinus)
    val baseline = big[0].firstBaseline
    return HeroGlyphs(
        big = big, small = small, minus = minus, point = point, label = label, metrics = metrics, slots = slots,
        reservedWidth = SlotLayout.width(slots, metrics).toFloat(),
        finalWidth = SlotLayout.width(SlotLayout.slots(finalDigits, withMinus), metrics).toFloat(),
        height = big[0].size.height.toFloat(),
        smallTop = baseline - small[0].firstBaseline,
        labelTop = baseline - label.firstBaseline,
        labelGap = (bigDigit * 0.25).toFloat(),
    )
}

/**
 * The big Available number: fixed-width digit slots (so the width depends only on the number of
 * digits), decimals at half size, a TND label, and an odometer roll per digit drawn on a Canvas.
 * The text shrinks as a whole so it fits on one line. TalkBack hears only the final value.
 */
@Composable
fun HeroNumber(
    odometer: HeroOdometer,
    normalColor: Color,
    negativeColor: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = MaterialTheme.typography.displayLarge
    // These change once per animation (not per frame), so reading them here is cheap.
    val to = odometer.toCents
    val reserved = odometer.reservedDigits
    val withMinus = odometer.fromCents < 0 || to < 0
    val finalDigits = SlotLayout.digitsOf(to)
    val description = stringResource(R.string.hero_description, HeroFormat.spoken(to * 10))

    BoxWithConstraints(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        val maxWidth = constraints.maxWidth
        val glyphs = remember(reserved, finalDigits, withMinus, maxWidth, density.fontScale, density.density) {
            var size = base.fontSize.value
            var g = measureHero(measurer, base, size, reserved, finalDigits, withMinus)
            while (size > 18f && max(g.reservedWidth, g.finalWidth) > maxWidth) {
                size *= 0.94f
                g = measureHero(measurer, base, size, reserved, finalDigits, withMinus)
            }
            g
        }
        Canvas(Modifier.fillMaxWidth().height(with(density) { glyphs.height.toDp() })) {
            if (!odometer.hasValue) return@Canvas
            drawHero(odometer, glyphs, normalColor, negativeColor)
        }
    }
}

private fun DrawScope.drawHero(odometer: HeroOdometer, g: HeroGlyphs, normalColor: Color, negativeColor: Color) {
    val v = odometer.currentCents()
    val negative = v < 0
    val magnitude = abs(v)
    val color = if (negative) negativeColor else normalColor
    val dim = color.copy(alpha = color.alpha * 0.8f)
    val contentWidth = lerp(g.reservedWidth, g.finalWidth, odometer.settle.value)
    var x = (size.width + contentWidth) / 2f // right edge; slots grow to the left, so leading slots may be unused
    for (slot in g.slots.asReversed()) {
        val w = SlotLayout.widthOf(slot.kind, g.metrics).toFloat()
        x -= w
        when (slot.kind) {
            SlotKind.MINUS -> if (negative) drawCentered(g.minus, x, w, 0f, color)
            SlotKind.GROUP -> Unit // a fixed blank gap
            SlotKind.POINT -> drawCentered(g.point, x, w, g.smallTop, dim)
            SlotKind.LABEL -> drawText(g.label, dim, Offset(x + g.labelGap, g.labelTop))
            SlotKind.DIGIT_BIG -> drawWheel(Odometer.wheel(magnitude, slot.index), g.big, x, w, 0f, color)
            SlotKind.DIGIT_SMALL -> drawWheel(Odometer.wheel(magnitude, slot.index), g.small, x, w, g.smallTop, dim)
        }
    }
}

private fun DrawScope.drawCentered(layout: TextLayoutResult, x: Float, w: Float, top: Float, color: Color) {
    drawText(layout, color, Offset(x + (w - layout.size.width) / 2f, top))
}

/** One wheel: the current digit and the next one rolling in from above/below, clipped to one digit height. */
private fun DrawScope.drawWheel(
    frame: com.cashflow.app.engine.WheelFrame,
    layouts: List<TextLayoutResult>,
    x: Float,
    w: Float,
    top: Float,
    color: Color,
) {
    val h = layouts[0].size.height.toFloat()
    val carry = frame.carry.toFloat()
    clipRect(left = x, top = top, right = x + w, bottom = top + h) {
        if (!frame.blank) drawCentered(layouts[frame.digit], x, w, top - carry * h, color)
        drawCentered(layouts[frame.next], x, w, top + (1f - carry) * h, color)
    }
}
