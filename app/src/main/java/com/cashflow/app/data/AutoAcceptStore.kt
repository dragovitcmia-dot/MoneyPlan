package com.cashflow.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the "accept my income automatically" setting lives (outside the database, so no schema change). */
interface AutoAcceptStore {
    val enabled: StateFlow<Boolean>
    fun set(enabled: Boolean)
}

class SharedPrefsAutoAccept(context: Context) : AutoAcceptStore {
    private val prefs = context.applicationContext.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getBoolean(KEY, false))

    override val enabled: StateFlow<Boolean> = state

    override fun set(enabled: Boolean) {
        if (state.value == enabled) return
        state.value = enabled
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    private companion object {
        const val KEY = "auto_accept_income"
    }
}
