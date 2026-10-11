package com.cashflow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cashflow.app.ui.ViewModelFactory
import com.cashflow.app.ui.home.HomeScreen
import com.cashflow.app.ui.home.HomeViewModel
import com.cashflow.app.ui.settings.SettingsScreen
import com.cashflow.app.ui.settings.SettingsViewModel
import com.cashflow.app.ui.theme.CashflowTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CashflowApp
        setContent {
            CashflowTheme {
                val factory = ViewModelFactory(app)
                val home: HomeViewModel = viewModel(factory = factory)
                val settings: SettingsViewModel = viewModel(factory = factory)
                var showSettings by rememberSaveable { mutableStateOf(false) }

                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        viewModel = settings,
                        repository = app.repository,
                        updateManager = app.updateManager,
                        onBack = { showSettings = false },
                    )
                } else {
                    HomeScreen(viewModel = home, onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}
