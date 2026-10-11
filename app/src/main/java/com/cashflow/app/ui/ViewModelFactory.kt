package com.cashflow.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.cashflow.app.CashflowApp
import com.cashflow.app.ui.home.HomeViewModel
import com.cashflow.app.ui.settings.SettingsViewModel

class ViewModelFactory(private val app: CashflowApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(HomeViewModel::class.java) ->
            HomeViewModel(app.repository, app.engineFactory, app.clock, app.uiPrefs) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(app.repository, app.clock) as T
        else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }
}
