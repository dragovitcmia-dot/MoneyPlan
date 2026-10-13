package com.cashflow.app.ui.upcoming

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import com.cashflow.app.ui.Haptics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.R
import com.cashflow.app.ui.components.selectedChipColors
import com.cashflow.app.engine.AppDates
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import com.cashflow.app.engine.Upcoming
import com.cashflow.app.engine.UpcomingKind
import com.cashflow.app.engine.UpcomingTap
import com.cashflow.app.engine.UpcomingType
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.unit.sp
import com.cashflow.app.engine.UpcomingGroupKey
import com.cashflow.app.engine.UpcomingRow
import com.cashflow.app.ui.components.AppCard
import com.cashflow.app.ui.components.NameWithRepeat
import com.cashflow.app.ui.components.quantityString
import com.cashflow.app.ui.home.HomeViewModel
import com.cashflow.app.ui.items.ItemEditSheet
import com.cashflow.app.ui.items.ItemDetails
import com.cashflow.app.ui.items.RejectConfirmDialog
import com.cashflow.app.ui.items.WaitingCard
import com.cashflow.app.ui.items.rememberItemActions
import com.cashflow.app.ui.rememberHaptics
import com.cashflow.app.ui.settings.SettingsViewModel
import com.cashflow.app.ui.theme.cashflow

/** The Upcoming tab: what is waiting and what is coming in the next 120 days, grouped by when. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpcomingScreen(
    upcoming: UpcomingViewModel,
    home: HomeViewModel,
    settings: SettingsViewModel,
    snackbar: SnackbarHostState,
    padding: PaddingValues,
    listState: LazyListState,
    /** A tapped notification about one waiting item of an "ask me the amount" series: its amount sheet opens. */
    openItemId: Long? = null,
    onOpenItemHandled: () -> Unit = {},
) {
    val model by upcoming.model.collectAsStateWithLifecycle()
    val seriesRows by upcoming.series.collectAsStateWithLifecycle()
    val seriesCtx by home.series.collectAsStateWithLifecycle()
    var openSeries by remember { mutableStateOf<Long?>(null) }
    val type by upcoming.type.collectAsStateWithLifecycle()
    val kind by upcoming.kind.collectAsStateWithLifecycle()
    val today by upcoming.today.collectAsStateWithLifecycle()
    val showHint by home.swipeHintVisible.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { upcoming.refreshDay() }

    val haptics = rememberHaptics()
    val actions = rememberItemActions(home, snackbar)
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var changing by remember { mutableStateOf<Transaction?>(null) }
    var deleting by remember { mutableStateOf<UpcomingRow?>(null) }
    val rules by home.rules.collectAsStateWithLifecycle()
    val current = model
    LaunchedEffect(openItemId, current) {
        if (openItemId != null && current != null) {
            current.groups.flatMap { it.rows }.firstOrNull { it.tx.id == openItemId }?.let { editing = it.tx }
            onOpenItemHandled()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = padding.calculateTopPadding() + 16.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title", contentType = "title") {
            Text(stringResource(R.string.upcoming_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        }
        item(key = "filters", contentType = "filters") {
            // Two slim rows: the type, then the kind. Chips look 32 dp tall with a 48 dp touch area and 13 sp text.
            Column(Modifier.fillMaxWidth()) {
                FilterRow(
                    selected = type,
                    options = listOf(
                        UpcomingType.ALL to R.string.filter_all,
                        UpcomingType.EXPENSES to R.string.filter_expenses,
                        UpcomingType.INCOME to R.string.filter_income,
                    ),
                    onSelect = { haptics.tick(); upcoming.setType(it) },
                )
                FilterRow(
                    selected = kind,
                    options = listOf(
                        UpcomingKind.ALL to R.string.filter_all,
                        UpcomingKind.REPEATING to R.string.filter_repeating,
                        UpcomingKind.ONE_TIME to R.string.filter_one_time,
                    ),
                    onSelect = { haptics.tick(); upcoming.setKind(it) },
                )
            }
        }

        if (kind == UpcomingKind.REPEATING) {
            if (seriesRows.isEmpty()) {
                item(key = "series-empty", contentType = "empty") {
                    Text(
                        stringResource(R.string.series_list_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            items(seriesRows, key = { "series-" + it.rule.id }, contentType = { "series" }) { row ->
                SeriesRowCard(row, onClick = { openSeries = row.rule.id }, modifier = Modifier.animateItem())
            }
        } else if (current != null) {
            current.dailyIncome?.let { total ->
                item(key = "daily", contentType = "daily") {
                    DailyIncomeRow(total) {
                        // Every row opens something: the series of a single daily income, or the Repeating list when there are several.
                        when (val tap = Upcoming.tapForDaily(current)) {
                            is UpcomingTap.OpenSeries -> openSeries = tap.ruleId
                            UpcomingTap.ShowRepeating -> upcoming.setKind(UpcomingKind.REPEATING)
                            else -> Unit
                        }
                    }
                }
            }
            if (current.groups.isEmpty()) {
                item(key = "empty", contentType = "empty") { NothingComing() }
            }
            for (group in current.groups) {
                item(key = "header-" + group.key.toString(), contentType = "header") {
                    Text(
                        groupTitle(group.key),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                    )
                }
                items(group.rows, key = { it.tx.id }, contentType = { if (it.waiting) "waiting" else "row" }) { row ->
                    if (row.waiting) {
                        WaitingCard(
                            tx = row.tx,
                            showHint = showHint,
                            haptics = haptics,
                            onConfirm = { fromSwipe ->
                                if (fromSwipe) home.onSwipeCompleted()
                                // A series that asks for the amount each time opens the amount sheet instead of accepting.
                                if (actions.asksAmount(row.tx, seriesCtx)) editing = row.tx else actions.confirm(row.tx)
                            },
                            onSkip = { fromSwipe ->
                                if (fromSwipe) home.onSwipeCompleted()
                                actions.requestReject(row.tx)
                            },
                            onOpen = { editing = row.tx },
                            modifier = Modifier.animateItem(),
                            footer = if (row.shortBy != null || row.last) ({ RowTags(row) }) else null,
                        )
                    } else {
                        SwipeToDelete(
                            haptics = haptics,
                            onDelete = { deleting = row },
                            modifier = Modifier.animateItem(),
                        ) { UpcomingCard(row, onClick = { editing = row.tx }) }
                    }
                }
            }
        }
    }

    editing?.let { tx ->
        ItemDetails(
            tx = tx,
            home = home,
            actions = actions,
            today = today,
            onDismiss = { editing = null },
            onEdit = { changing = tx },
        )
    }
    changing?.let { tx ->
        ItemEditSheet(tx = tx, actions = actions, onDismiss = { changing = null }, today = today)
    }
    RejectConfirmDialog(actions)
    openSeries?.let { id ->
        com.cashflow.app.ui.items.SeriesSheet(id, home, actions, today, onDismiss = { openSeries = null })
    }

    deleting?.let { row ->
        val tx = row.tx
        // A repeating item offers "this one" and "this and all future"; anything else is a plain confirmation.
        val inSeries = tx.recurringRuleId != null && rules.any { it.id == tx.recurringRuleId }
        DeleteConfirmDialog(
            inSeries = inSeries,
            onThisOne = { deleting = null; actions.delete(tx) },
            onThisAndFuture = { deleting = null; actions.deleteThisAndFuture(tx) },
            onCancel = { deleting = null },
        )
    }
}

/** Swipe left to delete: the row snaps back at once and only the confirmation dialog deletes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(haptics: Haptics, onDelete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            false
        },
        positionalThreshold = { total -> total * 0.35f },
    )
    LaunchedEffect(state.targetValue) {
        if (state.targetValue != SwipeToDismissBoxValue.Settled) haptics.tick()
    }
    val deleteLabel = stringResource(R.string.swipe_delete_action)
    SwipeToDismissBox(
        state = state,
        modifier = modifier.semantics { customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true }) },
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val active = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(if (active) MaterialTheme.cashflow.expenseFill else Color.Transparent).padding(horizontal = 28.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (active) Icon(Icons.Filled.Delete, contentDescription = deleteLabel, tint = MaterialTheme.cashflow.buttonLabel, modifier = Modifier.size(32.dp))
            }
        },
    ) { content() }
}

@Composable
private fun DeleteConfirmDialog(inSeries: Boolean, onThisOne: () -> Unit, onThisAndFuture: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(if (inSeries) R.string.swipe_delete_series_title else R.string.swipe_delete_title)) },
        text = {
            if (inSeries) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onThisOne, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.swipe_delete_this_one)) }
                    TextButton(onClick = onThisAndFuture, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.swipe_delete_future)) }
                }
            } else {
                Text(stringResource(R.string.swipe_delete_body))
            }
        },
        confirmButton = {
            if (!inSeries) TextButton(onClick = onThisOne, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.swipe_delete_action)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}


@Composable
private fun groupTitle(key: UpcomingGroupKey): String = when (key) {
    UpcomingGroupKey.Waiting -> stringResource(R.string.waiting_header)
    UpcomingGroupKey.Tomorrow -> stringResource(R.string.tomorrow)
    UpcomingGroupKey.ThisWeek -> stringResource(R.string.group_this_week)
    is UpcomingGroupKey.Month -> AppDates.monthYear(key.month)
}

@Composable
private fun UpcomingCard(row: UpcomingRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tx = row.tx
    val income = tx.kind == TxKind.INCOME
    AppCard(modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    NameWithRepeat(
                        tx.description.ifBlank { stringResource(if (income) R.string.item_income else R.string.item_expense) },
                        repeating = tx.recurringRuleId != null,
                        income = income,
                    )
                    Text(
                        AppDates.withWeekday(tx.date) + " · " + quantityString(R.plurals.in_days, row.daysAway, row.daysAway),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    Money.formatSigned(tx.kind, tx.amount),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (income) MaterialTheme.cashflow.positive else MaterialTheme.cashflow.negative,
                )
            }
            if (row.shortBy != null || row.last) RowTags(row)
        }
    }
}

/** "Repeats" with its icon, and the red "Short by X TND" tag when that day closes below zero. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowTags(row: UpcomingRow) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (row.last) LastTag()
        row.shortBy?.let { short ->
            Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                Text(
                    stringResource(R.string.short_by_tag, Money.formatDisplay(short, withCurrency = false)),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DailyIncomeRow(total: Long, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.cashflow.positive)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.daily_income_row, Money.formatDisplay(total, withCurrency = false)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.cashflow.positive,
                )
                Text(
                    stringResource(R.string.daily_income_row_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NothingComing() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
        Text(stringResource(R.string.upcoming_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The small "Last" tag on the final occurrence of a series that ends. */
@Composable
internal fun LastTag() {
    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Text(stringResource(R.string.tag_last), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** One series of the Repeating list: name, signed amount, schedule line, end line and the "Ended" tag. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesRowCard(row: com.cashflow.app.engine.SeriesRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val rule = row.rule
    val income = rule.kind == TxKind.INCOME
    AppCard(modifier.fillMaxWidth().heightIn(min = 72.dp), onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Text(
                    rule.description.ifBlank { stringResource(if (income) R.string.item_income else R.string.item_expense) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Text(
                    Money.formatSigned(rule.kind, rule.amount),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (income) MaterialTheme.cashflow.positive else MaterialTheme.cashflow.negative,
                )
            }
            Text(row.schedule, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.endLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (row.ended) {
                    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                        Text(stringResource(R.string.series_tag_ended), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}

/** One group of filter chips in a row: 32 dp look, 48 dp touch area, 13 sp text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> FilterRow(selected: T, options: List<Pair<T, Int>>, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((value, label) in options) {
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(stringResource(label), fontSize = 13.sp) },
                modifier = Modifier.minimumInteractiveComponentSize(),
                colors = selectedChipColors(),
            )
        }
    }
}
