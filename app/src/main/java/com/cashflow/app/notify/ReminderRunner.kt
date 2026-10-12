package com.cashflow.app.notify

import com.cashflow.app.CashflowApp
import com.cashflow.app.engine.BalanceEngine
import com.cashflow.app.engine.DecisionAction
import com.cashflow.app.engine.NotificationDecision
import com.cashflow.app.engine.NotificationRoute
import com.cashflow.app.engine.NotificationType
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
                val decisions = Notifications.reconcile(now, zone, ns, ledger, rules, forecast, posted)

                val poster = app.notificationPoster
                for (decision in decisions) {
                    if (decision.action == DecisionAction.CANCEL) {
                        poster.cancel(decision.type)
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

    /** The "Send test notification" action: posts a sample at once and adds it to the bell. */
    suspend fun sendTest(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean = withContext(Dispatchers.Default) {
        val today = LocalDate.ofInstant(now, zone)
        val sample = NotificationDecision(
            key = "TEST|${now.toEpochMilli()}",
            type = NotificationType.DAY_OF,
            title = "Today: 3 to confirm",
            body = "−120 TND, +30 TND",
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
