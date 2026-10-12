package com.cashflow.app.ui.upcoming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cashflow.app.LedgerSnapshots
import com.cashflow.app.engine.AppDates
import com.cashflow.app.engine.Upcoming
import com.cashflow.app.engine.UpcomingFilter
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

class UpcomingViewModel(private val snapshots: LedgerSnapshots) : ViewModel() {
    private val _filter = MutableStateFlow(UpcomingFilter.ALL)
    val filter: StateFlow<UpcomingFilter> = _filter

    val today: StateFlow<LocalDate> = snapshots.today

    /** Null until the first data emission. Built off the main thread. */
    val model: StateFlow<UpcomingModel?> = combine(snapshots.snapshot.filterNotNull(), _filter) { snap, filter ->
        Upcoming.build(snap.engine, snap.rules, filter, AppDates.firstDayOfWeek)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFilter(filter: UpcomingFilter) {
        _filter.value = filter
    }

    fun refreshDay() = snapshots.refreshDay()
}
