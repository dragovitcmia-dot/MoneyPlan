package com.cashflow.app.notify

import android.content.Context
import com.cashflow.app.engine.AppSettings
import com.cashflow.app.engine.NotificationSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The switches of the Settings > Notifications card. They live in preferences, so the main database stays untouched. */
data class NotificationToggles(
    val enabled: Boolean = true,
    val headsUp: Boolean = true,
    val keepReminding: Boolean = true,
    val shortageAlerts: Boolean = true,
)

class NotificationPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)

    private val state = MutableStateFlow(
        NotificationToggles(
            enabled = prefs.getBoolean(ENABLED, true),
            headsUp = prefs.getBoolean(HEADS_UP, true),
            keepReminding = prefs.getBoolean(KEEP, true),
            shortageAlerts = prefs.getBoolean(SHORTAGE, true),
        ),
    )
    val toggles: StateFlow<NotificationToggles> = state

    fun update(transform: (NotificationToggles) -> NotificationToggles) {
        val next = transform(state.value)
        state.value = next
        prefs.edit()
            .putBoolean(ENABLED, next.enabled).putBoolean(HEADS_UP, next.headsUp)
            .putBoolean(KEEP, next.keepReminding).putBoolean(SHORTAGE, next.shortageAlerts)
            .apply()
    }

    /** The permission is asked automatically once (after the first payment); later asks come from the user's own taps. */
    var permissionAsked: Boolean
        get() = prefs.getBoolean(ASKED, false)
        set(value) { prefs.edit().putBoolean(ASKED, value).apply() }

    @Synchronized
    fun postedKeys(): Set<String> = prefs.getStringSet(POSTED, emptySet()).orEmpty().toSet()

    @Synchronized
    fun savePostedKeys(keys: Set<String>) {
        prefs.edit().putStringSet(POSTED, keys).apply()
    }

    fun settingsFor(app: AppSettings, toggles: NotificationToggles = state.value) = NotificationSettings(
        enabled = toggles.enabled,
        reminderTime = app.reminderTime,
        headsUp = toggles.headsUp,
        keepReminding = toggles.keepReminding,
        shortageAlerts = toggles.shortageAlerts,
        autoAcceptIncome = app.autoAcceptIncome,
    )

    private companion object {
        const val ENABLED = "enabled"
        const val HEADS_UP = "heads_up"
        const val KEEP = "keep_reminding"
        const val SHORTAGE = "shortage_alerts"
        const val ASKED = "permission_asked"
        const val POSTED = "posted_keys"
    }
}
