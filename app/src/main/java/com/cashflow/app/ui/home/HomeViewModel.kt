package com.cashflow.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cashflow.app.EngineFactory
import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.engine.BalanceEngine
import com.cashflow.app.engine.EntryPlanner
import com.cashflow.app.engine.NewEntry
import com.cashflow.app.engine.NextExpense
import com.cashflow.app.engine.RuleMaintenance
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.WalletMode
import com.cashflow.app.engine.WhatIfResult
import com.cashflow.app.engine.whatIf
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
    /** The single headline figure. */
    val available: Long,
    /** PENDING items dated today or earlier, oldest first. */
    val toConfirm: List<Transaction>,
    val nextExpense: NextExpense?,
    val today: LocalDate,
    val walletMode: WalletMode,
    /** Default start date for a new recurring income source. */
    val newSourceStart: LocalDate,
)

class HomeViewModel(
    private val repository: LedgerRepository,
    engineFactory: EngineFactory,
    private val clock: () -> LocalDate,
) : ViewModel() {
    private val today = MutableStateFlow(clock())

    @Volatile
    private var engine: BalanceEngine? = null

    /** Null until the first data emission, so the UI never shows placeholder numbers. */
    val state: StateFlow<HomeUiState?> = combine(
        repository.settings.filterNotNull(),
        repository.transactions,
        today,
    ) { settings, transactions, day ->
        val e = engineFactory.create(settings, transactions, day)
        engine = e
        HomeUiState(
            available = e.available(),
            toConfirm = e.toConfirm(),
            nextExpense = e.nextExpense(),
            today = day,
            walletMode = settings.walletMode,
            newSourceStart = RuleMaintenance.newSourceStartDate(settings, day),
        )
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

    /** Confirms [id]; with [newAmount] the row's amount is changed first (only this row). */
    fun confirm(id: Long, newAmount: Long? = null) {
        viewModelScope.launch {
            if (newAmount == null) repository.confirm(id) else repository.confirmWithAmount(id, newAmount)
        }
    }

    fun skip(id: Long) {
        viewModelScope.launch { repository.skip(id) }
    }

    /** Returns [id] to PENDING, restoring [originalAmount] if the confirm had changed it. */
    fun undo(id: Long, originalAmount: Long? = null) {
        viewModelScope.launch { repository.undo(id, originalAmount) }
    }

    /** "What if I save this?" against the current data; null if there is no data yet. */
    fun whatIf(entry: NewEntry): WhatIfResult? {
        val e = engine ?: return null
        return EntryPlanner.transactionFor(entry, e.today)?.let { e.whatIf(newTransactions = listOf(it)) }
            ?: EntryPlanner.ruleFor(entry)?.let { e.whatIf(newRule = it) }
    }

    /** Stores a validated entry. Recurring entries become rules / income sources and materialize. */
    suspend fun save(entry: NewEntry) {
        val day = clock()
        val tx = EntryPlanner.transactionFor(entry, day)
        if (tx != null) {
            repository.insertTransactions(listOf(tx))
        } else {
            EntryPlanner.ruleFor(entry)?.let { repository.saveRule(it, day) }
        }
    }

    private fun millisUntilNextDay(): Long {
        val now = LocalDateTime.now()
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis()
    }
}
