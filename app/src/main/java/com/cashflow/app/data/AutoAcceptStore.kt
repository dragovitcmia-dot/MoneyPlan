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

/**
 * Per-series flags that live in the same settings store as [AutoAcceptStore] (no database change): the series that ask for
 * the amount each time, and the series the user ended explicitly.
 */
interface SeriesFlagsStore {
    val flags: StateFlow<com.cashflow.app.engine.SeriesContext>
    fun setAskAmount(ruleId: Long, on: Boolean)
    fun markEnded(ruleId: Long)
    fun unmarkEnded(ruleId: Long)

    /** Forgets ids of series that no longer exist. */
    fun prune(existingRuleIds: Set<Long>)
}

class SharedPrefsSeriesFlags(context: Context) : SeriesFlagsStore {
    private val prefs = context.applicationContext.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    override val flags: StateFlow<com.cashflow.app.engine.SeriesContext> = state

    private fun read() = com.cashflow.app.engine.SeriesContext(
        endedRuleIds = ids(ENDED),
        askAmountRuleIds = ids(ASK),
    )

    private fun ids(key: String): Set<Long> = prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    @Synchronized
    private fun write(next: com.cashflow.app.engine.SeriesContext) {
        if (next == state.value) return
        state.value = next
        prefs.edit()
            .putStringSet(ENDED, next.endedRuleIds.map(Long::toString).toSet())
            .putStringSet(ASK, next.askAmountRuleIds.map(Long::toString).toSet())
            .apply()
    }

    override fun setAskAmount(ruleId: Long, on: Boolean) {
        val cur = state.value
        write(cur.copy(askAmountRuleIds = if (on) cur.askAmountRuleIds + ruleId else cur.askAmountRuleIds - ruleId))
    }

    override fun markEnded(ruleId: Long) = write(state.value.copy(endedRuleIds = state.value.endedRuleIds + ruleId))

    override fun unmarkEnded(ruleId: Long) = write(state.value.copy(endedRuleIds = state.value.endedRuleIds - ruleId))

    override fun prune(existingRuleIds: Set<Long>) {
        val cur = state.value
        write(cur.copy(endedRuleIds = cur.endedRuleIds.intersect(existingRuleIds), askAmountRuleIds = cur.askAmountRuleIds.intersect(existingRuleIds)))
    }

    private companion object {
        const val ENDED = "ended_rule_ids"
        const val ASK = "ask_amount_rule_ids"
    }
}
