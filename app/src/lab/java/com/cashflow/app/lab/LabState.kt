package com.cashflow.app.lab

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
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

    fun init(app: Application) {
        val p = app.getSharedPreferences("lab_prefs", Context.MODE_PRIVATE)
        prefs = p
        AppClock.offsetMillis = p.getLong("clock_offset", 0L)
        _offset.value = AppClock.offsetMillis
        _lastScenario.value = p.getString("last_scenario", null)
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
