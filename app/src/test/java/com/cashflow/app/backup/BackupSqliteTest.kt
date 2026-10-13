package com.cashflow.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.time.ZoneId

/** The real code path on real SQLite files: copy, archive, checksum, restore; compared row for row. */
class BackupSqliteTest {
    private val zone = ZoneId.of("Africa/Tunis")

    private fun dir(): File = Files.createTempDirectory("cfsql").toFile()

    private fun connect(file: File): Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")

    /** A database with the app's three tables (the same columns as Room's v2 schema). */
    private fun createDb(file: File, wal: Boolean = false): Connection {
        val c = connect(file)
        c.createStatement().use { s ->
            if (wal) s.execute("PRAGMA journal_mode=WAL")
            s.execute(
                "CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, date TEXT NOT NULL, amount INTEGER NOT NULL, kind TEXT NOT NULL, " +
                    "description TEXT NOT NULL, status TEXT NOT NULL, source TEXT NOT NULL, recurringRuleId INTEGER, occurrenceDate TEXT, isPlanned INTEGER NOT NULL)",
            )
            s.execute(
                "CREATE TABLE recurring_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, amount INTEGER NOT NULL, kind TEXT NOT NULL, description TEXT NOT NULL, " +
                    "patternType TEXT NOT NULL, patternValue INTEGER, anchorDate TEXT, startDate TEXT NOT NULL, endDate TEXT, enabled INTEGER NOT NULL, confirmMinutes INTEGER NOT NULL)",
            )
            s.execute("CREATE TABLE settings (id INTEGER PRIMARY KEY NOT NULL, startingBalance INTEGER NOT NULL, balanceStartDate TEXT NOT NULL, walletMode TEXT NOT NULL, reminderMinutes INTEGER NOT NULL)")
        }
        return c
    }

    private fun fill(c: Connection, rows: Int = 300) {
        c.autoCommit = false
        c.prepareStatement("INSERT INTO transactions (date, amount, kind, description, status, source, recurringRuleId, occurrenceDate, isPlanned) VALUES (?,?,?,?,?,?,?,?,?)").use { p ->
            for (i in 0 until rows) {
                p.setString(1, "2026-10-%02d".format(i % 28 + 1))
                p.setLong(2, 1_000L * (i + 1) + i)
                p.setString(3, if (i % 3 == 0) "INCOME" else "EXPENSE")
                p.setString(4, "Item é ü — $i \"q\"")
                p.setString(5, listOf("PENDING", "CONFIRMED", "SKIPPED")[i % 3])
                p.setString(6, if (i % 2 == 0) "MANUAL" else "RECURRING")
                if (i % 2 == 0) p.setNull(7, java.sql.Types.INTEGER) else p.setLong(7, 1L)
                if (i % 2 == 0) p.setNull(8, java.sql.Types.VARCHAR) else p.setString(8, "2026-10-%02d".format(i % 28 + 1))
                p.setInt(9, 1)
                p.addBatch()
            }
            p.executeBatch()
        }
        c.createStatement().use {
            it.execute("INSERT INTO recurring_rules (amount, kind, description, patternType, patternValue, anchorDate, startDate, endDate, enabled, confirmMinutes) VALUES (75000,'EXPENSE','Sunday expense','WEEKLY',7,NULL,'2026-10-11','2026-12-27',1,720)")
            it.execute("INSERT INTO settings VALUES (1, 180000, '2026-10-10', 'DYNAMIC', 720)")
        }
        c.commit()
        c.autoCommit = true
    }

    private fun dump(file: File): Map<String, List<List<Any?>>> = connect(file).use { c ->
        listOf("transactions", "recurring_rules", "settings").associateWith { table ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT * FROM $table ORDER BY id").use { rs ->
                    val n = rs.metaData.columnCount
                    val out = ArrayList<List<Any?>>()
                    while (rs.next()) out += (1..n).map { rs.getObject(it) }
                    out
                }
            }
        }
    }

    private val counter = RowCounter { f ->
        connect(f).use { c ->
            listOf("transactions", "recurring_rules").associateWith { t ->
                c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM $t").use { it.next(); it.getLong(1) } }
            }
        }
    }

    private fun vacuumInto(live: File) = DbCopier { dest ->
        connect(live).use { c -> c.createStatement().use { it.execute("VACUUM INTO '${dest.path.replace("'", "''")}'") } }
    }

    /** The fallback for old SQLite versions: checkpoint the write-ahead log, then copy the file while nothing writes. */
    private fun checkpointCopy(live: File) = DbCopier { dest ->
        connect(live).use { c ->
            c.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            live.copyTo(dest, overwrite = true)
        }
    }

    private fun engine(root: File, copier: DbCopier, clockMillis: () -> Long = { 1_760_000_000_000 }) =
        BackupEngine(File(root, "backups"), copier, counter, clockMillis, { zone }, "0.9.0", 2)

    private val settings = SettingsSnapshot(mapOf("settings_prefs" to mapOf<String, Any>("auto_accept_income" to true, "ask_amount_rule_ids" to setOf("1", "4"))))

    @Test fun vacuum_into_snapshot_restores_row_for_row() {
        val root = dir()
        val live = File(root, "live.db")
        createDb(live).use { fill(it) }
        val original = dump(live)
        val e = engine(root, vacuumInto(live))
        val info = e.create(SnapshotType.MANUAL, settings)
        // the live database moves on after the snapshot
        connect(live).use { it.createStatement().use { s -> s.execute("DELETE FROM transactions"); s.execute("UPDATE settings SET startingBalance = 1") } }
        assertNotEquals(original, dump(live))
        val target = File(root, "restored.db")
        assertTrue(e.check(e.file(info), 2) is BackupCheck.Ok)
        assertTrue(e.extractDatabase(e.file(info), target))
        assertEquals(original, dump(target))
        assertEquals(settings, e.readSettings(e.file(info)))
    }

    @Test fun checkpoint_and_copy_captures_what_is_still_in_the_wal() {
        val root = dir()
        val live = File(root, "live.db")
        val c = createDb(live, wal = true)
        fill(c, 120)
        // a plain copy of the main file now would miss the rows that live only in the WAL
        val naive = File(root, "naive.db")
        live.copyTo(naive)
        val e = engine(root, checkpointCopy(live))
        val info = e.create(SnapshotType.AUTO, settings)
        val original = dump(live)
        c.close()
        val restored = File(root, "restored.db")
        assertTrue(e.extractDatabase(e.file(info), restored))
        assertEquals(original, dump(restored))
        assertEquals(120, dump(restored).getValue("transactions").size)
        // (the naive copy is the thing the checkpoint prevents: it holds fewer rows or none)
        val naiveRows = runCatching { dump(naive).getValue("transactions").size }.getOrDefault(0)
        assertTrue(naiveRows <= 120)
    }

    @Test fun the_manifest_counts_and_checksums_describe_the_snapshot() {
        val root = dir()
        val live = File(root, "live.db")
        createDb(live).use { fill(it, 77) }
        val e = engine(root, vacuumInto(live))
        val info = e.create(SnapshotType.BEFORE_UPDATE, settings)
        val opened = BackupArchive.open(e.file(info))
        val m = opened.manifest!!
        assertEquals(77L, m.items)
        assertEquals(1L, m.counts["recurring_rules"])
        assertEquals(2, m.dbVersion)
        assertEquals("0.9.0", m.appVersion)
        assertEquals(SnapshotType.BEFORE_UPDATE, m.type)
        assertEquals(m.parts, opened.actualSha)
        assertEquals(info.createdAtMillis, m.createdAtMillis)
    }

    @Test fun listing_is_newest_first_and_ignores_temporary_files() {
        val root = dir()
        val live = File(root, "live.db")
        createDb(live).use { fill(it, 5) }
        var t = 1_760_000_000_000L
        val e = engine(root, vacuumInto(live)) { t }
        val a = e.create(SnapshotType.AUTO, settings)
        t += 5_000
        val b = e.create(SnapshotType.MANUAL, settings)
        File(root, "backups/db.part").writeText("leftover")
        assertEquals(listOf(b.name, a.name), e.list().map { it.name })
        assertEquals(SnapshotType.MANUAL, e.list().first().type)
        assertTrue(e.list().all { it.sizeBytes > 0 })
    }

    @Test fun two_snapshots_in_the_same_millisecond_get_different_files() {
        val root = dir()
        val live = File(root, "live.db")
        createDb(live).use { fill(it, 5) }
        val e = engine(root, vacuumInto(live)) { 1_760_000_000_000 }
        val a = e.create(SnapshotType.AUTO, settings)
        val b = e.create(SnapshotType.AUTO, settings)
        assertNotEquals(a.name, b.name)
        assertEquals(2, e.list().size)
    }

    @Test fun pruning_consolidates_yesterday_and_deletes_the_extras_on_disk() {
        val root = dir()
        val live = File(root, "live.db")
        createDb(live).use { fill(it, 5) }
        val yesterday = 1_760_000_000_000L - 24 * 3_600_000L
        var t = yesterday
        val e = engine(root, vacuumInto(live)) { t }
        repeat(3) { e.create(SnapshotType.AUTO, settings); t += 3_600_000L }
        t = 1_760_000_000_000L
        e.create(SnapshotType.AUTO, settings)
        val deleted = e.prune()
        assertEquals(2, deleted.size)
        assertEquals(2, e.list().size)
        assertTrue(e.list().none { it.name in deleted })
    }

    @Test fun a_corrupted_snapshot_fails_its_checksum() {
        val root = dir()
        val live = File(root, "live.db")
        createDb(live).use { fill(it, 50) }
        val e = engine(root, vacuumInto(live))
        val info = e.create(SnapshotType.AUTO, settings)
        val bytes = e.file(info).readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        e.file(info).writeBytes(bytes)
        assertFalse(e.check(e.file(info), 2) is BackupCheck.Ok)
    }

    @Test fun a_snapshot_from_a_newer_schema_is_refused_and_one_from_an_older_supported_schema_is_accepted() {
        val root = dir()
        val live = File(root, "live.db")
        createDb(live).use { fill(it, 5) }
        val newer = BackupEngine(File(root, "newer"), vacuumInto(live), counter, { 1L }, { zone }, "9.9", 5)
        val info = newer.create(SnapshotType.MANUAL, settings)
        val current = engine(root, vacuumInto(live))
        assertEquals(BackupCheck.TooNew(5), current.check(newer.file(info), 2))
        val older = BackupEngine(File(root, "older"), vacuumInto(live), counter, { 2L }, { zone }, "0.1", 2)
        val oldInfo = older.create(SnapshotType.MANUAL, settings)
        val future = BackupEngine(File(root, "future"), vacuumInto(live), counter, { 3L }, { zone }, "1.0", 4)
        assertTrue(future.check(older.file(oldInfo), 2) is BackupCheck.Ok)
    }

    @Test fun an_export_is_a_copy_that_imports_like_a_snapshot() {
        val root = dir()
        val live = File(root, "live.db")
        createDb(live).use { fill(it, 40) }
        val original = dump(live)
        val e = engine(root, vacuumInto(live))
        val info = e.create(SnapshotType.MANUAL, settings)
        val export = File(root, BackupEngine.exportName(java.time.LocalDate.of(2026, 10, 13)))
        e.file(info).copyTo(export)
        // another phone's engine opens the file it was handed
        val other = engine(dir(), vacuumInto(live))
        val check = other.check(export, 2)
        assertTrue(check is BackupCheck.Ok)
        assertEquals(40L, (check as BackupCheck.Ok).manifest.items)
        val restored = File(root, "other.db")
        assertTrue(other.extractDatabase(export, restored))
        assertEquals(original, dump(restored))
    }

    @Test fun restoring_over_an_existing_database_replaces_it_completely() {
        val root = dir()
        val live = File(root, "live.db")
        createDb(live).use { fill(it, 30) }
        val original = dump(live)
        val e = engine(root, vacuumInto(live))
        val info = e.create(SnapshotType.AUTO, settings)
        connect(live).use { it.createStatement().use { s -> s.execute("INSERT INTO settings VALUES (2, 5, '2026-01-01', 'DYNAMIC', 1)"); s.execute("DELETE FROM recurring_rules") } }
        assertTrue(e.extractDatabase(e.file(info), live))
        assertEquals(original, dump(live))
        assertFalse(File(live.path + ".restore").exists())
    }

    @Test fun a_snapshot_taken_during_a_big_transaction_is_still_consistent() {
        val root = dir()
        val live = File(root, "live.db")
        val c = createDb(live)
        fill(c, 200)
        c.autoCommit = false // an open, uncommitted write: the snapshot must see only committed data
        c.createStatement().use { it.execute("DELETE FROM transactions") }
        val e = engine(root, vacuumInto(live))
        val info = e.create(SnapshotType.AUTO, settings)
        c.rollback()
        c.close()
        val restored = File(root, "r.db")
        assertTrue(e.extractDatabase(e.file(info), restored))
        assertEquals(200, dump(restored).getValue("transactions").size)
    }
}
