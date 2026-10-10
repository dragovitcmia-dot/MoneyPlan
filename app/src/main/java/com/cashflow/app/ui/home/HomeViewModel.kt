package com.cashflow.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cashflow.app.EngineFactory
import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.engine.NextExpense
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

data class HomeUiState(
    val currentBalance: Long,
    val balanceAfterPending: Long,
    val nextExpense: NextExpense?,
)

class HomeViewModel(
    private val repository: LedgerRepository,
    engineFactory: EngineFactory,
    private val clock: () -> LocalDate,
) : ViewModel() {
    private val today = MutableStateFlow(clock())

    val state: StateFlow<HomeUiState?> = combine(
        repository.settings.filterNotNull(),
        repository.transactions,
        today,
    ) { settings, transactions, day ->
        val engine = engineFactory.create(settings, transactions, day)
        HomeUiState(engine.currentBalance(), engine.balanceAfterPending(), engine.nextExpense())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Catch up on start, then again right after every midnight while the process is alive.
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(millisUntilNextDay() + 1_000)
            }
        }
    }

    /** Also called when the screen resumes, e.g. after the app was in the background for days. */
    fun refresh() {
        val day = clock()
        viewModelScope.launch {
            repository.runMaintenance(day)
            today.value = day
        }
    }

    private fun millisUntilNextDay(): Long {
        val now = LocalDateTime.now()
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis()
    }
}
