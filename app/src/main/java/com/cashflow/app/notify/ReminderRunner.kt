package com.cashflow.app.notify

import com.cashflow.app.CashflowApp
import com.cashflow.app.engine.BalanceEngine
import com.cashflow.app.engine.DecisionAction
import com.cashflow.app.engine.NotificationDecision
import com.cashflow.app.engine.NotificationRoute
import com.cashflow.app.engine.NoticeResolution
import com.cashflow.app.engine.NotificationType
import com.cashflow.app.engine.ShownNotice
import com.cashflow.app.engine.Notifications
import com.cashflow.app.engine.PostedKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Loads the data, lets the pure [Notifications.reconcile] decide, and executes the decisions: status-bar posts, bell
 * entries and the record of handled keys. Always off the main thread; one run at a time.
 */
class ReminderRunner(private val app: CashflowApp) {
    private val lock = Mutex()

    /** Returns the decisions that were executed (for the "Run reminder check now" button). */
    suspend fun run(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<NotificationDecision> =
        lock.withLock {
            withContext(Dispatchers.Default) {
                val repo = app.repository
                val today = LocalDate.ofInstant(now, zone)
                repo.runMaintenance(today)
                // Income of repeating series is accepted at the reminder time: same worker, same schedule.
                repo.applyAutoAccept(LocalDateTime.ofInstant(now, zone))

                val settings = repo.settings.filterNotNull().first()
                val ledger = repo.transactions.first().filter { it.date >= settings.balanceStartDate }
                val rules = repo.rules.first()
                val engine = BalanceEngine(settings, ledger, today)
                val forecast = engine.forecast(today, today.plusDays(Notifications.SHORTAGE_HORIZON_DAYS + 1))

                val prefs = app.notificationPrefs
                val ns = prefs.settingsFor(settings)
                val posted = PostedKeys.prune(prefs.postedKeys(), today).toMutableSet()
                val series = repo.seriesContext.value

                // Resolve first: notifications about items that were answered, moved or deleted go away (or shrink) at once.
                resolve(today, ns, ledger, rules, forecast, series)

                val decisions = Notifications.reconcile(now, zone, ns, ledger, rules, forecast, posted, series)

                val poster = app.notificationPoster
                for (decision in decisions) {
                    if (decision.action == DecisionAction.CANCEL) {
                        poster.cancel(decision.type, decision.ruleId)
                        continue
                    }
                    if (decision.post) {
                        poster.post(decision) // false when blocked; the bell below still gets its entry
                        posted += decision.key
                    }
                    decision.bellKey?.let { key ->
                        app.notificationCenter.record(decision, key, now)
                        posted += key
                    }
                }
                prefs.savePostedKeys(PostedKeys.prune(posted, today))
                app.notificationCenter.prune(now)
                decisions
            }
        }

    /**
     * The pure [NoticeResolution] decides; this executes it: the bell row goes (and the status-bar notification with it
     * when it is the newest of its kind), or both are rewritten in place without a new sound.
     */
    private suspend fun resolve(
        today: LocalDate,
        settings: com.cashflow.app.engine.NotificationSettings,
        ledger: List<com.cashflow.app.engine.Transaction>,
        rules: List<com.cashflow.app.engine.RecurringRule>,
        forecast: List<com.cashflow.app.engine.DayForecast>,
        series: com.cashflow.app.engine.SeriesContext,
    ) {
        val center = app.notificationCenter
        val rows = center.all()
        val shown = rows.filter { it.key.isNotEmpty() }.map {
            ShownNotice(it.key, it.type, it.relatedDate ?: today, it.title, it.body)
        }
        val plan = NoticeResolution.plan(today, settings, ledger, rules, forecast, shown, series)
        if (plan.isEmpty) return
        val poster = app.notificationPoster
        fun ruleOf(key: String) = if (key.startsWith("LAST|")) key.split("|").getOrNull(1)?.toLongOrNull() else null
        for (key in plan.remove) {
            val row = rows.first { it.key == key }
            val newest = rows.filter { it.type == row.type && (row.type != NotificationType.LAST_PAYMENT || ruleOf(it.key) == ruleOf(key)) }.maxByOrNull { it.createdAt }
            if (newest?.key == key) poster.cancel(row.type, ruleOf(key))
            center.removeKey(key)
        }
        for (u in plan.updates) {
            val row = rows.first { it.key == u.key }
            center.updateText(u.key, u.title, u.body)
            val newest = rows.filter { it.type == row.type }.maxByOrNull { it.createdAt }
            if (newest?.key == u.key && poster.isShown(row.type, ruleOf(u.key))) {
                poster.post(
                    NotificationDecision(
                        key = u.key, type = row.type, title = u.title, body = u.body, relatedDate = row.relatedDate ?: today,
                        route = NotificationRoute.UPCOMING, post = true, bellKey = null,
                    ),
                    silent = true,
                )
            }
        }
    }

    /** The "Send test notification" action: posts a sample at once and adds it to the bell. */
    suspend fun sendTest(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean = withContext(Dispatchers.Default) {
        val today = LocalDate.ofInstant(now, zone)
        val sample = NotificationDecision(
            key = "TEST|${now.toEpochMilli()}",
            type = NotificationType.INFO,
            title = "Test notification",
            body = "Reminders are working.",
            relatedDate = today,
            route = NotificationRoute.UPCOMING,
            post = true,
            bellKey = "TEST|${now.toEpochMilli()}",
        )
        val shown = app.notificationPoster.post(sample)
        app.notificationCenter.record(sample, sample.bellKey!!, now)
        shown
    }
}
