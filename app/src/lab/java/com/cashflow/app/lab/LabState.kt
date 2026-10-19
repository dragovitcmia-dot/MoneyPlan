package com.cashflow.app.lab

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.cashflow.app.debug.lab.LabResults
import com.cashflow.app.debug.lab.LabSession
import com.cashflow.app.engine.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime
import java.time.ZoneId

/** The Lab build's own small settings: the time machine's offset, the notifications switch and the last scenario. Never part of a backup. */
object LabState {
    private const val DAY_MILLIS = 86_400_000L
    private var prefs: SharedPreferences? = null

    private val _offset = MutableStateFlow(0L)
    val offset: StateFlow<Long> = _offset

    private val _lastScenario = MutableStateFlow<String?>(null)
    val lastScenario: StateFlow<String?> = _lastScenario

    /** Which Lab screen covers the tab content: the home (it opens when the Lab starts), the results, or none. */
    enum class Overlay { NONE, HOME, RESULTS }

    private val _overlay = MutableStateFlow(Overlay.HOME)
    val overlay: StateFlow<Overlay> = _overlay

    fun showHome() { _overlay.value = Overlay.HOME }
    fun showResults() { _overlay.value = Overlay.RESULTS }
    fun hideOverlay() { _overlay.value = Overlay.NONE }

    /** The run in progress ("Run all checks" or one scenario), or null. In memory only. */
    private val _session = MutableStateFlow<LabSession?>(null)
    val session: StateFlow<LabSession?> = _session
    fun setSession(session: LabSession?) { _session.value = session }

    /** True while a check's data is being prepared (seeding, moving the clock): the panel shows "Preparing" instead of its questions. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    fun setBusy(busy: Boolean) { _busy.value = busy }

    /** The check panel's expanded state (shared by the copies of the panel in the main window and in sheets). */
    private val _panelExpanded = MutableStateFlow(true)
    val panelExpanded: StateFlow<Boolean> = _panelExpanded
    fun setPanelExpanded(expanded: Boolean) { _panelExpanded.value = expanded }

    /** The days the current step moved the clock beyond the scenario's own clock. */
    var shiftDays: Int = 0

    private val _results = MutableStateFlow(LabResults())

    /** One verdict per scenario, kept only in the Lab's own storage. */
    val results: StateFlow<LabResults> = _results

    fun record(id: String, ok: Boolean) {
        _results.value = _results.value.with(id, ok)
        prefs?.edit()?.putString("lab_results", _results.value.encode())?.apply()
    }

    fun clearResults() {
        _results.value = LabResults()
        prefs?.edit()?.remove("lab_results")?.apply()
    }

    fun init(app: Application) {
        val p = app.getSharedPreferences("lab_prefs", Context.MODE_PRIVATE)
        prefs = p
        AppClock.offsetMillis = p.getLong("clock_offset", 0L)
        _offset.value = AppClock.offsetMillis
        _lastScenario.value = p.getString("last_scenario", null)
        _results.value = LabResults.decode(p.getString("lab_results", null))
    }

    /** System notifications are OFF until the Lab card's switch turns them on, so the two apps never both notify. */
    var notificationsOn: Boolean
        get() = prefs?.getBoolean("notifications_on", false) ?: false
        set(value) { prefs?.edit()?.putBoolean("notifications_on", value)?.apply() }

    fun setOffset(millis: Long) {
        AppClock.offsetMillis = millis
        _offset.value = millis
        prefs?.edit()?.putLong("clock_offset", millis)?.apply()
    }

    fun setClockTo(target: LocalDateTime) = setOffset(AppClock.offsetFor(target, ZoneId.systemDefault(), System.currentTimeMillis()))

    fun shiftDays(days: Int) = setOffset(AppClock.offsetMillis + days * DAY_MILLIS)

    fun realTime() = setOffset(0L)

    fun rememberScenario(id: String?) {
        _lastScenario.value = id
        prefs?.edit()?.putString("last_scenario", id)?.apply()
    }
}
