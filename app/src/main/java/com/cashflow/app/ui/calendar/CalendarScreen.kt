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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.R
import com.cashflow.app.engine.CalendarGrid
import com.cashflow.app.engine.AppDates
import com.cashflow.app.engine.CalendarCellContent
import com.cashflow.app.engine.CalendarCellLayout
import com.cashflow.app.engine.CellLineKind
import com.cashflow.app.engine.CellMetrics
import com.cashflow.app.engine.CompactAmount
import com.cashflow.app.engine.DaySummary
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.MonthPages
import com.cashflow.app.engine.MonthSummary
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.home.EntryComposer
import com.cashflow.app.ui.home.HomeViewModel
import com.cashflow.app.ui.items.RejectConfirmDialog
import com.cashflow.app.ui.items.rememberItemActions
import com.cashflow.app.ui.rememberHaptics
import com.cashflow.app.ui.settings.SettingsViewModel
import com.cashflow.app.ui.theme.Palette
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

/** Cell highlights (today, selected, negative) are nearly square: 3 dp corners. */
private val CellShape = RoundedCornerShape(3.dp)

/** The today marker is a small rounded square, not a pill or a circle. */
private val TodayShape = RoundedCornerShape(4.dp)
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

    val sheetDay by calendar.openDay.collectAsStateWithLifecycle()
    val itemRequest by calendar.itemRequest.collectAsStateWithLifecycle()
    var composerKind by rememberSaveable { mutableStateOf<String?>(null) }
    val sheets = com.cashflow.app.ui.items.rememberItemSheetState()

    // The month can also change from outside (a Lab check, a link): the pager follows the view model.
    LaunchedEffect(visibleMonth) {
        val page = MonthPages.pageOf(visibleMonth)
        if (pager.currentPage != page && !pager.isScrollInProgress) pager.scrollToPage(page)
    }

    fun goToMonth(month: YearMonth) {
        scope.launch { pager.animateScrollToPage(MonthPages.pageOf(month)) }
    }

    fun select(day: LocalDate) {
        haptics.tick()
        calendar.select(day)
    }

    val density = LocalDensity.current
    val firstDay = AppDates.firstDayOfWeek
    val shownMonth = MonthPages.monthOf(pager.currentPage)
    val title = remember(shownMonth) { AppDates.monthYear(shownMonth) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cellWidthDp = ((maxWidth - GridPadding * 2) / CalendarGrid.DAYS_PER_WEEK - CellGap * 2).value
        val topPad = padding.calculateTopPadding() + 12.dp

        Column(
            Modifier.fillMaxSize().padding(top = topPad, bottom = padding.calculateBottomPadding() + 4.dp),
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
                    shape = CellShape,
                )
            }
            shortDay?.let { day -> ShortBanner(day) {
                haptics.tick()
                calendar.select(day)
                goToMonth(YearMonth.from(day))
            } }
            WeekdayHeader(firstDay)

            // The grid takes exactly the height left under the header. Every cell is the same: the day number and three
            // money lines. When the room is short the TEXT shrinks; only when even the smallest text cannot fit does the
            // grid area (not the header) scroll. The sizes are a pure function of this area's size and the text scale.
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val worst = CalendarCellLayout.metrics(maxHeight.value, CalendarGrid.FIXED_ROWS, density.fontScale)
                val gridHeight = if (worst.scrolls) (worst.rowHeightDp * CalendarGrid.FIXED_ROWS).dp else maxHeight
                val pagerModifier = if (worst.scrolls) Modifier.fillMaxWidth().height(gridHeight) else Modifier.fillMaxSize()
                Box(if (worst.scrolls) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pager,
                        modifier = pagerModifier,
                        key = { it },
                        verticalAlignment = Alignment.Top,
                    ) { page ->
                        MonthGrid(
                            summary = months[MonthPages.monthOf(page)],
                            selected = selected,
                            gridHeightDp = gridHeight.value,
                            cellWidthDp = cellWidthDp,
                            fontScale = density.fontScale,
                            onDayClick = { day ->
                                select(day.date)
                                calendar.openDayList(day.date)
                            },
                        )
                    }
                }
            }
        }
    }

    // ---- day sheet and what it opens --------------------------------------------------------
    val openDate = sheetDay
    // ONE sheet at a time: while an item (or its Change form) is open, the day list is not shown; dismissing the item shows it again.
    if (openDate != null && sheets.item == null && composerKind == null) {
        val summary = months.values.firstNotNullOfOrNull { it.day(openDate) }
        DaySheet(
            date = openDate,
            summary = summary,
            today = today,
            onDismiss = { calendar.openDayList(null) },
            onOpenItem = { sheets.open(it) },
            onAdd = { kind -> composerKind = kind.name },
        )
    }

    // An item asked for by name (Lab): once the day is known, its sheet replaces the day list.
    val request = itemRequest
    if (request != null) {
        val day = months.values.firstNotNullOfOrNull { it.day(request.date) }
        val wanted = day?.items?.firstOrNull { it.tx.description == request.name }
        if (wanted != null) {
            LaunchedEffect(request) {
                sheets.open(wanted.tx, move = request.move)
                calendar.requestItem(null)
            }
        }
    }

    composerKind?.let { name ->
        val ui = homeUi
        val day = sheetDay
        if (ui != null && day != null) {
            EntryComposer(
                kind = TxKind.valueOf(name),
                ui = ui,
                redDip = home::redDip,
                resultingBalance = home::resultingBalance,
                onSave = home::save,
                onSaved = { message -> actions.announce(message) },
                onDismiss = { composerKind = null },
                day = day,
            )
        }
    }

    com.cashflow.app.ui.items.ItemSheets(sheets, home, actions, today)
    RejectConfirmDialog(actions)
}

@Composable
private fun ShortBanner(day: LocalDate, onClick: () -> Unit) {
    val text = stringResource(
        R.string.calendar_runs_short,
        AppDates.formatShort(day, com.cashflow.app.engine.AppClock.today()).replace(' ', '\u00A0'), // "10 NOV" never breaks
    )
    val action = stringResource(R.string.calendar_runs_short_action)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { role = Role.Button; contentDescription = "$text. $action" },
        shape = CellShape,
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
                dow.getDisplayName(if (narrow) TextStyle.NARROW else TextStyle.SHORT, AppDates.LOCALE),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f).semantics { contentDescription = dow.getDisplayName(TextStyle.FULL, AppDates.LOCALE) },
            )
        }
    }
}

@Composable
private fun MonthGrid(
    summary: MonthSummary?,
    selected: LocalDate?,
    gridHeightDp: Float,
    cellWidthDp: Float,
    fontScale: Float,
    onDayClick: (DaySummary) -> Unit,
) {
    if (summary == null) {
        // Nothing numeric until this month has been computed.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.cal_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val rows = summary.weeks.size
    // Sizes come from the actual cell height (5 or 6 rows share the grid), as a pure function of it and the text scale.
    val metrics = CalendarCellLayout.metrics(gridHeightDp, rows, fontScale)
    val horizontalLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val verticalLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Column(
        Modifier.fillMaxSize()
            .padding(horizontal = GridPadding)
            .drawBehind {
                // Thick lines between the week rows, faint ones between the day columns.
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
        for (week in summary.weeks) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (day in week) {
                    DayCell(
                        day = day,
                        selected = day.inMonth && day.date == selected,
                        metrics = metrics,
                        cellWidthDp = cellWidthDp,
                        fontScale = fontScale,
                        onClick = onDayClick,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * One fixed structure at every size: the day number, then three line slots (expenses, one-time income, balance) that
 * always keep their space, so the balances line up across cells. Nothing is hidden; text shrinks to fit.
 */
@Composable
private fun DayCell(
    day: DaySummary,
    selected: Boolean,
    metrics: CellMetrics,
    cellWidthDp: Float,
    fontScale: Float,
    onClick: (DaySummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.cashflow
    // Day numbers are small and faint (the money is the content); only today's number is full contrast and bold.
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = Palette.DAY_NUMBER_ALPHA)
    val numberColor = if (day.inMonth) faint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val dayRow = with(LocalDensity.current) { metrics.dayRowDp.dp }
    val dayStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = metrics.daySp.sp, lineHeight = (metrics.daySp * CalendarCellLayout.LINE).sp)
    // Highlights are nearly square (no pills anywhere on the calendar).
    val shape = CellShape
    if (!day.inMonth) {
        // The neighbouring months' days only fill the grid; they are dimmed and not touchable.
        Box(modifier.padding(CellGap).clearAndSetSemantics {}, contentAlignment = Alignment.TopCenter) {
            Text(day.date.dayOfMonth.toString(), style = dayStyle, color = numberColor, modifier = Modifier.padding(top = 2.dp))
        }
        return
    }
    val content = remember(day) { CalendarCellContent.of(day) }
    val resources = LocalContext.current.resources
    val description = remember(day, resources) { describe(resources, day) }
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
        Column(Modifier.fillMaxSize().padding(top = (CalendarCellLayout.PAD_DP / 2).dp, bottom = (CalendarCellLayout.PAD_DP / 2).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // The day number at the top ...
            Box(Modifier.fillMaxWidth().height(dayRow), contentAlignment = Alignment.Center) {
                if (day.isToday) {
                    Box(
                        Modifier.defaultMinSize(minWidth = dayRow, minHeight = dayRow).clip(TodayShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            day.date.dayOfMonth.toString(),
                            style = dayStyle,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 3.dp),
                        )
                    }
                } else {
                    Text(day.date.dayOfMonth.toString(), style = dayStyle, fontWeight = FontWeight.Normal, color = numberColor)
                }
            }
            // ... then three EQUAL slots that fill the rest of the cell; each text is centred in its slot, so the
            // expenses sit in the upper part and the balance in the lower part.
            for ((index, line) in content.lines.withIndex()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (line != null) {
                        val color = when (line.kind) {
                            CellLineKind.EXPENSE -> colors.negative
                            CellLineKind.INCOME -> colors.cellIncome
                            CellLineKind.BALANCE -> if (day.negative) colors.negative else MaterialTheme.colorScheme.onSurface
                        }
                        val max = if (index == CalendarCellContent.BALANCE_SLOT) metrics.balanceSp else metrics.moneySp
                        val size = CalendarCellLayout.fitSp(line.text, cellWidthDp, max, fontScale)
                        Text(
                            line.text,
                            color = color,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = size.sp,
                                lineHeight = (size * CalendarCellLayout.LINE).sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        if (day.waiting > 0) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp).size(6.dp).background(colors.badge, TodayShape))
        }
    }
}

/** What TalkBack says for a cell: the date, then what is on that day. */
private fun describe(resources: android.content.res.Resources, day: DaySummary): String {
    val parts = ArrayList<String>()
    parts += AppDates.withWeekday(day.date)
    if (day.isToday) parts += resources.getString(R.string.cal_desc_today)
    if (day.hasExpenses) parts += resources.getString(R.string.cal_desc_expenses, Money.formatDisplay(day.expenses, withCurrency = false))
    if (day.hasIncome) parts += resources.getString(R.string.cal_desc_income, Money.formatDisplay(day.shownIncome, withCurrency = false))
    if (day.hasForecast) parts += resources.getString(R.string.cal_desc_balance, Money.formatDisplay(day.closing, withCurrency = false))
    if (day.negative) parts += resources.getString(R.string.cal_desc_short)
    if (day.waiting > 0) parts += resources.getQuantityString(R.plurals.cal_desc_waiting, day.waiting, day.waiting)
    return parts.joinToString(". ")
}
