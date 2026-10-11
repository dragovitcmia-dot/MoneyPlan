package com.cashflow.app.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small on-device UI preferences (not part of the database, so no schema change). */
class UiPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    private val _swipeHintDismissed = MutableStateFlow(prefs.getBoolean(KEY_SWIPE_HINT_DISMISSED, false))

    /** True once the user has completed a swipe; the "← Skip / Confirm →" hints then fade away for good. */
    val swipeHintDismissed: StateFlow<Boolean> = _swipeHintDismissed

    fun markSwipeUsed() {
        if (_swipeHintDismissed.value) return
        _swipeHintDismissed.value = true
        prefs.edit().putBoolean(KEY_SWIPE_HINT_DISMISSED, true).apply()
    }

    private companion object {
        const val KEY_SWIPE_HINT_DISMISSED = "swipe_hint_dismissed"
    }
}
