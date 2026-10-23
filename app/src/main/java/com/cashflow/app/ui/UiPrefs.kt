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

    private val _glassEffects = MutableStateFlow(prefs.getBoolean(KEY_GLASS_EFFECTS, com.cashflow.app.ui.theme.GlassSpec.DEFAULT_ENABLED))

    /** The "Glass effects" setting: frosted glass on the bottom bar, sheets and the notification center. On by default. */
    val glassEffects: StateFlow<Boolean> = _glassEffects

    fun setGlassEffects(on: Boolean) {
        if (_glassEffects.value == on) return
        _glassEffects.value = on
        prefs.edit().putBoolean(KEY_GLASS_EFFECTS, on).apply()
    }

    private companion object {
        const val KEY_SWIPE_HINT_DISMISSED = "swipe_hint_dismissed"
        const val KEY_GLASS_EFFECTS = "glass_effects"
    }
}
