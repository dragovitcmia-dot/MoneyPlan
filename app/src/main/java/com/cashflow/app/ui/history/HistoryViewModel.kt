package com.cashflow.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cashflow.app.LedgerSnapshots
import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.engine.HistoryList
import com.cashflow.app.engine.HistoryTotals
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.UpcomingType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/** What the History list shows right now. */
data class HistoryUi(
    val entries: List<HistoryList.Entry>,
    /** The rows after the tab and the search, newest first (the first one is what a Lab check opens). */
    val rows: List<Transaction>,
    /** "N results, total X TND" while a search is active, otherwise null. */
    val resultsLine: String?,
    /** More month windows are available: the list loads one when its end comes into view. */
    val canLoadMore: Boolean,
    /** Nothing confirmed at all (or nothing for this tab): the one-line empty state. */
    val empty: Boolean,
)

data class HistoryView(val tab: UpcomingType = UpcomingType.ALL, val query: String = "")

/**
 * State of the History tab. It reads CONFIRMED rows by month windows (two months first, one more each time the end of the list is reached),
 * or, while a search is active, only the rows whose name matches: never the whole table. The totals strip has its own small query (this
 * week and this month). All grouping, filtering and totals are the pure [HistoryList] functions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val repository: LedgerRepository, snapshots: LedgerSnapshots) : ViewModel() {
    private val _view = MutableStateFlow(HistoryView())
    val view: StateFlow<HistoryView> = _view

    private val months = MutableStateFlow(HistoryList.INITIAL_MONTHS)
    val today: StateFlow<LocalDate> = snapshots.today

    /** A Lab check asks for the first row's sheet: the screen opens it once the rows are there, then clears this. */
    private val _openFirst = MutableStateFlow(false)
    val openFirst: StateFlow<Boolean> = _openFirst
    fun clearOpenFirst() { _openFirst.value = false }

    /** Bumped when a check applies a new view by itself: the screen closes the item sheet a previous check left open. */
    private val _sheetReset = MutableStateFlow(0)
    val sheetReset: StateFlow<Int> = _sheetReset

    fun setTab(tab: UpcomingType) { _view.value = _view.value.copy(tab = tab) }

    fun setQuery(query: String) {
        _view.value = _view.value.copy(query = query)
        months.value = HistoryList.INITIAL_MONTHS
    }

    /** Applies a tab and a search by itself (a Lab check or a link). */
    fun show(tab: UpcomingType, query: String, openFirst: Boolean) {
        _view.value = HistoryView(tab, query)
        months.value = HistoryList.INITIAL_MONTHS
        _openFirst.value = openFirst
        _sheetReset.value = _sheetReset.value + 1
    }

    fun loadMore() { months.value = months.value + 1 }

    private val source: Flow<List<Transaction>> =
        combine(_view.map { it.query.trim() }.distinctUntilChanged(), today, months) { q, t, m -> Triple(q, t, m) }
            .flatMapLatest { (q, t, m) ->
                if (q.isEmpty()) repository.confirmedFrom(HistoryList.windowFrom(t, m)) else repository.confirmedMatching(q)
            }

    /** Null until the first emission. Everything runs off the main thread. */
    val state: StateFlow<HistoryUi?> = combine(source, _view, today, repository.earliestConfirmed, months) { rows, view, day, earliest, m ->
        val shown = HistoryList.filter(rows, view.tab, view.query)
        val searching = view.query.isNotBlank()
        HistoryUi(
            entries = HistoryList.entries(HistoryList.groupByDay(shown)),
            rows = shown,
            resultsLine = if (searching) HistoryList.resultsLine(shown) else null,
            canLoadMore = !searching && !HistoryList.exhausted(HistoryList.windowFrom(day, m), earliest),
            empty = shown.isEmpty(),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The totals strip: this week and this month, confirmed items only, from the clock's day; not affected by the tab or the search. */
    val totals: StateFlow<HistoryTotals?> = today.flatMapLatest { day ->
        repository.confirmedFrom(HistoryList.totalsFrom(day)).map { HistoryList.totals(it, day) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
