package com.cashflow.app

import android.os.Bundle
import android.util.Log
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.cashflow.app.ui.calendar.CalendarScreen
import com.cashflow.app.ui.calendar.CalendarViewModel
import com.cashflow.app.ui.components.BottomTabBar
import com.cashflow.app.ui.notifications.AppTopBar
import com.cashflow.app.ui.notifications.LocalNotificationPermission
import com.cashflow.app.ui.notifications.NotificationCenterScreen
import com.cashflow.app.ui.notifications.NotificationPermission
import com.cashflow.app.engine.NotificationRoute
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import java.time.LocalDate
import java.time.YearMonth
import com.cashflow.app.ui.components.NavTab
import com.cashflow.app.ui.upcoming.UpcomingScreen
import com.cashflow.app.ui.upcoming.UpcomingViewModel
import androidx.compose.material3.MaterialTheme
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
    /** Library texts (date picker, dialogs) come in English too, whatever language the phone uses. */
    override fun attachBaseContext(newBase: android.content.Context) {
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(com.cashflow.app.engine.AppDates.LOCALE)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    /** A tap on a notification: where to go (Upcoming, or the Calendar on a date). Consumed by the composition. */
    private var navRequest by mutableStateOf<NavRequest?>(null)

    private class NavRequest(val route: NotificationRoute, val date: LocalDate?)

    private fun readRequest(intent: android.content.Intent?) {
        val route = intent?.getStringExtra(EXTRA_ROUTE)?.let { runCatching { NotificationRoute.valueOf(it) }.getOrNull() } ?: return
        val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        intent.removeExtra(EXTRA_ROUTE)
        navRequest = NavRequest(route, date)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readRequest(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readRequest(intent)
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
                var showCenter by rememberSaveable { mutableStateOf(false) }
                val snackbar = remember { SnackbarHostState() }
                val unread by app.notificationCenter.unread.collectAsStateWithLifecycle(0)

                // The notification permission is asked in context (after the first payment, when reminders are switched on).
                val permissionHolder = remember { arrayOfNulls<NotificationPermission>(1) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    permissionHolder[0]?.refresh()
                }
                val permission = remember {
                    NotificationPermission(app.notificationPoster, app.notificationPrefs) {
                        launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }.also { permissionHolder[0] = it }
                }

                fun navigate(route: NotificationRoute, date: LocalDate?) {
                    showCenter = false
                    when (route) {
                        NotificationRoute.UPCOMING -> tab = 2
                        NotificationRoute.CALENDAR -> {
                            tab = 1
                            if (date != null) {
                                calendar.showMonth(YearMonth.from(date))
                                calendar.select(date)
                            }
                        }
                    }
                }
                val request = navRequest
                LaunchedEffect(request) {
                    if (request != null) {
                        navigate(request.route, request.date)
                        navRequest = null
                    }
                }
                BackHandler(enabled = showCenter) { showCenter = false }

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

                CompositionLocalProvider(LocalNotificationPermission provides permission) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = { AppTopBar(unread = unread, onBell = { showCenter = !showCenter }) },
                    bottomBar = {
                        // Four labelled tabs. The labels follow the font size up to 1.3x and then shrink to fit,
                        // so "Calendar" and "Upcoming" never get cut off.
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, 1.3f))) {
                            BottomTabBar(
                                tabs = listOf(
                                    NavTab(Icons.Filled.Home, R.string.nav_home),
                                    NavTab(Icons.Filled.DateRange, R.string.nav_calendar),
                                    NavTab(Icons.AutoMirrored.Filled.List, R.string.nav_upcoming),
                                    NavTab(Icons.Filled.Settings, R.string.nav_settings),
                                ),
                                selected = tab,
                                onSelect = { tab = it },
                            )
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
                        if (showCenter) {
                            // The notification center covers the tab content (the bars stay); Back or the arrow closes it.
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                NotificationCenterScreen(
                                    app = app,
                                    contentPadding = padding,
                                    onClose = { showCenter = false },
                                    onOpen = { entry ->
                                        navigate(
                                            entry.type.routeOf(),
                                            entry.relatedDate,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }

    /** False when the user turned system animations off (animator duration scale 0): show final values at once. */
    private fun animationsEnabled(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    companion object {
        const val EXTRA_ROUTE = "notification_route"
        const val EXTRA_DATE = "notification_date"
    }
}

/** Shortage entries open the Calendar on their date; everything else opens Upcoming. */
private fun com.cashflow.app.engine.NotificationType.routeOf(): NotificationRoute =
    if (this == com.cashflow.app.engine.NotificationType.SHORTAGE) NotificationRoute.CALENDAR else NotificationRoute.UPCOMING
