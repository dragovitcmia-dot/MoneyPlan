package com.cashflow.app.ui.upcoming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cashflow.app.LedgerSnapshots
import com.cashflow.app.engine.AppDates
import com.cashflow.app.engine.SeriesContext
import com.cashflow.app.engine.SeriesList
import com.cashflow.app.engine.SeriesRow
import com.cashflow.app.engine.Upcoming
import com.cashflow.app.engine.UpcomingKind
import com.cashflow.app.engine.UpcomingType
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
    // Two independent groups that combine: the type (All, Expenses, Income) and the kind (All, Repeating, One-time).
    private val _type = MutableStateFlow(UpcomingType.ALL)
    val type: StateFlow<UpcomingType> = _type
    private val _kind = MutableStateFlow(UpcomingKind.ALL)
    val kind: StateFlow<UpcomingKind> = _kind

    val today: StateFlow<LocalDate> = snapshots.today

    /** Null until the first data emission. Built off the main thread. */
    val model: StateFlow<UpcomingModel?> = combine(snapshots.snapshot.filterNotNull(), _type, _kind) { snap, type, kind ->
        Upcoming.build(snap.engine, snap.rules, type, kind, AppDates.firstDayOfWeek)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The Repeating list: one row per active series (filtered by type), soonest next occurrence first. */
    val series: StateFlow<List<SeriesRow>> = combine(snapshots.snapshot.filterNotNull(), flags, _type) { snap, ctx, type ->
        SeriesList.build(snap.rules, snap.engine.transactions, ctx, snap.engine.today, type)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setType(type: UpcomingType) {
        _type.value = type
    }

    fun setKind(kind: UpcomingKind) {
        _kind.value = kind
    }

    fun refreshDay() = snapshots.refreshDay()
}
