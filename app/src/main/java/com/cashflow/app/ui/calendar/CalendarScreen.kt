package com.cashflow.app.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.R
import com.cashflow.app.engine.CalendarGrid
import com.cashflow.app.engine.CompactAmount
import com.cashflow.app.engine.DaySummary
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.MonthPages
import com.cashflow.app.engine.MonthSummary
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import com.cashflow.app.ui.components.FitText
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.home.EntryComposer
import com.cashflow.app.ui.home.HomeViewModel
import com.cashflow.app.ui.items.ItemEditSheet
import com.cashflow.app.ui.items.RuleEditorHost
import com.cashflow.app.ui.items.rememberItemActions
import com.cashflow.app.ui.rememberHaptics
import com.cashflow.app.ui.settings.SettingsViewModel
import com.cashflow.app.ui.theme.cashflow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private val CellGap = 3.dp
private val GridPadding = 8.dp

/**
 * The Calendar tab: a month grid you can swipe, with each day's planned expense (red) and extra income
 * (green) in the cell, days that close below zero tinted red, and a day sheet to look at and change one day.
 */
@Composable
fun CalendarScreen(
    calendar: CalendarViewModel,
    home: HomeViewModel,
    settings: SettingsViewModel,
    snackbar: SnackbarHostState,
    padding: PaddingValues,
) {
    val months by calendar.months.collectAsStateWithLifecycle()
    val today by calendar.today.collectAsStateWithLifecycle()
    val selected by calendar.selected.collectAsStateWithLifecycle()
    val shortDay by calendar.shortDay.collectAsStateWithLifecycle()
    val visibleMonth by calendar.month.collectAsStateWithLifecycle()
    val homeUi by home.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { calendar.refreshDay() }

    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val actions = rememberItemActions(home, snackbar)
    val pager = rememberPagerState(initialPage = MonthPages.pageOf(visibleMonth)) { MonthPages.COUNT }

    // The view model follows the page the pager rests on; a light tick marks each month change.
    LaunchedEffect(pager) {
        var first = true
        snapshotFlow { pager.settledPage }.collect { page ->
            calendar.showMonth(MonthPages.monthOf(page))
            if (!first) haptics.tick()
            first = false
        }
    }

    var sheetDate by rememberSaveable { mutableStateOf<String?>(null) }
    var composerKind by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var editingSeries by remember { mutableStateOf<Long?>(null) }

    fun goToMonth(month: YearMonth) {
        scope.launch { pager.animateScrollToPage(MonthPages.pageOf(month)) }
    }

    fun select(day: LocalDate) {
        haptics.tick()
        calendar.select(day)
    }

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val amountStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val firstDay = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val shownMonth = MonthPages.monthOf(pager.currentPage)
    val title = remember(shownMonth) { shownMonth.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cellWidth = (maxWidth - GridPadding * 2) / CalendarGrid.DAYS_PER_WEEK
        // Amounts only when a typical one fits; otherwise (large font, very narrow cells) coloured dots.
        val dots = remember(density, cellWidth, amountStyle) {
            density.fontScale >= 1.5f ||
                measurer.measure("-8888", amountStyle).size.width > with(density) { (cellWidth - CellGap * 2 - 2.dp).toPx() }
        }
        val dayLine = with(density) { (16.sp * 1.3f).toDp() }
        val amountLine = with(density) { (13.sp * 1.3f).toDp() }
        // Normal size: about 92dp (day number, expense, income, and the day's balance at the bottom).
        val rowHeight = if (dots) dayLine + 6.dp + 12.dp + 6.dp else maxOf(92.dp, dayLine + amountLine * 3 + 22.dp)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Month title with its arrows; the Today button sits under it (the same layout at every font size).
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }) {
                        Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.calendar_previous_month))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(MonthPages.COUNT - 1)) } }) {
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.calendar_next_month))
                    }
                }
                AppButton(
                    stringResource(R.string.calendar_today),
                    onClick = {
                        haptics.tick()
                        calendar.select(today)
                        goToMonth(YearMonth.from(today))
                    },
                    style = AppButtonStyle.Tonal,
                )
            }

            shortDay?.let { day -> ShortBanner(day) {
                haptics.tick()
                calendar.select(day)
                goToMonth(YearMonth.from(day))
            } }

            WeekdayHeader(firstDay)

            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth().height(rowHeight * CalendarGrid.FIXED_ROWS),
                key = { it },
                verticalAlignment = Alignment.Top,
            ) { page ->
                MonthGrid(
                    summary = months[MonthPages.monthOf(page)],
                    selected = selected,
                    dots = dots,
                    rowHeight = rowHeight,
                    dayLine = dayLine,
                    amountStyle = amountStyle,
                    onDayClick = { day ->
                        select(day.date)
                        sheetDate = day.date.toString()
                    },
                )
            }

            if (dots) {
                Text(
                    stringResource(R.string.calendar_dots_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }

    // ---- day sheet and what it opens --------------------------------------------------------
    val openDate = sheetDate?.let(LocalDate::parse)
    if (openDate != null) {
        val summary = months.values.firstNotNullOfOrNull { it.day(openDate) }
        DaySheet(
            date = openDate,
            summary = summary,
            today = today,
            onDismiss = { sheetDate = null },
            onOpenItem = { editing = it },
            onAdd = { kind -> composerKind = kind.name },
        )
    }

    composerKind?.let { name ->
        val ui = homeUi
        val day = sheetDate?.let(LocalDate::parse)
        if (ui != null && day != null) {
            EntryComposer(
                kind = TxKind.valueOf(name),
                ui = ui,
                whatIf = home::whatIf,
                onSave = home::save,
                onSaved = { message -> actions.announce(message) },
                onDismiss = { composerKind = null },
                day = day,
            )
        }
    }

    editing?.let { tx ->
        ItemEditSheet(
            tx = tx,
            actions = actions,
            onDismiss = { editing = null },
            onEditSeries = { editingSeries = tx.recurringRuleId },
            today = today,
        )
    }

    editingSeries?.let { ruleId ->
        RuleEditorHost(ruleId = ruleId, settings = settings, onDismiss = { editingSeries = null })
    }
}

@Composable
private fun ShortBanner(day: LocalDate, onClick: () -> Unit) {
    val text = stringResource(
        R.string.calendar_runs_short,
        day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())).replace(' ', '\u00A0'), // "10 Nov" never breaks
    )
    val action = stringResource(R.string.calendar_runs_short_action)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { role = Role.Button; contentDescription = "$text. $action" },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun WeekdayHeader(firstDay: java.time.DayOfWeek) {
    val narrow = LocalDensity.current.fontScale > 1.3f
    Row(Modifier.fillMaxWidth().padding(horizontal = GridPadding)) {
        for (dow in CalendarGrid.weekdays(firstDay)) {
            Text(
                dow.getDisplayName(if (narrow) TextStyle.NARROW else TextStyle.SHORT, Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f).semantics { contentDescription = dow.getDisplayName(TextStyle.FULL, Locale.getDefault()) },
            )
        }
    }
}

@Composable
private fun MonthGrid(
    summary: MonthSummary?,
    selected: LocalDate?,
    dots: Boolean,
    rowHeight: Dp,
    dayLine: Dp,
    amountStyle: androidx.compose.ui.text.TextStyle,
    onDayClick: (DaySummary) -> Unit,
) {
    if (summary == null) {
        // Nothing numeric until this month has been computed.
        Box(Modifier.fillMaxWidth().height(rowHeight * CalendarGrid.FIXED_ROWS), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.cal_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = GridPadding)) {
        for (week in summary.weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (day in week) {
                    DayCell(
                        day = day,
                        selected = day.inMonth && day.date == selected,
                        dots = dots,
                        dayLine = dayLine,
                        amountStyle = amountStyle,
                        onClick = onDayClick,
                        modifier = Modifier.weight(1f).height(rowHeight),
                    )
                }
            }
        }
    }
}

private val FullDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

@Composable
private fun DayCell(
    day: DaySummary,
    selected: Boolean,
    dots: Boolean,
    dayLine: Dp,
    amountStyle: androidx.compose.ui.text.TextStyle,
    onClick: (DaySummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.cashflow
    val numberColor = if (day.isPast || !day.inMonth) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground
    if (!day.inMonth) {
        // The neighbouring months' days only fill the grid; they are dimmed and not touchable.
        Box(modifier.padding(CellGap).clearAndSetSemantics {}, contentAlignment = Alignment.TopCenter) {
            Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, color = numberColor, modifier = Modifier.padding(top = 4.dp))
        }
        return
    }
    val resources = LocalContext.current.resources
    val description = remember(day, resources) { describe(resources, day) }
    val shape = MaterialTheme.shapes.small
    val fill = when {
        day.negative -> colors.dayNegativeFill
        selected -> colors.daySelectedFill
        else -> Color.Transparent
    }
    Box(
        modifier
            .padding(CellGap)
            .clip(shape)
            .background(fill)
            .then(if (selected) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape) else Modifier)
            .clickable(role = Role.Button) { onClick(day) }
            .semantics { contentDescription = description; this.selected = selected },
    ) {
        Column(Modifier.fillMaxSize().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (day.isToday) {
                Box(
                    Modifier.defaultMinSize(minWidth = dayLine, minHeight = dayLine).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
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
                Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, color = numberColor)
            }
            if (dots) {
                if (day.hasExpenses || day.hasIncome) {
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (day.hasExpenses) Dot(colors.negative)
                        if (day.hasIncome) Dot(colors.cellIncome)
                    }
                }
            } else {
                if (day.hasExpenses) {
                    FitText(CompactAmount.signed(TxKind.EXPENSE, day.expenses), colors.negative, amountStyle, Modifier.fillMaxWidth())
                }
                if (day.hasIncome) {
                    FitText(CompactAmount.signed(TxKind.INCOME, day.shownIncome), colors.cellIncome, amountStyle, Modifier.fillMaxWidth())
                }
            }
        }
        if (!dots && day.hasForecast) {
            // The balance at the end of the day (history for past days, dimmed). Red when negative.
            FitText(
                CompactAmount.balance(day.closing),
                when {
                    day.negative -> colors.negative
                    day.isPast -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onBackground
                },
                amountStyle,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 5.dp),
            )
        }
        if (day.waiting > 0) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp).size(8.dp).background(colors.badge, CircleShape))
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

/** What TalkBack says for a cell: the date, then what is on that day. */
private fun describe(resources: android.content.res.Resources, day: DaySummary): String {
    val parts = ArrayList<String>()
    parts += day.date.format(FullDate)
    if (day.isToday) parts += resources.getString(R.string.cal_desc_today)
    if (day.hasExpenses) parts += resources.getString(R.string.cal_desc_expenses, Money.formatDisplay(day.expenses, withCurrency = false))
    if (day.hasIncome) parts += resources.getString(R.string.cal_desc_income, Money.formatDisplay(day.shownIncome, withCurrency = false))
    if (day.hasForecast) parts += resources.getString(R.string.cal_desc_balance, Money.formatDisplay(day.closing, withCurrency = false))
    if (day.negative) parts += resources.getString(R.string.cal_desc_short)
    if (day.waiting > 0) parts += resources.getQuantityString(R.plurals.cal_desc_waiting, day.waiting, day.waiting)
    return parts.joinToString(". ")
}
