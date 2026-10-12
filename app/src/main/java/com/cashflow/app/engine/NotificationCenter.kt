package com.cashflow.app.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One row of the notification center (the bell's history). */
data class BellEntry(
    val id: Long,
    val type: NotificationType,
    val title: String,
    val body: String,
    val relatedDate: LocalDate?,
    val amountMillimes: Long?,
    val createdAt: Instant,
    val readAt: Instant?,
) {
    val unread get() = readAt == null
}

enum class BellSection { TODAY, EARLIER }

/** The pure rules of the bell: badge text, history size, grouping and the relative time of a row. */
object NotificationCenterModel {
    const val MAX_ENTRIES = 200
    const val MAX_DAYS = 90L
    const val BADGE_CAP = 9

    /** Null when nothing is unread, otherwise the count capped at "9+". */
    fun badge(unread: Int): String? = when {
        unread <= 0 -> null
        unread > BADGE_CAP -> "$BADGE_CAP+"
        else -> unread.toString()
    }

    /** Ids to delete so that at most [MAX_ENTRIES] entries newer than [MAX_DAYS] days remain (whichever is smaller). */
    fun idsToPrune(entries: List<Pair<Long, Instant>>, now: Instant): List<Long> {
        val cutoff = now.minus(MAX_DAYS, ChronoUnit.DAYS)
        val kept = entries.filter { !it.second.isBefore(cutoff) }.sortedByDescending { it.second }.take(MAX_ENTRIES).map { it.first }.toSet()
        return entries.map { it.first }.filter { it !in kept }
    }

    fun sectionOf(createdAt: Instant, now: Instant, zone: ZoneId): BellSection =
        if (createdAt.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()) BellSection.TODAY else BellSection.EARLIER

    /** Newest first, split into Today / Earlier; an empty section is left out. */
    fun group(entries: List<BellEntry>, now: Instant, zone: ZoneId): List<Pair<BellSection, List<BellEntry>>> {
        val sorted = entries.sortedWith(compareByDescending<BellEntry> { it.createdAt }.thenByDescending { it.id })
        return BellSection.entries.mapNotNull { section ->
            val rows = sorted.filter { sectionOf(it.createdAt, now, zone) == section }
            if (rows.isEmpty()) null else section to rows
        }
    }

    /** "Just now", "5 min ago", "3 h ago" for today; "Yesterday" or "03 OCT" before. */
    fun relativeTime(createdAt: Instant, now: Instant, zone: ZoneId): String {
        val minutes = ChronoUnit.MINUTES.between(createdAt, now).coerceAtLeast(0)
        val created = createdAt.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when {
            created == today && minutes < 1 -> "Just now"
            created == today && minutes < 60 -> "$minutes min ago"
            created == today -> "${minutes / 60} h ago"
            created == today.minusDays(1) -> "Yesterday"
            else -> AppDates.formatShort(created, today)
        }
    }
}
