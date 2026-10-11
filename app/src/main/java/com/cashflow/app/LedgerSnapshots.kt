package com.cashflow.app

import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.engine.BalanceEngine
import com.cashflow.app.engine.RecurringRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** One immutable view of the data: the engine for today, plus every rule (the calendar needs to know which are daily). */
class LedgerSnapshot(val engine: BalanceEngine, val rules: List<RecurringRule>)

/**
 * The engine snapshot shared by the Calendar and Upcoming tabs. It is rebuilt (off the main thread)
 * whenever a transaction, a rule, the settings or the date changes, so every tab sees the same numbers.
 */
class LedgerSnapshots(
    repository: LedgerRepository,
    engineFactory: EngineFactory,
    private val clock: () -> LocalDate,
    scope: CoroutineScope,
) {
    private val day = MutableStateFlow(clock())
    val today: StateFlow<LocalDate> = day

    /** Re-reads the date, e.g. when the app comes back after the date changed. */
    fun refreshDay() {
        day.value = clock()
    }

    init {
        scope.launch {
            while (true) {
                day.value = clock()
                val now = LocalDateTime.now()
                delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis() + 1_000)
            }
        }
    }

    /** Null until the first data emission. */
    val snapshot: StateFlow<LedgerSnapshot?> = combine(
        repository.settings.filterNotNull(),
        repository.transactions,
        repository.rules,
        day,
    ) { settings, transactions, rules, date ->
        LedgerSnapshot(engineFactory.create(settings, transactions, date), rules)
    }.flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)
}
