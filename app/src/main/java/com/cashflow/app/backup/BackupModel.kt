package com.cashflow.app.backup

import com.cashflow.app.engine.AppDates
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Why a snapshot exists. */
enum class SnapshotType(val code: String, val tag: String) {
    AUTO("auto", "Auto"),
    MANUAL("manual", "Manual"),
    BEFORE_UPDATE("update", "Before update"),
    BEFORE_RESTORE("restore", "Before restore");

    companion object {
        fun ofCode(code: String): SnapshotType? = entries.firstOrNull { it.code == code }
    }
}

/** One stored snapshot file. The file name carries its time and type: snap-<epoch millis>-<code>.cfb */
data class SnapshotInfo(val name: String, val createdAtMillis: Long, val type: SnapshotType, val sizeBytes: Long) {
    fun localDay(zone: ZoneId): LocalDate = Instant.ofEpochMilli(createdAtMillis).atZone(zone).toLocalDate()
}

object SnapshotNames {
    const val EXTENSION = "cfb"
    private val PATTERN = Regex("""snap-(\d+)-([a-z]+)\.$EXTENSION""")

    fun nameOf(createdAtMillis: Long, type: SnapshotType) = "snap-$createdAtMillis-${type.code}.$EXTENSION"

    /** Time and type from a file name; null for anything that is not a snapshot file. */
    fun parse(name: String, sizeBytes: Long): SnapshotInfo? {
        val m = PATTERN.matchEntire(name) ?: return null
        val type = SnapshotType.ofCode(m.groupValues[2]) ?: return null
        return SnapshotInfo(name, m.groupValues[1].toLong(), type, sizeBytes)
    }
}

/** How a snapshot is shown in the list: "13 OCT 2026, 07:52", its tag, and its size in KB. */
object SnapshotLabel {
    private val clock = DateTimeFormatter.ofPattern("HH:mm", AppDates.LOCALE)

    fun time(info: SnapshotInfo, zone: ZoneId): String {
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(info.createdAtMillis), zone)
        return AppDates.format(local.toLocalDate()) + ", " + local.format(clock)
    }

    fun tag(info: SnapshotInfo) = info.type.tag

    /** Rounded to the nearest KB, at least 1. */
    fun sizeKb(bytes: Long): Long = maxOf(1L, (bytes + 512) / 1024)

    fun size(info: SnapshotInfo) = "${sizeKb(info.sizeBytes)} KB"
}

/** The one-line summary of the Backups row in Settings: "Last backup 19 OCT 2026, 16:24 · 7 saved" (newest first), or "No backups yet". */
object BackupSummary {
    fun line(snapshots: List<SnapshotInfo>, zone: ZoneId): String {
        val newest = snapshots.maxByOrNull { it.createdAtMillis } ?: return "No backups yet"
        return "Last backup ${SnapshotLabel.time(newest, zone)} · ${snapshots.size} saved"
    }
}
