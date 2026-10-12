package com.cashflow.app.engine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class NotificationType { HEADS_UP_WEEK, HEADS_UP_DAY, DAY_OF, SHORTAGE, AUTO_INCOME }

/** Where a tap on a notification (or a bell row) leads. */
enum class NotificationRoute { UPCOMING, CALENDAR }

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
    const val MAX_NAMES = 3

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
    ): List<NotificationDecision> {
        if (!settings.enabled) return emptyList()
        val local = now.atZone(zone)
        val today = local.toLocalDate()
        val time = local.toLocalTime()
        val out = mutableListOf<NotificationDecision>()
        out += dayOf(today, time, settings, items, alreadyPosted)
        if (settings.headsUp) out += headsUps(today, time, settings, items, rules, alreadyPosted)
        if (settings.shortageAlerts) out += shortage(today, time, settings, forecast, alreadyPosted)
        out += autoIncome(today, time, settings, items, alreadyPosted)
        return out
    }

    // ---- day-of summary and nags ----------------------------------------------------------------

    private fun dayOf(
        today: LocalDate,
        time: LocalTime,
        s: NotificationSettings,
        items: List<Transaction>,
        posted: Set<String>,
    ): List<NotificationDecision> {
        val waiting = items.filter {
            it.status == TxStatus.PENDING && !it.date.isAfter(today) &&
                // Repeating income is about to be accepted by itself: it is not asked about.
                !(s.autoAcceptIncome && it.kind == TxKind.INCOME && it.source == TxSource.RECURRING)
        }
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
        val parts = buildList {
            if (expenses > 0) add(Money.formatSigned(TxKind.EXPENSE, expenses))
            if (income > 0) add(Money.formatSigned(TxKind.INCOME, income))
        }
        val bellKey = "DAY|$today|bell"
        return listOf(
            NotificationDecision(
                key = key,
                type = NotificationType.DAY_OF,
                title = "Today: ${waiting.size} to confirm",
                body = parts.joinToString(", "),
                relatedDate = today,
                route = NotificationRoute.UPCOMING,
                post = true,
                bellKey = bellKey.takeIf { it !in posted },
                amountMillimes = income - expenses,
            ),
        )
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
        val byId = rules.associateBy { it.id }
        fun rule(tx: Transaction) = tx.recurringRuleId?.let(byId::get)
        val out = mutableListOf<NotificationDecision>()

        fun group(days: Long, type: NotificationType, prefix: String, title: String, eligible: (Transaction) -> Boolean) {
            val target = today.plusDays(days)
            val key = "$prefix|$target"
            if (key in posted) return
            val list = items.filter { it.status == TxStatus.PENDING && it.kind == TxKind.EXPENSE && it.date == target && eligible(it) }
                .sortedWith(compareBy<Transaction> { it.amount }.thenBy { it.id }.reversed())
            if (list.isEmpty()) return
            val names = list.take(MAX_NAMES).joinToString(", ") { "${nameOf(it)} ${Money.formatSigned(TxKind.EXPENSE, it.amount)}" }
            val more = if (list.size > MAX_NAMES) ", +${list.size - MAX_NAMES} more" else ""
            val first = AppDates.formatShort(target, today)
            out += NotificationDecision(
                key = key,
                type = type,
                title = "$title: ${list.size} ${if (list.size == 1) "payment" else "payments"}",
                body = "$first: $names$more",
                relatedDate = target,
                route = NotificationRoute.UPCOMING,
                post = true,
                bellKey = key,
                amountMillimes = -list.sumOf { it.amount },
            )
        }
        // A week ahead: one-time payments and the ones that come monthly or less often.
        group(7, NotificationType.HEADS_UP_WEEK, "HU7", "In 1 week") { tx -> rule(tx)?.pattern?.let(::monthlyOrLess) ?: true }
        // The day before: everything except daily repeaters.
        group(1, NotificationType.HEADS_UP_DAY, "HU1", "Tomorrow") { tx -> rule(tx)?.pattern != RecurrencePattern.Daily }
        return out
    }

    /** Monthly or less often: the monthly patterns, yearly, and every 28 days or more. */
    fun monthlyOrLess(p: RecurrencePattern): Boolean = when (p) {
        is RecurrencePattern.DayOfMonth, RecurrencePattern.MonthFirst, RecurrencePattern.MonthLast, is RecurrencePattern.Yearly -> true
        is RecurrencePattern.EveryNDays -> p.n >= 28
        RecurrencePattern.Daily, is RecurrencePattern.Weekly, is RecurrencePattern.WeeklyDays -> false
    }

    private fun nameOf(tx: Transaction) = tx.description.trim().ifEmpty { "Expense" }

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
                title = "Short by ${Money.formatDisplay(need)} before ${AppDates.format(day.date)}",
                body = "Save ${Money.formatDisplay(perDay)}/day",
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
                title = "Income added",
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

/** Keeps the set of handled keys small: a key is dropped 14 days after the first date written in it. */
object PostedKeys {
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    const val KEEP_DAYS = 14L

    fun prune(keys: Set<String>, today: LocalDate): Set<String> = keys.filter { k ->
        val date = DATE.find(k)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        date != null && !date.isBefore(today.minusDays(KEEP_DAYS))
    }.toSet()
}
