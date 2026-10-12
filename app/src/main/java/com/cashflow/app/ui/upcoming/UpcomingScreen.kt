package com.cashflow.app.ui.upcoming

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
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import com.cashflow.app.engine.UpcomingFilter
import com.cashflow.app.engine.UpcomingGroupKey
import com.cashflow.app.engine.UpcomingRow
import com.cashflow.app.ui.components.AppCard
import com.cashflow.app.ui.components.quantityString
import com.cashflow.app.ui.home.HomeViewModel
import com.cashflow.app.ui.items.ItemEditSheet
import com.cashflow.app.ui.items.RuleEditorHost
import com.cashflow.app.ui.items.WaitingCard
import com.cashflow.app.ui.items.rememberItemActions
import com.cashflow.app.ui.rememberHaptics
import com.cashflow.app.ui.settings.SettingsViewModel
import com.cashflow.app.ui.theme.cashflow
import java.time.format.DateTimeFormatter
import java.util.Locale

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
) {
    val model by upcoming.model.collectAsStateWithLifecycle()
    val filter by upcoming.filter.collectAsStateWithLifecycle()
    val showHint by home.swipeHintVisible.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { upcoming.refreshDay() }

    val haptics = rememberHaptics()
    val actions = rememberItemActions(home, snackbar)
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var editingSeries by remember { mutableStateOf<Long?>(null) }
    val current = model

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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in listOf(
                    UpcomingFilter.ALL to R.string.filter_all,
                    UpcomingFilter.EXPENSES to R.string.filter_expenses,
                    UpcomingFilter.INCOME to R.string.filter_income,
                )) {
                    FilterChip(
                        selected = filter == value,
                        onClick = { haptics.tick(); upcoming.setFilter(value) },
                        label = { Text(stringResource(label), style = MaterialTheme.typography.titleSmall) },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                }
            }
        }

        if (current != null) {
            current.dailyIncome?.let { total ->
                item(key = "daily", contentType = "daily") { DailyIncomeRow(total) }
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
                                actions.confirm(row.tx)
                            },
                            onSkip = { fromSwipe ->
                                if (fromSwipe) home.onSwipeCompleted()
                                actions.skip(row.tx)
                            },
                            onOpen = { editing = row.tx },
                            modifier = Modifier.animateItem(),
                            footer = if (row.repeating || row.shortBy != null) ({ RowTags(row) }) else null,
                        )
                    } else {
                        UpcomingCard(row, onClick = { editing = row.tx }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }

    editing?.let { tx ->
        ItemEditSheet(
            tx = tx,
            actions = actions,
            onDismiss = { editing = null },
            onEditSeries = { editingSeries = tx.recurringRuleId },
        )
    }
    editingSeries?.let { ruleId ->
        RuleEditorHost(ruleId = ruleId, settings = settings, onDismiss = { editingSeries = null })
    }
}

private val RowDate: DateTimeFormatter get() = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private val MonthTitle: DateTimeFormatter get() = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())

@Composable
private fun groupTitle(key: UpcomingGroupKey): String = when (key) {
    UpcomingGroupKey.Waiting -> stringResource(R.string.waiting_header)
    UpcomingGroupKey.Tomorrow -> stringResource(R.string.tomorrow)
    UpcomingGroupKey.ThisWeek -> stringResource(R.string.group_this_week)
    is UpcomingGroupKey.Month -> key.month.format(MonthTitle)
}

@Composable
private fun UpcomingCard(row: UpcomingRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tx = row.tx
    val income = tx.kind == TxKind.INCOME
    AppCard(modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        tx.description.ifBlank { stringResource(if (income) R.string.item_income else R.string.item_expense) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        tx.date.format(RowDate) + " · " + quantityString(R.plurals.in_days, row.daysAway, row.daysAway),
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
            if (row.repeating || row.shortBy != null) RowTags(row)
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
        if (row.repeating) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.repeats_label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
private fun DailyIncomeRow(total: Long) {
    AppCard(Modifier.fillMaxWidth()) {
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
