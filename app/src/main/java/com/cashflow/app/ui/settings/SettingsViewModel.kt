package com.cashflow.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.engine.AppSettings
import com.cashflow.app.engine.RuleMaintenance
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

class SettingsViewModel(
    private val repository: LedgerRepository,
    private val clock: () -> LocalDate,
) : ViewModel() {
    private val _settings = MutableStateFlow<AppSettings?>(null)

    /** Local working copy; edits show up here at once and are saved in order in the background. */
    val settings: StateFlow<AppSettings?> = _settings

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
}
