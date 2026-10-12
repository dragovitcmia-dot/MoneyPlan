package com.cashflow.app.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cashflow.app.R
import com.cashflow.app.engine.CalendarGrid
import com.cashflow.app.engine.CompactAmount
import com.cashflow.app.engine.LiveDay
import com.cashflow.app.engine.LiveMonth
import com.cashflow.app.engine.Money
import com.cashflow.app.ui.components.FitText
import com.cashflow.app.ui.theme.Palette
import com.cashflow.app.ui.theme.cashflow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private val FullDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
private val MediumDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/**
 * The live calendar of the Add wizard: a month grid where every day shows the projected closing balance with the new
 * item applied on the chosen day. Days that would be below zero are red and change as other days are tapped; past days
 * are disabled. Same look as the Calendar tab: faint day numbers, full-contrast balances that are always shown.
 * [project] computes a month for the current selection (it runs off the main thread in the caller).
 */
@Composable
fun LiveCalendarPicker(
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
    /** Changes whenever what is being added changes, so the balances are recomputed. */
    projectionKey: Any,
    project: suspend (YearMonth, LocalDate?, DayOfWeek) -> LiveMonth?,
    modifier: Modifier = Modifier,
) {
    val firstDay = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    val live by produceState<LiveMonth?>(null, month, selected, projectionKey) {
        value = project(month, selected, firstDay) // the previous month stays visible until the new numbers arrive
    }
    val title = remember(month) { month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())) }
    val density = LocalDensity.current
    val numbers = with(density) { (16.sp * 1.3f).toDp() + (13.sp * 1.3f).toDp() }
    val rowHeight = numbers + 22.dp

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.calendar_previous_month))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(onClick = { month = month.plusMonths(1) }) {
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.calendar_next_month))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (dow in CalendarGrid.weekdays(firstDay)) {
                Text(
                    dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = Palette.DAY_NUMBER_ALPHA),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val current = live
        if (current == null) {
            Box(Modifier.fillMaxWidth().height(rowHeight * 5), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.live_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val rows = current.weeks.size
            val horizontalLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            val verticalLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            val onMonthChange by rememberUpdatedState { delta: Int -> month = month.plusMonths(delta.toLong()) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight * rows)
                    // Swipe sideways to change month.
                    .pointerInput(Unit) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total > 120f) onMonthChange(-1) else if (total < -120f) onMonthChange(1)
                            },
                        ) { _, amount -> total += amount }
                    }
                    .drawBehind {
                        val thick = 2.dp.toPx()
                        val thin = 1.dp.toPx()
                        for (i in 1 until CalendarGrid.DAYS_PER_WEEK) {
                            val x = size.width * i / CalendarGrid.DAYS_PER_WEEK
                            drawLine(verticalLine, Offset(x, 0f), Offset(x, size.height), strokeWidth = thin)
                        }
                        for (j in 1 until rows) {
                            val y = size.height * j / rows
                            drawLine(horizontalLine, Offset(0f, y), Offset(size.width, y), strokeWidth = thick)
                        }
                    },
            ) {
                for (week in current.weeks) {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        for (day in week) {
                            LiveCell(day, today = day.isToday, onPick = onPick, modifier = Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
            val summary = current.summary
            Text(
                if (summary == null) {
                    stringResource(R.string.live_summary_empty)
                } else {
                    stringResource(
                        R.string.live_summary,
                        summary.date.format(MediumDate),
                        Money.formatDisplay(summary.balanceOnDay, withCurrency = false),
                        Money.formatDisplay(summary.lowestAfter, withCurrency = false),
                        summary.lowestDate.format(MediumDate),
                    )
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (summary != null && (summary.balanceOnDay < 0 || summary.lowestAfter < 0)) MaterialTheme.cashflow.negative else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private val CellGap = 3.dp

@Composable
private fun LiveCell(day: LiveDay, today: Boolean, onPick: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.cashflow
    val number = if (day.inMonth) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = if (day.enabled) Palette.DAY_NUMBER_ALPHA else 0.3f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    }
    val shape = MaterialTheme.shapes.small
    val fill = when {
        day.negative && day.enabled -> colors.dayNegativeFill
        day.selected -> colors.daySelectedFill
        else -> Color.Transparent
    }
    val resources = LocalContext.current.resources
    val description = remember(day, resources) {
        buildString {
            append(day.date.format(FullDate))
            day.closing?.let { append(". ").append(resources.getString(R.string.live_cell_description, Money.formatDisplay(it, withCurrency = false))) }
            if (day.negative) append(" ").append(resources.getString(R.string.live_cell_negative))
        }
    }
    Box(
        modifier
            .padding(CellGap)
            .clip(shape)
            .background(fill)
            .then(if (day.selected) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape) else Modifier)
            .then(if (day.enabled && day.inMonth) Modifier.clickable(role = Role.Button) { onPick(day.date) } else Modifier)
            .semantics { contentDescription = description; this.selected = day.selected },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (today) {
                Box(
                    Modifier.defaultMinSize(minWidth = 24.dp, minHeight = 24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        day.date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal, color = number)
            }
        }
        // Money is full contrast, always shown (also on days that are disabled): red when below zero.
        if (day.closing != null && day.inMonth) {
            FitText(
                CompactAmount.balance(day.closing),
                if (day.negative) colors.negative else MaterialTheme.colorScheme.onSurface,
                MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 5.dp),
                minSize = 9.sp,
            )
        }
    }
}
