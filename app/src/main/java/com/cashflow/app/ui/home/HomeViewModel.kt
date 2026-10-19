package com.cashflow.app.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cashflow.app.EngineFactory
import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.engine.BalanceEngine
import com.cashflow.app.engine.DeleteOutcome
import com.cashflow.app.engine.DeletePlan
import com.cashflow.app.engine.EntryPlanner
import com.cashflow.app.engine.ExtendChoice
import com.cashflow.app.engine.SeriesContext
import com.cashflow.app.engine.EntryProjection
import com.cashflow.app.engine.ResultingBalance
import com.cashflow.app.engine.NameSuggestions
import com.cashflow.app.engine.RedDip
import com.cashflow.app.engine.RedDipCheck
import com.cashflow.app.engine.TxKind
import com.cashflow.app.engine.NewEntry
import com.cashflow.app.engine.PlanBaseline
import com.cashflow.app.engine.NextExpense
import com.cashflow.app.engine.RecurringRule
import com.cashflow.app.engine.RuleMaintenance
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxStatus
import com.cashflow.app.ui.UiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

@Immutable
data class HomeUiState(
    /** The single headline figure. */
    val available: Long,
    /** PENDING items dated today or earlier, oldest first. */
    val toConfirm: List<Transaction>,
    /** The one quiet line under the balance. */
    val status: com.cashflow.app.engine.HomeStatus,
    /** Accepted expenses dated today (0 = nothing spent: the "Today" line is hidden). */
    val spentToday: Long = 0,
    /** Everything entered so far, for the wizard's smart defaults (same as last time, recent amounts). */
    val history: List<Transaction> = emptyList(),
    val rules: List<RecurringRule> = emptyList(),
    val recentExpenseAmounts: List<Long> = emptyList(),
    val recentIncomeAmounts: List<Long> = emptyList(),
    val today: LocalDate,
    /** Default start date for a new recurring income source. */
    val newSourceStart: LocalDate,
    val balanceStartDate: LocalDate,
    /** The three most used names, for the chips above the wizard's name field. */
    val expenseNames: List<String> = emptyList(),
    val incomeNames: List<String> = emptyList(),
)

class HomeViewModel(
    private val repository: LedgerRepository,
    engineFactory: EngineFactory,
    private val clock: () -> LocalDate,
    private val prefs: UiPrefs,
) : ViewModel() {
    private val today = MutableStateFlow(clock())

    @Volatile
    private var engine: BalanceEngine? = null

    /** Null until the first data emission, so the UI never shows placeholder numbers. */
    val state: StateFlow<HomeUiState?> = combine(
        repository.settings.filterNotNull(),
        repository.transactions,
        today,
        repository.rules,
    ) { settings, transactions, day, rules ->
        val e = engineFactory.create(settings, transactions, day)
        engine = e
        HomeUiState(
            available = e.available(),
            toConfirm = e.toConfirm(),
            status = com.cashflow.app.engine.HomeStatusLine.of(e),
            spentToday = com.cashflow.app.engine.SpentToday.of(e),
            history = transactions,
            rules = rules,
            recentExpenseAmounts = com.cashflow.app.engine.Suggestions.recentAmounts(TxKind.EXPENSE, transactions, rules, day),
            recentIncomeAmounts = com.cashflow.app.engine.Suggestions.recentAmounts(TxKind.INCOME, transactions, rules, day),
            today = day,
            newSourceStart = RuleMaintenance.newSourceStartDate(settings, day),
            balanceStartDate = settings.balanceStartDate,
            expenseNames = NameSuggestions.topFrequent(transactions, TxKind.EXPENSE),
            incomeNames = NameSuggestions.topFrequent(transactions, TxKind.INCOME),
        )
    }.flowOn(Dispatchers.Default) // the engine runs off the main thread
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether the "← Skip / Confirm →" hints on the cards are still shown. */
    val swipeHintVisible: StateFlow<Boolean> = prefs.swipeHintDismissed
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, !prefs.swipeHintDismissed.value)

    /** Call after a completed swipe (not after a TalkBack action): the hints fade away for good. */
    fun onSwipeCompleted() = prefs.markSwipeUsed()

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
            repository.applyAutoAccept(com.cashflow.app.engine.AppClock.dateTime()) // income of repeating series, when the setting is on
            today.value = day
        }
    }

    /** Confirms [id]; with [newAmount] the row's amount is changed first (only this row). */
    fun confirm(id: Long, newAmount: Long? = null) {
        viewModelScope.launch {
            if (newAmount == null) repository.confirm(id, clock()) else repository.confirmWithAmount(id, newAmount, clock())
        }
    }

    /** Undo of an accept, only within its few seconds and only while the row is still what that accept made. */
    fun undoAccept(token: com.cashflow.app.engine.AcceptToken) {
        viewModelScope.launch { repository.undoAccept(token) }
    }

    fun skip(id: Long) {
        viewModelScope.launch { repository.skip(id, clock()) }
    }

    /** Returns [id] to PENDING, restoring [originalAmount] if the confirm had changed it. */
    fun undo(id: Long, originalAmount: Long? = null) {
        viewModelScope.launch { repository.undo(id, originalAmount) }
    }

    /** Changes only this row (amount, description, date). */
    fun updateItem(id: Long, amount: Long, description: String, date: LocalDate, status: TxStatus? = null) {
        viewModelScope.launch { repository.updateItem(id, amount, description, date, status, clock()) }
    }

    /** Moves one waiting item to a later day (only this occurrence when it belongs to a series). */
    fun postpone(id: Long, newDate: LocalDate, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onDone(repository.postpone(id, newDate)) }
    }

    /** Undo of a postpone. */
    fun moveTo(id: Long, date: LocalDate) {
        viewModelScope.launch { repository.moveTo(id, date) }
    }

    /** Every repeating series, so a details sheet can describe the one an item belongs to. */
    val rules: StateFlow<List<RecurringRule>> = repository.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "Edit this and future": new amount and name; only waiting future occurrences are regenerated. */
    fun editSeries(rule: RecurringRule, amount: Long, description: String) {
        viewModelScope.launch { repository.saveRule(rule.copy(amount = amount, description = description.trim()), clock()) }
    }

    /** Every stored row, for what the series sheet shows about what was really paid. */
    val transactions: StateFlow<List<Transaction>> = repository.transactions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Series the user ended explicitly, and series that ask for the amount each time. */
    val series: StateFlow<SeriesContext> = repository.seriesContext

    /** "End series": no occurrence from [from] on; [onDone] gets what Undo needs. */
    fun endSeriesFrom(id: Long, from: LocalDate, onDone: (Pair<RecurringRule, List<Transaction>>?) -> Unit = {}) {
        viewModelScope.launch {
            val plan = repository.endSeriesPlan(id, from, clock())
            repository.endSeriesFrom(id, from, clock())
            onDone(plan)
        }
    }

    fun undoEndSeries(oldRule: RecurringRule, removed: List<Transaction>) {
        viewModelScope.launch { repository.undoEndSeries(oldRule, removed) }
    }

    /** "Delete series": the series and its waiting future items go; accepted and rejected items stay. */
    fun deleteSeries(id: Long, onDone: (LedgerRepository.DeletedSeries?) -> Unit = {}) {
        viewModelScope.launch { onDone(repository.deleteSeriesWithUndo(id, clock())) }
    }

    fun undoDeleteSeries(deleted: LedgerRepository.DeletedSeries) {
        viewModelScope.launch { repository.undoDeleteSeries(deleted) }
    }

    /** Extends the series; [onDone] gets the new end as text, or null when nothing moved. */
    fun extendSeries(id: Long, choice: ExtendChoice, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch { onDone(repository.extendSeries(id, choice, clock())) }
    }

    /** Saves an edited series and its "ask me the amount each time" switch. */
    fun saveSeries(rule: RecurringRule, askAmount: Boolean) {
        viewModelScope.launch { repository.saveSeries(rule, askAmount, clock()) }
    }

    fun setAskAmount(ruleId: Long, on: Boolean) {
        viewModelScope.launch { repository.setAskAmount(ruleId, on) }
    }

    /** Deletes one item: removed when manual, marked Skipped when generated by a repeating payment. */
    fun deleteItem(tx: Transaction, onDone: (DeleteOutcome?) -> Unit = {}) {
        viewModelScope.launch { onDone(repository.deleteItem(tx)) }
    }

    /** "This and all future" of a repeating item; [onDone] gets the plan to undo with, or null if there was no series. */
    fun deleteThisAndFuture(tx: Transaction, onDone: (DeletePlan.EndSeries?) -> Unit) {
        viewModelScope.launch { onDone(repository.deleteThisAndFuture(tx, clock())) }
    }

    fun undoDeleteThisAndFuture(plan: DeletePlan.EndSeries) {
        viewModelScope.launch { repository.undoDeleteThisAndFuture(plan) }
    }

    /** Undo of a manual delete. */
    fun restoreItem(tx: Transaction) {
        viewModelScope.launch { repository.restoreItem(tx) }
    }

    /** The below-zero check run when the user presses Add: null when nothing goes (more) negative. */
    suspend fun redDip(entry: NewEntry): RedDip? = withContext(Dispatchers.Default) {
        engine?.let { RedDipCheck.check(it, entry) }
    }

    /** The balance the Review screen shows for [entry]: the Available amount after it, or the balance on its day. */
    suspend fun resultingBalance(entry: NewEntry): ResultingBalance? = withContext(Dispatchers.Default) {
        engine?.let { EntryProjection.resultingBalance(it, entry) }
    }

    /** Today's closing balance and the next 180 days, for Plan a purchase. */
    suspend fun purchaseBaseline(): PlanBaseline? = withContext(Dispatchers.Default) {
        engine?.let { PlanBaseline.of(it) }
    }

    /** "Plan it": the parts of a purchase become ordinary one-time planned expenses. */
    suspend fun planPurchase(entries: List<NewEntry.Planned>) {
        for (entry in entries) save(entry)
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
        val now = com.cashflow.app.engine.AppClock.dateTime()
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis()
    }
}
