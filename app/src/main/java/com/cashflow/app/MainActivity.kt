package com.cashflow.app

import android.os.Bundle
import android.util.Log
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.cashflow.app.ui.calendar.CalendarScreen
import com.cashflow.app.ui.calendar.CalendarViewModel
import com.cashflow.app.ui.components.FitText
import com.cashflow.app.ui.upcoming.UpcomingScreen
import com.cashflow.app.ui.upcoming.UpcomingViewModel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cashflow.app.engine.HeroLaunch
import com.cashflow.app.ui.components.HeroOdometer
import com.cashflow.app.ui.rememberHaptics
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        val freshLaunch = savedInstanceState == null
        setContent {
            CashflowTheme {
                val factory = ViewModelFactory(app)
                val home: HomeViewModel = viewModel(factory = factory)
                val settings: SettingsViewModel = viewModel(factory = factory)
                val calendar: CalendarViewModel = viewModel(factory = factory)
                val upcoming: UpcomingViewModel = viewModel(factory = factory)
                // Scroll positions live here, above the tab switch, so each tab is where the user left it.
                val homeList = rememberLazyListState()
                val upcomingList = rememberLazyListState()
                val settingsScroll = rememberScrollState()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val snackbar = remember { SnackbarHostState() }

                // The hero odometer lives here, above the tabs, so switching tabs never touches it. The launch
                // count-down plays ONLY on a fresh start (no saved state); returning from the background,
                // rotation / theme / font changes and tab switches never replay it.
                val haptics = rememberHaptics()
                val odometer = remember { HeroOdometer() }
                LaunchedEffect(Unit) {
                    var firstEmission = true
                    home.state.map { it?.available }.distinctUntilChanged().collectLatest { value ->
                        if (value != null) {
                            val action = HeroLaunch.decide(freshLaunch, firstEmission, animationsEnabled())
                            Log.i("CashflowHero", "emission=$value fresh=$freshLaunch first=$firstEmission -> $action")
                            firstEmission = false
                            odometer.show(value, action, haptics)
                        }
                    }
                }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        // Four labelled tabs. The labels follow the font size up to 1.3x and then shrink to fit,
                        // so "Calendar" and "Upcoming" never get cut off.
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, 1.3f))) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp,
                            ) {
                                val colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val tabs: List<Triple<Int, ImageVector, Int>> = listOf(
                                    Triple(0, Icons.Filled.Home, R.string.nav_home),
                                    Triple(1, Icons.Filled.DateRange, R.string.nav_calendar),
                                    Triple(2, Icons.AutoMirrored.Filled.List, R.string.nav_upcoming),
                                    Triple(3, Icons.Filled.Settings, R.string.nav_settings),
                                )
                                for ((index, icon, label) in tabs) {
                                    NavigationBarItem(
                                        selected = tab == index,
                                        onClick = { tab = index },
                                        icon = { Icon(icon, contentDescription = null) },
                                        label = {
                                            FitText(
                                                stringResource(label),
                                                if (tab == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                                MaterialTheme.typography.labelMedium,
                                            )
                                        },
                                        alwaysShowLabel = true,
                                        colors = colors,
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    // A short crossfade is all the transition there is: cheap to run and to re-enter.
                    Box(Modifier.fillMaxSize()) {
                        Crossfade(targetState = tab, animationSpec = tween(120), label = "tabs") { selected ->
                            when (selected) {
                                0 -> HomeScreen(home, odometer, snackbar, padding, homeList)
                                1 -> CalendarScreen(calendar, home, settings, snackbar, padding)
                                2 -> UpcomingScreen(upcoming, home, settings, snackbar, padding, upcomingList)
                                else -> SettingsScreen(settings, app.repository, app.updateManager, padding, settingsScroll)
                            }
                        }
                        // Content that scrolls up slides under a solid strip, never under the clock and icons.
                        Box(
                            Modifier.align(Alignment.TopStart).fillMaxWidth()
                                .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                                .background(MaterialTheme.colorScheme.background),
                        )
                    }
                }
            }
        }
    }

    /** False when the user turned system animations off (animator duration scale 0): show final values at once. */
    private fun animationsEnabled(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
}
