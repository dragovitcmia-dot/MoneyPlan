package com.cashflow.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cashflow.app.LedgerSnapshot
import com.cashflow.app.LedgerSnapshots
import com.cashflow.app.engine.AppDates
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

    /** The day whose list (day sheet) is open: set by a tap on a cell, or from outside (the Home "Today" line, a Lab check). */
    private val _openDay = MutableStateFlow<LocalDate?>(null)
    val openDay: StateFlow<LocalDate?> = _openDay

    /** An item to open by name on [date] (and its Move date picker): set by a Lab check, taken by the Calendar screen once the day is known. */
    class ItemRequest(val date: LocalDate, val name: String, val move: Boolean)

    private val _itemRequest = MutableStateFlow<ItemRequest?>(null)
    val itemRequest: StateFlow<ItemRequest?> = _itemRequest

    fun requestItem(request: ItemRequest?) {
        _itemRequest.value = request
    }

    fun openDayList(date: LocalDate?) {
        _openDay.value = date
        if (date != null) _selected.value = date
    }

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
            val firstDay = AppDates.firstDayOfWeek
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
