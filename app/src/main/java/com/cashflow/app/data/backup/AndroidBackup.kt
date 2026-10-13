package com.cashflow.app.data.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.work.WorkManager
import com.cashflow.app.CashflowApp
import com.cashflow.app.RestartActivity
import com.cashflow.app.backup.BackupCheck
import com.cashflow.app.backup.BackupEngine
import com.cashflow.app.backup.DbCopier
import com.cashflow.app.backup.RowCounter
import com.cashflow.app.backup.SettingsSnapshot
import com.cashflow.app.backup.SnapshotInfo
import com.cashflow.app.backup.SnapshotScheduler
import com.cashflow.app.backup.SnapshotType
import com.cashflow.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** The small settings stores that belong to a backup; the posted-notification keys and other bookkeeping are left out. */
private val SETTINGS_STORES = listOf("settings_prefs", "notification_prefs", "ui_prefs")
private val EXCLUDED_KEYS = setOf("posted_keys")

/** Pure Android bits of the backup: how to copy the live database, count rows, read and write the settings stores. */
object AndroidBackupParts {
    /** "3.27.0" and newer can VACUUM INTO. */
    private fun supportsVacuumInto(db: androidx.sqlite.db.SupportSQLiteDatabase): Boolean {
        val v = db.query("select sqlite_version()").use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
        val parts = v.split(".").mapNotNull { it.toIntOrNull() }
        return parts.size >= 2 && (parts[0] > 3 || (parts[0] == 3 && parts[1] >= 27))
    }

    /**
     * A consistent copy of the open Room database: VACUUM INTO where SQLite is new enough, otherwise a WAL checkpoint and a
     * file copy while a write transaction keeps other writers out. Never a bare copy of a live file.
     */
    fun liveCopier(db: AppDatabase) = DbCopier { dest ->
        val sdb = db.openHelper.writableDatabase
        val copied = try {
            if (supportsVacuumInto(sdb)) {
                sdb.execSQL("VACUUM INTO '${dest.path.replace("'", "''")}'")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            dest.delete()
            false
        }
        if (!copied) {
            sdb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            sdb.beginTransaction() // exclusive: nobody writes while the file is copied
            try {
                File(sdb.path!!).copyTo(dest, overwrite = true)
            } finally {
                sdb.endTransaction()
            }
        }
    }

    /** For the copy taken BEFORE Room opens the database (before an update): checkpoint with plain SQLite, then copy the file. */
    fun rawCopier(dbFile: File) = DbCopier { dest ->
        try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
                raw.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                dbFile.copyTo(dest, overwrite = true)
            }
        } catch (e: Exception) {
            dbFile.copyTo(dest, overwrite = true)
        }
    }

    val counter = RowCounter { file ->
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                listOf("transactions", "recurring_rules").associateWith { t ->
                    android.database.DatabaseUtils.longForQuery(raw, "SELECT COUNT(*) FROM $t", null)
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** The schema version stored in a database file (Room keeps it in PRAGMA user_version), or null. */
    fun userVersion(file: File): Int? = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
    } catch (e: Exception) {
        null
    }

    fun readSettings(context: Context): SettingsSnapshot {
        val stores = LinkedHashMap<String, Map<String, Any>>()
        for (name in SETTINGS_STORES) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val map = LinkedHashMap<String, Any>()
            for ((key, value) in prefs.all) {
                if (key in EXCLUDED_KEYS || value == null) continue
                map[key] = when (value) {
                    is Boolean, is Int, is Long, is String -> value
                    is Set<*> -> value.map { it.toString() }.toSet()
                    else -> value.toString()
                }
            }
            stores[name] = map
        }
        return SettingsSnapshot(stores)
    }

    /** Replaces the stored values of each store with the snapshot's (the bookkeeping keys stay as they are). Committed at once: the process restarts next. */
    fun writeSettings(context: Context, snapshot: SettingsSnapshot) {
        for (name in SETTINGS_STORES) {
            val values = snapshot.stores[name] ?: continue
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            for (key in prefs.all.keys) if (key !in EXCLUDED_KEYS) editor.remove(key)
            for ((key, v) in values) {
                when (v) {
                    is Boolean -> editor.putBoolean(key, v)
                    is Int -> editor.putInt(key, v)
                    is Long -> editor.putLong(key, v)
                    is String -> editor.putString(key, v)
                    is Set<*> -> editor.putStringSet(key, v.map { it.toString() }.toSet())
                }
            }
            editor.commit()
        }
    }
}

/** What the app does at start, before anything opens the database. */
object BackupStartup {
    private const val STATE = "backup_state"
    const val KEY_LAST_SEEN_SCHEMA = "last_seen_schema"

    /**
     * An update that carries a different database schema gets a "Before update" snapshot, made by a raw file copy BEFORE Room
     * opens (and perhaps migrates) the database. Always records the schema this version runs with.
     */
    fun beforeRoomOpens(app: android.app.Application, appVersion: String) {
        val prefs = app.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        val dbFile = app.getDatabasePath("cashflow.db")
        val stored = if (prefs.contains(KEY_LAST_SEEN_SCHEMA)) prefs.getInt(KEY_LAST_SEEN_SCHEMA, 0) else null
        val lastSeen = stored ?: if (dbFile.exists()) AndroidBackupParts.userVersion(dbFile) else null
        if (SnapshotScheduler.beforeUpdateNeeded(lastSeen, AppDatabase.SCHEMA_VERSION, dbFile.exists())) {
            runCatching {
                val engine = BackupEngine(
                    File(app.filesDir, "backups"), AndroidBackupParts.rawCopier(dbFile), AndroidBackupParts.counter,
                    System::currentTimeMillis, ZoneId::systemDefault, appVersion, lastSeen ?: AppDatabase.SCHEMA_VERSION,
                )
                engine.create(SnapshotType.BEFORE_UPDATE, AndroidBackupParts.readSettings(app))
            }
        }
        prefs.edit().putInt(KEY_LAST_SEEN_SCHEMA, AppDatabase.SCHEMA_VERSION).apply()
    }
}

/** Snapshots, export, import and restore for the app: the Android glue around the pure [BackupEngine]. */
class BackupManager(private val app: CashflowApp) {
    private val prefs = app.getSharedPreferences("backup_state", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var pending: Job? = null

    @Volatile
    private var restoring = false

    private val appVersion: String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    private val engine by lazy {
        BackupEngine(
            File(app.filesDir, "backups"), AndroidBackupParts.liveCopier(app.database), AndroidBackupParts.counter,
            System::currentTimeMillis, ZoneId::systemDefault, appVersion, AppDatabase.SCHEMA_VERSION,
        )
    }

    private val _snapshots = MutableStateFlow<List<SnapshotInfo>>(emptyList())

    /** Newest first. */
    val snapshots: StateFlow<List<SnapshotInfo>> = _snapshots

    private fun lastChange(): Long? = prefs.getLong("last_change", 0L).takeIf { it > 0 }
    private fun lastSnapshot(): Long? = prefs.getLong("last_snapshot", 0L).takeIf { it > 0 }

    fun refresh() {
        _snapshots.value = engine.list()
    }

    /** At app start: list what exists, tidy up, and take a snapshot at once when the last one is older than the last change. */
    suspend fun onStart() = withContext(Dispatchers.IO) {
        prune()
        if (SnapshotScheduler.onStart(lastChange(), lastSnapshot())) snapshot(SnapshotType.AUTO)
        refresh()
    }

    /** A data change: about 60 seconds after the LAST one a snapshot is taken (quick edits collapse into one). */
    fun noteChange(scope: CoroutineScope) {
        if (restoring) return
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_change", now).apply()
        pending?.cancel()
        pending = scope.launch(Dispatchers.IO) {
            delay(SnapshotScheduler.DEBOUNCE_MILLIS)
            val decision = SnapshotScheduler.afterChange(lastChange(), lastSnapshot(), System.currentTimeMillis())
            if (decision.takeNow) snapshot(SnapshotType.AUTO)
        }
    }

    /** Takes a snapshot of the live database and the settings now. */
    suspend fun snapshot(type: SnapshotType): SnapshotInfo? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (restoring) return@withLock null
            val info = runCatching { engine.create(type, AndroidBackupParts.readSettings(app)) }.getOrNull()
            if (info != null) {
                prefs.edit().putLong("last_snapshot", info.createdAtMillis).apply()
                engine.prune()
                refresh()
            }
            info
        }
    }

    /** Consolidates earlier days, deletes snapshots older than 30 days and enforces the caps. Also run by the periodic worker. */
    suspend fun prune() = withContext(Dispatchers.IO) {
        lock.withLock { engine.prune() }
        refresh()
    }

    fun file(info: SnapshotInfo): File = engine.file(info)

    /** Checks a snapshot file or an imported file against this app. */
    fun check(file: File): BackupCheck = engine.check(file, MIN_DB_VERSION)

    /** A new backup of the current state, copied to the cache as cashflow-<day>.cfbackup, ready for the share sheet. */
    suspend fun exportFile(): File? = withContext(Dispatchers.IO) {
        val info = snapshot(SnapshotType.MANUAL) ?: return@withContext null
        val dir = File(app.cacheDir, "exports").also { it.mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, BackupEngine.exportName(LocalDate.now()))
        engine.file(info).copyTo(out, overwrite = true)
        out
    }

    /**
     * Restores [file] (a snapshot or an imported backup, already checked): first a "Before restore" snapshot of the current
     * state, then background work stops, the database is closed and swapped, the settings are replaced and the process restarts.
     * Returns false when something stopped it before the swap.
     */
    suspend fun restore(file: File, activity: Activity): Boolean = withContext(Dispatchers.IO) {
        if (check(file) !is BackupCheck.Ok) return@withContext false
        val settings = engine.readSettings(file) ?: return@withContext false
        if (snapshot(SnapshotType.BEFORE_RESTORE) == null) return@withContext false
        restoring = true
        pending?.cancel()
        WorkManager.getInstance(app).cancelAllWork()
        app.database.close()
        val target = app.getDatabasePath("cashflow.db")
        File(target.path + "-wal").delete()
        File(target.path + "-shm").delete()
        if (!engine.extractDatabase(file, target)) {
            restoring = false
            return@withContext false
        }
        AndroidBackupParts.writeSettings(app, settings)
        withContext(Dispatchers.Main) { RestartActivity.restart(activity) }
        true
    }

    companion object {
        /** Oldest database schema this app can restore (older ones have no migration path). */
        const val MIN_DB_VERSION = 2
    }
}
