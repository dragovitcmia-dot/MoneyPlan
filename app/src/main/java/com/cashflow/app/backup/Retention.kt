package com.cashflow.app.backup

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Which snapshots to delete. Pure: the clock and the time zone come in as values. */
object Retention {
    const val KEEP_DAYS = 30L
    const val MAX_FILES = 200
    const val MAX_BYTES = 50L * 1024 * 1024

    /**
     * - Everything from TODAY (the local day of [now]) stays, one snapshot per change.
     * - Each earlier day is consolidated into ONE snapshot: the last one of that day. A day's only snapshot is never removed, whatever
     *   its type (Manual, Before update, Before restore).
     * - A day more than [KEEP_DAYS] days back is deleted whole.
     * - Hard caps afterwards: at most [MAX_FILES] files and [MAX_BYTES] bytes, dropping the oldest first.
     */
    fun plan(files: List<SnapshotInfo>, now: Instant, zone: ZoneId): List<SnapshotInfo> {
        val today: LocalDate = now.atZone(zone).toLocalDate()
        val delete = LinkedHashSet<SnapshotInfo>()
        val kept = ArrayList<SnapshotInfo>()
        for ((day, list) in files.groupBy { it.localDay(zone) }) {
            when {
                !day.isBefore(today) -> kept += list
                ChronoUnit.DAYS.between(day, today) > KEEP_DAYS -> delete += list
                else -> {
                    val last = list.maxWith(compareBy<SnapshotInfo> { it.createdAtMillis }.thenBy { it.name })
                    kept += last
                    delete += list.filter { it !== last }
                }
            }
        }
        val oldestFirst = kept.sortedWith(compareBy<SnapshotInfo> { it.createdAtMillis }.thenBy { it.name }).toMutableList()
        var total = oldestFirst.sumOf { it.sizeBytes }
        while (oldestFirst.isNotEmpty() && (oldestFirst.size > MAX_FILES || total > MAX_BYTES)) {
            val drop = oldestFirst.removeAt(0)
            total -= drop.sizeBytes
            delete += drop
        }
        return delete.toList()
    }
}

/** When to take a snapshot, and when a different schema asks for a safety copy. */
object SnapshotScheduler {
    const val DEBOUNCE_MILLIS = 60_000L

    data class Decision(val takeNow: Boolean, val waitMillis: Long) {
        val needed get() = takeNow || waitMillis >= 0
    }

    private fun unsaved(lastChange: Long?, lastSnapshot: Long?) = lastChange != null && (lastSnapshot == null || lastSnapshot < lastChange)

    /**
     * About 60 seconds after the LAST change: many quick edits become one snapshot. A snapshot is due when something changed since
     * the last one and [DEBOUNCE_MILLIS] passed with no further change; otherwise wait that long (or nothing is needed: wait -1).
     */
    fun afterChange(lastChange: Long?, lastSnapshot: Long?, now: Long): Decision {
        if (!unsaved(lastChange, lastSnapshot)) return Decision(false, -1)
        val due = lastChange!! + DEBOUNCE_MILLIS
        return if (now >= due) Decision(true, 0) else Decision(false, due - now)
    }

    /** On app start: a snapshot at once when the last one is older than the last change. */
    fun onStart(lastChange: Long?, lastSnapshot: Long?): Boolean = unsaved(lastChange, lastSnapshot)

    /**
     * An update that carries a different database schema gets a "Before update" copy, taken BEFORE the database is opened.
     * [lastSeen] is the schema version this app saw last time (null on a first install: nothing to protect).
     */
    fun beforeUpdateNeeded(lastSeen: Int?, current: Int, databaseExists: Boolean): Boolean =
        databaseExists && lastSeen != null && lastSeen != current
}
