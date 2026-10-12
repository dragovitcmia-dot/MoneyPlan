package com.cashflow.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cashflow.app.LedgerSnapshot
import com.cashflow.app.LedgerSnapshots
import com.cashflow.app.engine.CalendarSummaries
import com.cashflow.app.engine.MonthSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * State of the Calendar tab. It lives in the activity's view model store, so the visible month and the
 * selected day survive tab switches. Month numbers come from the engine forecast, computed off the main
 * thread for the visible month and one on each side, and cached until the data changes.
 */
class CalendarViewModel(private val snapshots: LedgerSnapshots, clock: () -> LocalDate) : ViewModel() {
    private val visible = MutableStateFlow(YearMonth.from(clock()))
    private val _selected = MutableStateFlow<LocalDate?>(null)

    /** The month the pager currently rests on. */
    val month: StateFlow<YearMonth> = visible

    val selected: StateFlow<LocalDate?> = _selected

    val today: StateFlow<LocalDate> = snapshots.today

    /** First day (today or later) that closes below zero; null when none does. */
    val shortDay: StateFlow<LocalDate?> = snapshots.snapshot.filterNotNull()
        .map { CalendarSummaries.firstShortDay(it.engine) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Summaries of the visible month and its neighbours. While a data change is being recomputed the previous
     * numbers stay on screen (they are replaced in one step), so a cell never shows a half-updated value.
     */
    val months: StateFlow<Map<YearMonth, MonthSummary>> = flow {
        var owner: LedgerSnapshot? = null
        var cache = emptyMap<YearMonth, MonthSummary>()
        snapshots.snapshot.filterNotNull().combine(visible) { snap, vis -> snap to vis }.collect { (snap, vis) ->
            val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
            val wanted = listOf(vis.minusMonths(1), vis, vis.plusMonths(1))
            val reusable = if (snap === owner) cache else emptyMap()
            val missing = wanted.filter { it !in reusable }
            if (missing.isNotEmpty() || snap !== owner) {
                val computed = missing.associateWith { CalendarSummaries.month(snap.engine, snap.rules, it, firstDay) }
                cache = (reusable + computed).filterKeys { it >= vis.minusMonths(6) && it <= vis.plusMonths(6) }
                owner = snap
                emit(cache)
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun showMonth(month: YearMonth) {
        visible.value = month
    }

    fun select(date: LocalDate?) {
        _selected.value = date
    }

    /** Called when the tab is shown again, so a changed date is picked up. */
    fun refreshDay() = snapshots.refreshDay()
}
