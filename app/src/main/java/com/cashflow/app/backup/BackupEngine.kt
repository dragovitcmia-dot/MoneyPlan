package com.cashflow.app.backup

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Makes a consistent copy of the live database into [dest] (VACUUM INTO, or a checkpoint and a file copy). */
fun interface DbCopier {
    fun copyTo(dest: File)
}

/** Row counts by table of a database file, for the manifest. */
fun interface RowCounter {
    fun counts(dbFile: File): Map<String, Long>
}

/**
 * Snapshots in one folder. No Android classes: the database copy and the row counts come in through [DbCopier] and
 * [RowCounter], the clock and the zone as values, so the same code runs in the app and in JVM tests on real SQLite files.
 */
class BackupEngine(
    private val dir: File,
    private val copier: DbCopier,
    private val counter: RowCounter,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    private val appVersion: String,
    private val dbVersion: Int,
    private val variant: BackupVariant = BackupVariant.REAL,
) {
    /** Newest first. */
    fun list(): List<SnapshotInfo> =
        dir.listFiles().orEmpty().mapNotNull { SnapshotNames.parse(it.name, it.length()) }
            .sortedWith(compareByDescending<SnapshotInfo> { it.createdAtMillis }.thenByDescending { it.name })

    fun file(info: SnapshotInfo) = File(dir, info.name)

    /** Takes a snapshot of the live database and the settings now. */
    fun create(type: SnapshotType, settings: SettingsSnapshot): SnapshotInfo {
        dir.mkdirs()
        val tmp = File(dir, "db.part")
        tmp.delete()
        try {
            copier.copyTo(tmp)
            val settingsJson = SettingsJson.encode(settings)
            var at = clock()
            while (File(dir, SnapshotNames.nameOf(at, type)).exists()) at++
            val manifest = Manifest(
                format = ManifestCodec.FORMAT,
                appVersion = appVersion,
                dbVersion = dbVersion,
                createdAtMillis = at,
                type = type,
                counts = counter.counts(tmp),
                parts = mapOf(DB_ENTRY to Hashing.sha256(tmp), SETTINGS_ENTRY to Hashing.sha256(settingsJson.toByteArray())),
                variant = variant,
            )
            val target = File(dir, SnapshotNames.nameOf(at, type))
            val partial = File(dir, target.name + ".part")
            BackupArchive.write(partial, manifest, tmp, settingsJson)
            check(partial.renameTo(target)) { "could not store the snapshot" }
            return SnapshotInfo(target.name, at, type, target.length())
        } finally {
            tmp.delete()
        }
    }

    /** Applies [Retention]: consolidates earlier days, deletes the old and enforces the caps. Returns the names deleted. */
    fun prune(): List<String> {
        val doomed = Retention.plan(list(), Instant.ofEpochMilli(clock()), zone())
        return doomed.filter { file(it).delete() }.map { it.name }
    }

    /** Opens [file] (a snapshot or an imported backup) and checks it against this app's schema. */
    fun check(file: File, minDbVersion: Int): BackupCheck {
        val opened = BackupArchive.open(file)
        return BackupValidator.check(opened.manifest, opened.actualSha, dbVersion, minDbVersion, variant)
    }

    /** Writes the database of [file] to [targetDb] (through a temporary file, so a failure never leaves half a database). */
    fun extractDatabase(file: File, targetDb: File): Boolean {
        val part = File(targetDb.path + ".restore")
        part.delete()
        if (!BackupArchive.extract(file, DB_ENTRY, part)) return false
        targetDb.delete()
        return part.renameTo(targetDb)
    }

    fun readSettings(file: File): SettingsSnapshot? = BackupArchive.readText(file, SETTINGS_ENTRY)?.let(SettingsJson::decode)

    companion object {
        /** cashflow-2026-10-13.cfbackup; a Lab export is cashflow-lab-2026-10-13.cfbackup. */
        fun exportName(day: LocalDate, variant: BackupVariant = BackupVariant.REAL) =
            if (variant == BackupVariant.LAB) "cashflow-lab-$day.cfbackup" else "cashflow-$day.cfbackup"
    }
}
