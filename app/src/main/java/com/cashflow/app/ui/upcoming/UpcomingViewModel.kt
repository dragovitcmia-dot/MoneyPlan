package com.cashflow.app.ui.upcoming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cashflow.app.LedgerSnapshots
import com.cashflow.app.engine.AppDates
import com.cashflow.app.engine.SeriesContext
import com.cashflow.app.engine.SeriesList
import com.cashflow.app.engine.SeriesRow
import com.cashflow.app.engine.Upcoming
import com.cashflow.app.engine.UpcomingType
import com.cashflow.app.engine.UpcomingView
import com.cashflow.app.engine.UpcomingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

class UpcomingViewModel(private val snapshots: LedgerSnapshots, flags: StateFlow<SeriesContext>) : ViewModel() {
    // The tab (All, Expenses, Income) and the series toggle; the tab filters both the occurrences and the series list.
    private val _view = MutableStateFlow(UpcomingView())
    val view: StateFlow<UpcomingView> = _view

    val today: StateFlow<LocalDate> = snapshots.today

    /** Null until the first data emission. Built off the main thread. */
    val model: StateFlow<UpcomingModel?> = combine(snapshots.snapshot.filterNotNull(), _view) { snap, view ->
        Upcoming.build(snap.engine, snap.rules, view.type, AppDates.firstDayOfWeek)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The Repeating list: one row per active series (filtered by type), soonest next occurrence first. */
    val series: StateFlow<List<SeriesRow>> = combine(snapshots.snapshot.filterNotNull(), flags, _view) { snap, ctx, view ->
        SeriesList.build(snap.rules, snap.engine.transactions, ctx, snap.engine.today, view.type)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setType(type: UpcomingType) {
        _view.value = _view.value.withType(type)
    }

    fun toggleSeries() {
        _view.value = _view.value.toggleSeries()
    }

    /** Back to the plain occurrence list on the All tab, or on to the Repeating list (a Lab check or a link sets it by itself). */
    fun show(series: Boolean) {
        _view.value = UpcomingView(UpcomingType.ALL, series)
    }

    fun showSeries() {
        _view.value = _view.value.withSeries(true)
    }

    fun refreshDay() = snapshots.refreshDay()
}
