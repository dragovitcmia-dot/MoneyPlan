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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.CheckCircle
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
import com.cashflow.app.engine.NavLogic
import com.cashflow.app.engine.NavState
import com.cashflow.app.engine.NotificationRoute
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.ui.theme.glassSource
import com.cashflow.app.ui.theme.glassSurface
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

    private class NavRequest(val route: NotificationRoute, val date: LocalDate?, val ruleId: Long?, val itemId: Long?)

    private fun readRequest(intent: android.content.Intent?) {
        val route = intent?.getStringExtra(EXTRA_ROUTE)?.let { runCatching { NotificationRoute.valueOf(it) }.getOrNull() } ?: return
        val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val rule = intent.getLongExtra(EXTRA_RULE, -1L).takeIf { it >= 0 }
        val item = intent.getLongExtra(EXTRA_ITEM, -1L).takeIf { it >= 0 }
        intent.removeExtra(EXTRA_ROUTE)
        navRequest = NavRequest(route, date, rule, item)
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
                val history: com.cashflow.app.ui.history.HistoryViewModel = viewModel(factory = factory)
                // Scroll positions live here, above the tab switch, so each tab is where the user left it.
                val homeList = rememberLazyListState()
                val upcomingList = rememberLazyListState()
                val historyList = rememberLazyListState()
                val settingsScroll = rememberScrollState()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var showCenter by rememberSaveable { mutableStateOf(false) }
                var backupsOpen by rememberSaveable { mutableStateOf(false) }
                // A place to show on Home by itself (the wizard on its Name step, the purchase result).
                var homeRequest by remember { mutableStateOf<com.cashflow.app.engine.UiTarget?>(null) }
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

                // A last-payment notification opens that series' sheet; an "ask the amount" notification opens that item's amount sheet.
                var seriesRequest by rememberSaveable { mutableStateOf<Long?>(null) }
                var itemRequest by rememberSaveable { mutableStateOf<Long?>(null) }

                fun navigate(route: NotificationRoute, date: LocalDate?, ruleId: Long? = null, itemId: Long? = null) {
                    showCenter = NavLogic.closeCenter(NavState(tab, showCenter)).centerOpen
                    when (route) {
                        NotificationRoute.UPCOMING -> {
                            tab = 2
                            itemRequest = itemId
                        }
                        NotificationRoute.SERIES -> seriesRequest = ruleId
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
                        navigate(request.route, request.date, request.ruleId, request.itemId)
                        navRequest = null
                    }
                }
                // Anything that wants the app to show a place by itself (the Cashflow Lab's checks) posts it to UiTargets.
                val pendingTarget by com.cashflow.app.ui.UiTargets.pending.collectAsStateWithLifecycle()
                LaunchedEffect(pendingTarget) {
                    val pending = pendingTarget ?: return@LaunchedEffect
                    showCenter = false
                    backupsOpen = false
                    calendar.openDayList(null)
                    calendar.requestItem(null)
                    calendar.resetSheets()
                    when (val t = pending.target) {
                        com.cashflow.app.engine.UiTarget.Home -> {
                            tab = 0
                            homeRequest = t // Home closes the wizard or purchase sheet it may still have open
                        }
                        is com.cashflow.app.engine.UiTarget.CalendarDay -> {
                            tab = 1
                            calendar.showMonth(YearMonth.from(t.date))
                            calendar.openDayList(t.date)
                            if (t.item != null) calendar.requestItem(CalendarViewModel.ItemRequest(t.date, t.item, t.move))
                        }
                        is com.cashflow.app.engine.UiTarget.Upcoming -> {
                            tab = 2
                            upcoming.show(t.series)
                        }
                        is com.cashflow.app.engine.UiTarget.Wizard, is com.cashflow.app.engine.UiTarget.Purchase -> {
                            tab = 0
                            homeRequest = t
                        }
                        is com.cashflow.app.engine.UiTarget.History -> {
                            tab = com.cashflow.app.engine.TabIndex.HISTORY
                            history.show(t.tab, t.query, t.openFirst)
                        }
                        com.cashflow.app.engine.UiTarget.Settings -> tab = com.cashflow.app.engine.TabIndex.SETTINGS
                        com.cashflow.app.engine.UiTarget.Backups -> {
                            tab = com.cashflow.app.engine.TabIndex.SETTINGS
                            backupsOpen = true
                        }
                    }
                    com.cashflow.app.ui.UiTargets.consume(pending)
                }
                BackHandler(enabled = showCenter) { showCenter = NavLogic.closeCenter(NavState(tab, showCenter)).centerOpen }

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

                val glassOn by app.uiPrefs.glassEffects.collectAsStateWithLifecycle()
                val glass = com.cashflow.app.ui.theme.rememberGlass(glassOn)
                CompositionLocalProvider(LocalNotificationPermission provides permission, com.cashflow.app.ui.theme.LocalGlass provides glass) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = { AppTopBar(unread = unread, onBell = { NavLogic.toggleCenter(NavState(tab, showCenter)).let { tab = it.tab; showCenter = it.centerOpen } }) },
                    bottomBar = {
                        // Four labelled tabs. The labels follow the font size up to 1.3x and then shrink to fit,
                        // so "Calendar" and "Upcoming" never get cut off.
                        val density = LocalDensity.current
                        androidx.compose.foundation.layout.Column {
                        // Lab build only: the check panel sits right above the navigation bar (a no-op everywhere else).
                        com.cashflow.app.lab.LabHooks.Panel()
                        CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, 1.3f))) {
                            BottomTabBar(
                                tabs = listOf(
                                    NavTab(Icons.Filled.Home, R.string.nav_home),
                                    NavTab(Icons.Filled.DateRange, R.string.nav_calendar),
                                    NavTab(Icons.AutoMirrored.Filled.List, R.string.nav_upcoming),
                                    NavTab(Icons.Filled.CheckCircle, R.string.nav_history),
                                    NavTab(Icons.Filled.Settings, R.string.nav_settings),
                                ),
                                selected = tab,
                                // Every tab always works: with the notification center open, a tap leaves it and shows that tab.
                                onSelect = { index ->
                                    if (index != tab || showCenter) haptics.tick() // light tick on every tab change
                                    if (index != tab) backupsOpen = false
                                    com.cashflow.app.lab.LabHooks.onTabSelected()
                                    NavLogic.selectTab(NavState(tab, showCenter), index).let { tab = it.tab; showCenter = it.centerOpen }
                                },
                            )
                        }
                        }
                    },
                ) { padding ->
                    // A short crossfade is all the transition there is: cheap to run and to re-enter.
                    Box(Modifier.fillMaxSize()) {
                        // The tab content is the backdrop the glass bottom bar and notification center blur.
                        Box(Modifier.fillMaxSize().glassSource()) {
                        Crossfade(targetState = tab, animationSpec = tween(120), label = "tabs") { selected ->
                            when (selected) {
                                0 -> HomeScreen(
                                    home, odometer, snackbar, padding, homeList,
                                    onOpenDay = { day -> navigate(NotificationRoute.CALENDAR, day) },
                                    // the "Today" line: the Calendar on that day with its list open
                                    onOpenDayList = { day -> navigate(NotificationRoute.CALENDAR, day); calendar.openDayList(day) },
                                    request = homeRequest,
                                    onRequestHandled = { homeRequest = null },
                                )
                                1 -> CalendarScreen(calendar, home, settings, snackbar, padding)
                                2 -> UpcomingScreen(upcoming, home, settings, snackbar, padding, upcomingList, openItemId = itemRequest, onOpenItemHandled = { itemRequest = null })
                                3 -> com.cashflow.app.ui.history.HistoryScreen(history, home, snackbar, padding, historyList)
                                else -> SettingsScreen(settings, app.repository, app.updateManager, padding, settingsScroll, backupsOpen, { backupsOpen = it })
                            }
                        }
                        seriesRequest?.let { id ->
                            com.cashflow.app.ui.items.SeriesFromNotification(id, home, snackbar, onDismiss = { seriesRequest = null })
                        }
                        }
                        // Lab build only: its home and results screens cover the tab content (the bars stay); a no-op everywhere else.
                        com.cashflow.app.lab.LabHooks.Overlay(onOpenSettings = { tab = com.cashflow.app.engine.TabIndex.SETTINGS })
                        if (showCenter) {
                            // The notification center covers the tab content (the bars stay); Back or the arrow closes it.
                            // With glass on it stops above the bottom bar and is a frosted panel over the tab content.
                            val centerModifier = if (glass.active) {
                                Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())
                                    .glassSurface(androidx.compose.ui.graphics.RectangleShape, MaterialTheme.colorScheme.background)
                            } else {
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                            }
                            Box(centerModifier) {
                                NotificationCenterScreen(
                                    app = app,
                                    contentPadding = if (glass.active) PaddingValues(top = padding.calculateTopPadding()) else padding,
                                    onClose = { showCenter = NavLogic.closeCenter(NavState(tab, showCenter)).centerOpen },
                                    onOpen = { entry ->
                                        navigate(
                                            entry.type.routeOf(),
                                            entry.relatedDate,
                                            ruleId = if (entry.key.startsWith("LAST|")) entry.key.split("|").getOrNull(1)?.toLongOrNull() else null,
                                            // A day summary about ONE waiting item of an "ask me the amount" series opens that item's amount sheet, like the status-bar notification.
                                            itemId = if (entry.type == com.cashflow.app.engine.NotificationType.DAY_OF) {
                                                home.state.value?.toConfirm?.singleOrNull()?.takeIf { it.recurringRuleId in home.series.value.askAmountRuleIds }?.id
                                            } else {
                                                null
                                            },
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
        const val EXTRA_RULE = "notification_rule"
        const val EXTRA_ITEM = "notification_item"
    }
}

/** Shortage entries open the Calendar on their date; everything else opens Upcoming. */
private fun com.cashflow.app.engine.NotificationType.routeOf(): NotificationRoute =
    when (this) {
        com.cashflow.app.engine.NotificationType.SHORTAGE -> NotificationRoute.CALENDAR
        com.cashflow.app.engine.NotificationType.LAST_PAYMENT -> NotificationRoute.SERIES
        else -> NotificationRoute.UPCOMING
    }
