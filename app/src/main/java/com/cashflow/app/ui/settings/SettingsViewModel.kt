package com.cashflow.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.engine.AppSettings
import com.cashflow.app.engine.RecurringRule
import com.cashflow.app.engine.RuleMaintenance
import com.cashflow.app.engine.TxKind
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class SettingsViewModel(
    private val repository: LedgerRepository,
    private val clock: () -> LocalDate,
) : ViewModel() {
    private val _settings = MutableStateFlow<AppSettings?>(null)

    /** Local working copy; edits show up here at once and are saved in order in the background. */
    val settings: StateFlow<AppSettings?> = _settings

    /** Income sources only (rules of kind INCOME). */
    val sources: StateFlow<List<RecurringRule>> = repository.rules
        .map { rules -> rules.filter { it.kind == TxKind.INCOME } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Repeating payments: expense rules that have not ended. */
    val repeating: StateFlow<List<RecurringRule>> = repository.rules
        .map { rules -> rules.filter { it.kind == TxKind.EXPENSE && RuleMaintenance.isRunning(it, clock()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every rule, so an item can open the series it came from. */
    val rules: StateFlow<List<RecurringRule>> = repository.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val saves = Channel<AppSettings>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            repository.runMaintenance(clock()) // makes sure the settings row exists
            reload()
            for (s in saves) repository.updateSettings(s, clock())
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val current = _settings.value ?: return
        val next = transform(current)
        if (next == current) return
        _settings.value = next
        saves.trySend(next)
    }

    /** Re-read from the database, e.g. after the debug actions replaced the data. */
    fun reload(onLoaded: () -> Unit = {}) {
        viewModelScope.launch {
            _settings.value = repository.settings.filterNotNull().first()
            onLoaded()
        }
    }

    /** Start date a brand-new source should get. */
    fun newSourceStartDate(): LocalDate =
        RuleMaintenance.newSourceStartDate(_settings.value ?: AppSettings.default(clock()), clock())

    fun saveSource(rule: RecurringRule) {
        viewModelScope.launch { repository.saveRule(rule, clock()) }
    }

    fun setSourceEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { repository.setRuleEnabled(id, enabled, clock()) }
    }

    /** "Stop repeating": ends the series today and removes its waiting future entries. */
    fun stopRepeating(id: Long) {
        viewModelScope.launch { repository.stopSeries(id, clock()) }
    }

    fun deleteSource(id: Long) {
        viewModelScope.launch { repository.deleteRule(id, clock()) }
    }
}
