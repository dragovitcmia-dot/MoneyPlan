package com.cashflow.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.R
import com.cashflow.app.engine.AppDates
import com.cashflow.app.engine.HistoryList
import com.cashflow.app.engine.HistoryTotals
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.SemanticColor
import com.cashflow.app.engine.TxKind
import com.cashflow.app.ui.components.AppCard
import com.cashflow.app.ui.components.AppListRow
import com.cashflow.app.ui.components.color
import com.cashflow.app.ui.home.HomeViewModel
import com.cashflow.app.ui.items.ItemSheets
import com.cashflow.app.ui.items.rememberItemActions
import com.cashflow.app.ui.items.rememberItemSheetState
import com.cashflow.app.ui.rememberHaptics
import com.cashflow.app.ui.theme.CappedFontScale
import com.cashflow.app.ui.theme.Spacing
import com.cashflow.app.ui.upcoming.FilterTabsRow

/**
 * The History tab: every CONFIRMED item (Now expenses and accepted series occurrences included), newest first, grouped by day with that day's
 * spent (red) and received (green) totals. A totals strip, the tabs of Upcoming, a search field; a row opens the standard item sheet, which
 * shows an accepted item as history (no actions). The list loads month windows as it scrolls.
 */
@Composable
fun HistoryScreen(
    history: HistoryViewModel,
    home: HomeViewModel,
    snackbar: SnackbarHostState,
    padding: PaddingValues,
    listState: LazyListState,
) {
    val ui by history.state.collectAsStateWithLifecycle()
    val totals by history.totals.collectAsStateWithLifecycle()
    val view by history.view.collectAsStateWithLifecycle()
    val today by history.today.collectAsStateWithLifecycle()
    val openFirst by history.openFirst.collectAsStateWithLifecycle()
    val sheetReset by history.sheetReset.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val actions = rememberItemActions(home, snackbar)
    val sheets = rememberItemSheetState()
    val current = ui

    // A check applied a new view by itself: any item sheet from the previous one closes; "open the first row" opens its sheet.
    LaunchedEffect(sheetReset) { sheets.close() }
    LaunchedEffect(openFirst, current) {
        if (openFirst && current != null && current.rows.isNotEmpty()) {
            sheets.open(current.rows.first())
            history.clearOpenFirst()
        }
    }

    // The next month window loads when the end of the list comes into view.
    val canLoadMore by rememberUpdatedState(current?.canLoadMore == true)
    val total by rememberUpdatedState(current?.entries?.size ?: 0)
    LaunchedEffect(listState) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to listState.layoutInfo.totalItemsCount }.collect { (last, count) ->
            if (canLoadMore && count > 0 && last >= count - 8) history.loadMore()
        }
    }

    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(view.query) }
    LaunchedEffect(view.query) { if (text != view.query) text = view.query } // a check can apply a search by itself
    val searchHint = stringResource(R.string.history_search_hint)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.l,
            end = Spacing.l,
            top = padding.calculateTopPadding() + Spacing.l,
            bottom = padding.calculateBottomPadding() + Spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item(key = "title", contentType = "title") {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        }
        item(key = "totals", contentType = "totals") { TotalsStrip(totals, Modifier.padding(vertical = Spacing.s)) }
        item(key = "tabs", contentType = "tabs") {
            CappedFontScale { FilterTabsRow(view.tab, onType = { haptics.tick(); history.setTab(it) }) }
        }
        item(key = "search", contentType = "search") {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; history.setQuery(it) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                singleLine = true,
                placeholder = { Text(searchHint) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = ""; history.setQuery(""); focus.clearFocus() }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.history_search_clear))
                        }
                    }
                },
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            )
        }
        current?.resultsLine?.let { line ->
            item(key = "results", contentType = "results") {
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
            }
        }
        if (current != null && current.empty) {
            item(key = "empty", contentType = "empty") {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                )
            }
        } else if (current != null) {
            items(
                current.entries,
                key = { entry -> when (entry) { is HistoryList.Entry.Header -> "h-" + entry.day.date.toEpochDay(); is HistoryList.Entry.Row -> "r-" + entry.tx.id } },
                contentType = { entry -> if (entry is HistoryList.Entry.Header) "header" else "row" },
            ) { entry ->
                when (entry) {
                    is HistoryList.Entry.Header -> DayHeader(entry.day)
                    is HistoryList.Entry.Row -> {
                        val tx = entry.tx
                        val income = tx.kind == TxKind.INCOME
                        AppListRow(
                            title = tx.description.ifBlank { stringResource(if (income) R.string.item_income else R.string.item_expense) },
                            kind = tx.kind,
                            repeating = tx.recurringRuleId != null,
                            amountText = Money.formatSigned(tx.kind, tx.amount),
                            onClick = { sheets.open(tx) },
                        )
                    }
                }
            }
        }
    }

    ItemSheets(sheets, home, actions, today)
}

/** "TUE 20 OCT 2026" with that day's spent (red) and received (green) totals. */
@Composable
private fun DayHeader(day: com.cashflow.app.engine.HistoryDay) {
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.m).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(AppDates.withWeekday(day.date), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            if (day.spent > 0) {
                Text(Money.formatSigned(TxKind.EXPENSE, day.spent), style = MaterialTheme.typography.labelLarge, color = SemanticColor.ofKind(TxKind.EXPENSE).color())
            }
            if (day.received > 0) {
                Text(Money.formatSigned(TxKind.INCOME, day.received), style = MaterialTheme.typography.labelLarge, color = SemanticColor.ofKind(TxKind.INCOME).color())
            }
        }
    }
}

/** Spent this week, spent this month, received this month: confirmed items only, not affected by the tab or the search. */
@Composable
private fun TotalsStrip(totals: HistoryTotals?, modifier: Modifier = Modifier) {
    val big = LocalDensity.current.fontScale > 1.3f
    val cells = listOf(
        Triple(R.string.history_spent_week, totals?.spentThisWeek, TxKind.EXPENSE),
        Triple(R.string.history_spent_month, totals?.spentThisMonth, TxKind.EXPENSE),
        Triple(R.string.history_received_month, totals?.receivedThisMonth, TxKind.INCOME),
    )
    AppCard(modifier.fillMaxWidth()) {
        if (big) {
            Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                for ((label, value, kind) in cells) Cell(label, value, kind, Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.padding(Spacing.l), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                for ((label, value, kind) in cells) Cell(label, value, kind, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Cell(label: Int, value: Long?, kind: TxKind, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        Text(
            if (value == null) "–" else Money.formatDisplay(value),
            style = MaterialTheme.typography.titleMedium,
            color = if (value == null || value == 0L) MaterialTheme.colorScheme.onSurface else SemanticColor.ofKind(kind).color(),
            maxLines = 1,
        )
    }
}
