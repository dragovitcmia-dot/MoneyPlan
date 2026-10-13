package com.cashflow.app.engine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class NotificationType { HEADS_UP_WEEK, HEADS_UP_DAY, DAY_OF, SHORTAGE, AUTO_INCOME, INFO, LAST_PAYMENT }

/** Where a tap on a notification (or a bell row) leads. */
enum class NotificationRoute { UPCOMING, CALENDAR, SERIES }

enum class DecisionAction { POST, CANCEL }

/** The Notifications card of Settings plus the two inputs the rules need from the rest of the settings. */
data class NotificationSettings(
    /** Master switch. Off: nothing is posted and nothing goes into the bell. */
    val enabled: Boolean = true,
    val reminderTime: LocalTime = LocalTime.NOON,
    val headsUp: Boolean = true,
    /** Repeat the day-of notification every 2 hours while something due today is still waiting. */
    val keepReminding: Boolean = true,
    val shortageAlerts: Boolean = true,
    /** From [AppSettings.autoAcceptIncome]: income of repeating series is accepted by itself at [reminderTime]. */
    val autoAcceptIncome: Boolean = false,
)

/**
 * What the Android layer must do. It only executes decisions and then records the keys: [key] when [post] is
 * true (the status-bar notification was shown) and [bellKey] when it is non-null (a bell entry was written).
 */
data class NotificationDecision(
    val key: String,
    val type: NotificationType,
    val title: String,
    val body: String,
    val relatedDate: LocalDate,
    val route: NotificationRoute,
    /** Show a status-bar notification. False: bell entry only. */
    val post: Boolean,
    /** Unique key of the bell entry to write; null when this decision adds nothing to the bell. */
    val bellKey: String?,
    val amountMillimes: Long? = null,
    val action: DecisionAction = DecisionAction.POST,
    /** The series a [NotificationRoute.SERIES] tap opens. */
    val ruleId: Long? = null,
    /** A single waiting item of an "ask me the amount" series: the tap opens its amount sheet. */
    val itemId: Long? = null,
)

/**
 * The single source of truth for reminders: a pure function of the clock, the settings, the data and the keys that
 * were already handled. Running it again (or late) never produces a duplicate and never resurrects a stale reminder.
 *
 * Time rules:
 *  - Quiet hours are 22:00-08:00. Nothing is posted inside them. A reminder time inside them counts as 08:00 of the
 *    same day (the next allowed moment of that day).
 *  - Heads-ups belong to their own morning: they are posted from the reminder time until six hours later, never after.
 *  - The day-of summary and its nags are the exception: while something due today is waiting, the latest slot that has
 *    been reached is posted whenever the run happens (catch-up), all day until quiet hours.
 */
object Notifications {
    val QUIET_START: LocalTime = LocalTime.of(22, 0)
    val QUIET_END: LocalTime = LocalTime.of(8, 0)
    const val NAG_STEP_HOURS = 2L
    val NAG_LAST: LocalTime = LocalTime.of(21, 0)
    const val HEADS_UP_WINDOW_HOURS = 6L
    const val SHORTAGE_HORIZON_DAYS = 60L

    fun isQuiet(t: LocalTime): Boolean = !t.isBefore(QUIET_START) || t.isBefore(QUIET_END)

    /** The first moment on the same day at which a reminder scheduled for [t] may be shown. */
    fun effective(t: LocalTime): LocalTime = if (isQuiet(t)) QUIET_END else t

    /** The times of day at which the day-of notification is (re)posted: the reminder time, then every 2 hours up to 21:00. */
    fun daySlots(reminder: LocalTime, keepReminding: Boolean): List<LocalTime> {
        val slots = mutableListOf(effective(reminder))
        if (keepReminding) {
            var t = reminder
            while (true) {
                val next = t.plusHours(NAG_STEP_HOURS)
                if (next.isBefore(t) || next.isAfter(NAG_LAST)) break // wrapped past midnight, or later than 21:00
                t = next
                val eff = effective(t)
                if (eff.isAfter(slots.last())) slots += eff
            }
        }
        return slots
    }

    /** Smallest multiple of 0.01 TND (10 millimes) that is at least need / days. */
    fun perDayToSave(need: Long, days: Long): Long {
        val d = maxOf(1L, days) * 10
        return (need + d - 1) / d * 10
    }

    fun reconcile(
        now: Instant,
        zone: ZoneId,
        settings: NotificationSettings,
        items: List<Transaction>,
        rules: List<RecurringRule>,
        forecast: List<DayForecast>,
        alreadyPosted: Set<String>,
        series: SeriesContext = SeriesContext(),
    ): List<NotificationDecision> {
        if (!settings.enabled) return emptyList()
        val local = now.atZone(zone)
        val today = local.toLocalDate()
        val time = local.toLocalTime()
        val out = mutableListOf<NotificationDecision>()
        out += dayOf(today, time, settings, items, alreadyPosted, series)
        if (settings.headsUp) out += headsUps(today, time, settings, items, rules, alreadyPosted)
        if (settings.shortageAlerts) out += shortage(today, time, settings, forecast, alreadyPosted)
        out += autoIncome(today, time, settings, items, alreadyPosted)
        if (settings.headsUp) out += lastPayments(today, time, settings, items, rules, series, alreadyPosted)
        return out
    }

    // ---- day-of summary and nags ----------------------------------------------------------------

    private fun dayOf(
        today: LocalDate,
        time: LocalTime,
        s: NotificationSettings,
        items: List<Transaction>,
        posted: Set<String>,
        series: SeriesContext,
    ): List<NotificationDecision> {
        val waiting = waitingSet(today, s.autoAcceptIncome, items)
        if (waiting.isEmpty()) {
            val leftover = posted.any { k -> isDayKey(k, today) || isDayKey(k, today.minusDays(1)) }
            return if (leftover) {
                listOf(
                    NotificationDecision(
                        key = "CANCEL|DAY|$today", type = NotificationType.DAY_OF, title = "", body = "",
                        relatedDate = today, route = NotificationRoute.UPCOMING, post = false, bellKey = null,
                        action = DecisionAction.CANCEL,
                    ),
                )
            } else {
                emptyList()
            }
        }
        if (isQuiet(time)) return emptyList()
        val slots = daySlots(s.reminderTime, s.keepReminding)
        val index = if (s.keepReminding) slots.indexOfLast { !time.isBefore(it) } else if (!time.isBefore(slots[0])) 0 else -1
        if (index < 0) return emptyList()
        val key = "DAY|$today|${slots[index]}"
        if (key in posted) return emptyList()
        val expenses = waiting.filter { it.kind == TxKind.EXPENSE }.sumOf { it.amount }
        val income = waiting.filter { it.kind == TxKind.INCOME }.sumOf { it.amount }
        val again = posted.any { isDayKey(it, today) } // something was already said about today: this one is a nag
        val (title, body) = dayText(waiting, again)
        val bellKey = "DAY|$today|bell"
        return listOf(
            NotificationDecision(
                key = key,
                type = NotificationType.DAY_OF,
                title = title,
                body = body,
                relatedDate = today,
                route = NotificationRoute.UPCOMING,
                post = true,
                bellKey = bellKey.takeIf { it !in posted },
                amountMillimes = income - expenses,
                itemId = waiting.singleOrNull()?.takeIf { it.recurringRuleId in series.askAmountRuleIds }?.id,
            ),
        )
    }

    /** PENDING items due today or earlier that the day summary asks about (income that repeats is accepted by itself when that is on). */
    fun waitingSet(today: LocalDate, autoAccept: Boolean, items: List<Transaction>): List<Transaction> = items.filter {
        it.status == TxStatus.PENDING && !it.date.isAfter(today) &&
            !(autoAccept && it.kind == TxKind.INCOME && it.source == TxSource.RECURRING)
    }

    /** The title and body of the day-of notification; [again] makes it a nag ("Still waiting"). */
    fun dayText(waiting: List<Transaction>, again: Boolean): Pair<String, String> {
        val expenses = waiting.filter { it.kind == TxKind.EXPENSE }.sumOf { it.amount }
        val income = waiting.filter { it.kind == TxKind.INCOME }.sumOf { it.amount }
        val title = when {
            again -> "Still waiting"
            expenses == 0L -> "Confirm today's income"
            else -> "Confirm today's payments"
        }
        val body = if (waiting.size == 1) {
            val only = waiting.single()
            "${nameOf(only)} ${Money.formatSigned(only.kind, only.amount)}"
        } else {
            buildList {
                add("${waiting.size} items")
                if (expenses > 0) add(Money.formatSigned(TxKind.EXPENSE, expenses))
                if (income > 0) add(Money.formatSigned(TxKind.INCOME, income))
            }.joinToString(", ")
        }
        return title to body
    }

    private fun isDayKey(key: String, date: LocalDate) = key.startsWith("DAY|$date|") && !key.endsWith("|bell")

    // ---- heads-ups ---------------------------------------------------------------------------------

    private fun headsUps(
        today: LocalDate,
        time: LocalTime,
        s: NotificationSettings,
        items: List<Transaction>,
        rules: List<RecurringRule>,
        posted: Set<String>,
    ): List<NotificationDecision> {
        if (isQuiet(time)) return emptyList()
        val start = effective(s.reminderTime)
        val end = start.plusHours(HEADS_UP_WINDOW_HOURS)
        val inWindow = !time.isBefore(start) && (end.isBefore(start) || time.isBefore(end))
        if (!inWindow) return emptyList()
        val out = mutableListOf<NotificationDecision>()

        fun group(days: Long, type: NotificationType, prefix: String, week: Boolean) {
            val target = today.plusDays(days)
            val key = "$prefix|$target"
            if (key in posted) return
            val list = headsUpItems(items, rules, target, week)
            if (list.isEmpty()) return
            val (title, text) = headsUpText(list, target, week)
            out += NotificationDecision(
                key = key,
                type = type,
                title = title,
                body = text,
                relatedDate = target,
                route = NotificationRoute.UPCOMING,
                post = true,
                bellKey = key,
                amountMillimes = -list.sumOf { it.amount },
            )
        }
        // A week ahead: one-time payments and the ones that come monthly or less often.
        group(7, NotificationType.HEADS_UP_WEEK, "HU7", week = true)
        // The day before: everything except daily repeaters.
        group(1, NotificationType.HEADS_UP_DAY, "HU1", week = false)
        return out
    }

    /** The waiting expenses a heads-up for [target] is about (a week ahead: one-time and monthly or rarer; a day ahead: all but daily). */
    fun headsUpItems(items: List<Transaction>, rules: List<RecurringRule>, target: LocalDate, week: Boolean): List<Transaction> {
        val byId = rules.associateBy { it.id }
        return items.filter { it.status == TxStatus.PENDING && it.kind == TxKind.EXPENSE && it.date == target }
            .filter { tx ->
                val pattern = tx.recurringRuleId?.let(byId::get)?.pattern
                if (week) pattern?.let(::monthlyOrLess) ?: true else pattern != RecurrencePattern.Daily
            }
            .sortedWith(compareBy<Transaction> { it.amount }.thenBy { it.id }.reversed())
    }

    fun headsUpText(list: List<Transaction>, target: LocalDate, week: Boolean): Pair<String, String> {
        val title = if (week) "Coming up" else "Tomorrow"
        val text = if (list.size == 1) {
            "${nameOf(list[0])} ${Money.formatSigned(TxKind.EXPENSE, list[0].amount)}, ${AppDates.dayMonth(target)}"
        } else {
            "${list.size} payments ${if (week) "next week" else "tomorrow"}, ${Money.formatSigned(TxKind.EXPENSE, list.sumOf { it.amount })}"
        }
        return title to text
    }

    /** Monthly or less often: the monthly patterns, yearly, and every 28 days or more. */
    fun monthlyOrLess(p: RecurrencePattern): Boolean = when (p) {
        is RecurrencePattern.DayOfMonth, RecurrencePattern.MonthFirst, RecurrencePattern.MonthLast, is RecurrencePattern.Yearly -> true
        is RecurrencePattern.EveryNDays -> p.n >= 28
        RecurrencePattern.Daily, is RecurrencePattern.Weekly, is RecurrencePattern.WeeklyDays -> false
    }

    internal fun nameOf(tx: Transaction) = tx.description.trim().ifEmpty { if (tx.kind == TxKind.INCOME) "Income" else "Expense" }

    // ---- shortage alert ----------------------------------------------------------------------------

    private fun shortage(
        today: LocalDate,
        time: LocalTime,
        s: NotificationSettings,
        forecast: List<DayForecast>,
        posted: Set<String>,
    ): List<NotificationDecision> {
        val day = forecast.firstOrNull {
            !it.date.isBefore(today) && it.closing < 0 && ChronoUnit.DAYS.between(today, it.date) <= SHORTAGE_HORIZON_DAYS
        } ?: return emptyList()
        val need = -day.closing
        val until = ChronoUnit.DAYS.between(today, day.date)
        val perDay = perDayToSave(need, until)
        val osKey = "SHORT|$today|${day.date}|$need"
        val bellKey = "SHORT|BELL|${day.date}|$need"
        val bellPending = bellKey !in posted
        val postDue = !isQuiet(time) && !time.isBefore(effective(s.reminderTime)) && osKey !in posted
        if (!bellPending && !postDue) return emptyList()
        return listOf(
            NotificationDecision(
                key = osKey,
                type = NotificationType.SHORTAGE,
                title = "Low balance ahead",
                body = "Short by ${Money.formatDisplay(need)} before ${AppDates.dayMonth(day.date)}, save ${Money.formatDisplay(perDay, withCurrency = false)}/day",
                relatedDate = day.date,
                route = NotificationRoute.CALENDAR,
                post = postDue,
                bellKey = bellKey.takeIf { bellPending },
                amountMillimes = need,
            ),
        )
    }

    // ---- income accepted by itself ---------------------------------------------------------------------

    private fun autoIncome(
        today: LocalDate,
        time: LocalTime,
        s: NotificationSettings,
        items: List<Transaction>,
        posted: Set<String>,
    ): List<NotificationDecision> {
        if (!s.autoAcceptIncome || time.isBefore(s.reminderTime)) return emptyList()
        val key = "AUTO|$today"
        if (key in posted) return emptyList()
        val list = items.filter {
            it.status == TxStatus.CONFIRMED && it.kind == TxKind.INCOME && it.source == TxSource.RECURRING && it.date == today
        }
        if (list.isEmpty()) return emptyList()
        val total = list.sumOf { it.amount }
        return listOf(
            NotificationDecision(
                key = key,
                type = NotificationType.AUTO_INCOME,
                title = "Income received",
                body = Money.formatSigned(TxKind.INCOME, total),
                relatedDate = today,
                route = NotificationRoute.UPCOMING,
                post = false, // accepted by itself: no status-bar notification, only the bell
                bellKey = key,
                amountMillimes = total,
            ),
        )
    }
}

/** The "Last payment" reminder, and the pure decision about which shown notifications are resolved. */
internal fun Notifications.lastPayments(
    today: LocalDate,
    time: LocalTime,
    s: NotificationSettings,
    items: List<Transaction>,
    rules: List<RecurringRule>,
    series: SeriesContext,
    posted: Set<String>,
): List<NotificationDecision> {
    if (isQuiet(time) || time.isBefore(effective(s.reminderTime))) return emptyList()
    val out = mutableListOf<NotificationDecision>()
    for (rule in rules) {
        if (!rule.enabled || rule.endDate == null || rule.id in series.endedRuleIds) continue
        val last = SeriesLast.lastDate(rule, items) ?: continue
        // From the first day on or after "a week before" until the day itself; later it is too late to be useful.
        if (today.isBefore(last.minusDays(7)) || today.isAfter(last)) continue
        val key = Notifications.lastKey(rule.id, last)
        if (key in posted) continue
        val name = rule.description.trim().ifEmpty { if (rule.kind == TxKind.INCOME) "Income" else "Expense" }
        out += NotificationDecision(
            key = key,
            type = NotificationType.LAST_PAYMENT,
            title = "Last payment",
            body = "$name ${Money.formatSigned(rule.kind, rule.amount)} ends ${AppDates.dayMonth(last)}. Extend?",
            relatedDate = last,
            route = NotificationRoute.SERIES,
            post = true,
            bellKey = key,
            amountMillimes = if (rule.kind == TxKind.INCOME) rule.amount else -rule.amount,
            ruleId = rule.id,
        )
    }
    return out
}

fun Notifications.lastKey(ruleId: Long, last: LocalDate) = "LAST|$ruleId|$last"

/** A notification that is on show (its bell row), as the resolution below sees it. */
data class ShownNotice(val key: String, val type: NotificationType, val relatedDate: LocalDate, val title: String, val body: String)

data class NoticeUpdate(val key: String, val title: String, val body: String)

/** Which shown notifications to take away (status bar and bell row) and which to rewrite in place. */
data class ResolutionPlan(val remove: List<String>, val updates: List<NoticeUpdate>) {
    val isEmpty get() = remove.isEmpty() && updates.isEmpty()
}

/**
 * When an item changes state, the notifications that were about it must follow: gone when everything they mention is
 * resolved, rewritten in place (fewer items, new total) when some of it remains, untouched when the change was about
 * something else. Informational rows (income received, tests) and shortage history stay.
 */
object NoticeResolution {
    fun plan(
        today: LocalDate,
        settings: NotificationSettings,
        items: List<Transaction>,
        rules: List<RecurringRule>,
        forecast: List<DayForecast>,
        shown: List<ShownNotice>,
        series: SeriesContext = SeriesContext(),
    ): ResolutionPlan {
        val remove = mutableListOf<String>()
        val updates = mutableListOf<NoticeUpdate>()
        val waiting = Notifications.waitingSet(today, settings.autoAcceptIncome, items)
        val anyShortage = forecast.any {
            !it.date.isBefore(today) && it.closing < 0 && ChronoUnit.DAYS.between(today, it.date) <= Notifications.SHORTAGE_HORIZON_DAYS
        }
        for (n in shown) {
            when (n.type) {
                NotificationType.DAY_OF -> {
                    if (waiting.isEmpty()) {
                        remove += n.key
                    } else if (n.relatedDate == today) {
                        val (title, body) = Notifications.dayText(waiting, again = n.title == "Still waiting")
                        if (title != n.title || body != n.body) updates += NoticeUpdate(n.key, title, body)
                    }
                }
                NotificationType.HEADS_UP_WEEK, NotificationType.HEADS_UP_DAY -> {
                    val week = n.type == NotificationType.HEADS_UP_WEEK
                    val list = Notifications.headsUpItems(items, rules, n.relatedDate, week)
                    if (list.isEmpty()) {
                        remove += n.key
                    } else {
                        val (title, body) = Notifications.headsUpText(list, n.relatedDate, week)
                        if (title != n.title || body != n.body) updates += NoticeUpdate(n.key, title, body)
                    }
                }
                NotificationType.SHORTAGE -> if (!anyShortage) remove += n.key
                NotificationType.LAST_PAYMENT -> {
                    val ruleId = n.key.split("|").getOrNull(1)?.toLongOrNull()
                    val rule = rules.firstOrNull { it.id == ruleId }
                    val stillLast = rule != null && rule.enabled && rule.endDate != null && rule.id !in series.endedRuleIds &&
                        SeriesLast.lastDate(rule, items) == n.relatedDate
                    if (!stillLast) remove += n.key
                }
                NotificationType.AUTO_INCOME, NotificationType.INFO -> Unit
            }
        }
        return ResolutionPlan(remove, updates)
    }
}

/** Keeps the set of handled keys small: a key is dropped 14 days after the first date written in it. */
object PostedKeys {
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    const val KEEP_DAYS = 14L

    fun prune(keys: Set<String>, today: LocalDate): Set<String> = keys.filter { k ->
        val date = DATE.find(k)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        date != null && !date.isBefore(today.minusDays(KEEP_DAYS))
    }.toSet()
}
