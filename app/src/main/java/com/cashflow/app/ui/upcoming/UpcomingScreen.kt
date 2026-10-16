package com.cashflow.app.ui.upcoming

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import com.cashflow.app.engine.UpcomingView
import com.cashflow.app.engine.UpcomingTap
import com.cashflow.app.engine.UpcomingType
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.unit.sp
import com.cashflow.app.engine.UpcomingGroupKey
import com.cashflow.app.engine.UpcomingRow
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.AppCard
import com.cashflow.app.ui.components.AppListRow
import com.cashflow.app.ui.components.color
import com.cashflow.app.ui.components.SectionHeader
import com.cashflow.app.ui.theme.Motion
import com.cashflow.app.ui.theme.Spacing
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
    val view by upcoming.view.collectAsStateWithLifecycle()
    val today by upcoming.today.collectAsStateWithLifecycle()
    val showHint by home.swipeHintVisible.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { upcoming.refreshDay() }

    val haptics = rememberHaptics()
    val actions = rememberItemActions(home, snackbar)
    var editing by remember { mutableStateOf<Transaction?>(null) }
    // "Add one" on the empty series list opens the add wizard on Repeating (an income when the Income tab is selected).
    var adding by remember { mutableStateOf<TxKind?>(null) }
    val homeState by home.state.collectAsStateWithLifecycle()
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
            start = Spacing.l,
            end = Spacing.l,
            top = padding.calculateTopPadding() + Spacing.l,
            bottom = padding.calculateBottomPadding() + Spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        item(key = "title", contentType = "title") {
            Text(stringResource(R.string.upcoming_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        }
        item(key = "filters", contentType = "filters") {
            // One row: three equal tabs (All, Expenses, Income) and, at the end, the toggle between occurrences and the series list.
            UpcomingTabs(
                view = view,
                onType = { haptics.tick(); upcoming.setType(it) },
                onToggleSeries = { haptics.tick(); upcoming.toggleSeries() },
            )
        }

        if (view.showSeries) {
            if (seriesRows.isEmpty()) {
                item(key = "series-empty", contentType = "empty") {
                    Column(Modifier.fillMaxWidth().padding(top = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        Text(
                            stringResource(R.string.series_list_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AppButton(
                            stringResource(R.string.series_list_add_one),
                            onClick = { haptics.tick(); adding = if (view.type == UpcomingType.INCOME) TxKind.INCOME else TxKind.EXPENSE },
                            modifier = Modifier.fillMaxWidth(),
                            style = AppButtonStyle.Outlined,
                        )
                    }
                }
            }
            items(seriesRows, key = { "series-" + it.rule.id }, contentType = { "series" }) { row ->
                SeriesRowCard(row, onClick = { openSeries = row.rule.id }, modifier = Modifier.animateItem(fadeInSpec = Motion.standard(), placementSpec = Motion.standard(), fadeOutSpec = Motion.fast()))
            }
        } else if (current != null) {
            current.dailyIncome?.let { total ->
                item(key = "daily", contentType = "daily") {
                    DailyIncomeRow(total) {
                        // Every row opens something: the series of a single daily income, or the Repeating list when there are several.
                        when (val tap = Upcoming.tapForDaily(current)) {
                            is UpcomingTap.OpenSeries -> openSeries = tap.ruleId
                            UpcomingTap.ShowRepeating -> upcoming.showSeries()
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
                    SectionHeader(groupTitle(group.key))
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
                            modifier = Modifier.animateItem(fadeInSpec = Motion.standard(), placementSpec = Motion.standard(), fadeOutSpec = Motion.fast()),
                            footer = if (row.shortBy != null || row.last) ({ RowTags(row) }) else null,
                        )
                    } else {
                        SwipeToDelete(
                            haptics = haptics,
                            onDelete = { deleting = row },
                            modifier = Modifier.animateItem(fadeInSpec = Motion.standard(), placementSpec = Motion.standard(), fadeOutSpec = Motion.fast()),
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
    adding?.let { kind ->
        val ui = homeState
        if (ui != null) {
            com.cashflow.app.ui.home.EntryComposer(
                kind = kind,
                ui = ui,
                redDip = home::redDip,
                resultingBalance = home::resultingBalance,
                onSave = home::save,
                onSaved = { message -> actions.announce(message) },
                onDismiss = { adding = null },
                startRepeating = true,
            )
        }
    }
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
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(if (active) MaterialTheme.cashflow.expenseFill else Color.Transparent).padding(horizontal = Spacing.xl),
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
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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
    AppListRow(
        title = tx.description.ifBlank { stringResource(if (income) R.string.item_income else R.string.item_expense) },
        kind = tx.kind,
        repeating = tx.recurringRuleId != null,
        amountText = Money.formatSigned(tx.kind, tx.amount),
        caption = AppDates.withWeekday(tx.date) + " \u00B7 " + quantityString(R.plurals.in_days, row.daysAway, row.daysAway),
        onClick = onClick,
        modifier = modifier,
        footer = if (row.shortBy != null || row.last) ({ RowTags(row) }) else null,
    )
}

/** "Repeats" with its icon, and the red "Short by X TND" tag when that day closes below zero. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowTags(row: UpcomingRow) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (row.last) LastTag()
        row.shortBy?.let { short ->
            Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                Text(
                    stringResource(R.string.short_by_tag, Money.formatDisplay(short, withCurrency = false)),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                )
            }
        }
    }
}

@Composable
private fun DailyIncomeRow(total: Long, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
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
        Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
        Text(stringResource(R.string.upcoming_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The small "Last" tag on the final occurrence of a series that ends. */
@Composable
internal fun LastTag() {
    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Text(stringResource(R.string.tag_last), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s))
    }
}

/** One series of the Repeating list: name, signed amount, schedule line, end line and the "Ended" tag. */
@Composable
private fun SeriesRowCard(row: com.cashflow.app.engine.SeriesRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val rule = row.rule
    val income = rule.kind == TxKind.INCOME
    AppListRow(
        title = rule.description.ifBlank { stringResource(if (income) R.string.item_income else R.string.item_expense) },
        kind = rule.kind,
        repeating = false,
        amountText = Money.formatSigned(rule.kind, rule.amount),
        caption = row.schedule + "\n" + row.endLine,
        onClick = onClick,
        modifier = modifier,
        footer = if (row.ended) ({
            Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                Text(stringResource(R.string.series_tag_ended), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs))
            }
        }) else null,
    )
}

/** One group of filter chips in a row: 32 dp look, 48 dp touch area, 13 sp text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpcomingTabs(view: UpcomingView, onType: (UpcomingType) -> Unit, onToggleSeries: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).selectableGroup()) {
            for ((type, label) in listOf(
                UpcomingType.ALL to R.string.filter_all,
                UpcomingType.EXPENSES to R.string.filter_expenses,
                UpcomingType.INCOME to R.string.filter_income,
            )) {
                val selected = view.type == type
                // All is neutral, Expenses red, Income green (selected text and underline)
                val token = com.cashflow.app.engine.SemanticColor.ofTab(type)
                val tabColor = if (token == com.cashflow.app.engine.ColorToken.NEUTRAL) primary else token.color()
                Box(
                    Modifier.weight(1f).height(48.dp).selectable(selected = selected, onClick = { onType(type) }, role = Role.Tab),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) tabColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selected) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                                .background(tabColor, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                        )
                    }
                }
            }
        }
        IconToggleButton(
            checked = view.showSeries,
            onCheckedChange = { onToggleSeries() },
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.iconToggleButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = primary,
                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.filter_repeating))
        }
    }
}
