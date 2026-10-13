package com.cashflow.app.backup

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

const val MANIFEST_ENTRY = "manifest.json"
const val DB_ENTRY = "cashflow.db"
const val SETTINGS_ENTRY = "settings.json"

/** What is inside a snapshot or an exported backup file. */
data class Manifest(
    val format: Int,
    val appVersion: String,
    val dbVersion: Int,
    val createdAtMillis: Long,
    val type: SnapshotType,
    /** Row counts by table, e.g. transactions and recurring_rules. */
    val counts: Map<String, Long>,
    /** sha256 (hex) of each part: cashflow.db and settings.json. */
    val parts: Map<String, String>,
) {
    val items: Long get() = counts["transactions"] ?: 0L
}

object ManifestCodec {
    const val FORMAT = 1

    fun encode(m: Manifest): String = MiniJson.write(
        linkedMapOf(
            "format" to m.format,
            "appVersion" to m.appVersion,
            "dbVersion" to m.dbVersion,
            "createdAt" to m.createdAtMillis,
            "type" to m.type.code,
            "counts" to m.counts,
            "parts" to m.parts,
        ),
    )

    fun decode(text: String): Manifest? {
        val root = MiniJson.parse(text) as? Map<*, *> ?: return null
        fun long(key: String) = (root[key] as? Long)
        val format = long("format")?.toInt() ?: return null
        val dbVersion = long("dbVersion")?.toInt() ?: return null
        val created = long("createdAt") ?: return null
        val type = (root["type"] as? String)?.let(SnapshotType::ofCode) ?: return null
        val counts = (root["counts"] as? Map<*, *>)?.mapNotNull { (k, v) -> (v as? Long)?.let { k.toString() to it } }?.toMap() ?: emptyMap()
        val parts = (root["parts"] as? Map<*, *>)?.mapNotNull { (k, v) -> (v as? String)?.let { k.toString() to it } }?.toMap() ?: return null
        return Manifest(format, root["appVersion"] as? String ?: "", dbVersion, created, type, counts, parts)
    }
}

/** What opening a backup file found. */
sealed interface BackupCheck {
    data class Ok(val manifest: Manifest) : BackupCheck
    data object Unreadable : BackupCheck
    data class Corrupt(val reason: String) : BackupCheck

    /** Made by a newer app than this one: refused. */
    data class TooNew(val dbVersion: Int) : BackupCheck

    /** Older than the oldest schema this app can migrate from. */
    data class TooOld(val dbVersion: Int) : BackupCheck
}

object BackupValidator {
    /**
     * [actualSha] are the hashes computed from the file's real contents. The manifest must be readable, its format known,
     * both parts present with matching checksums, and the database schema neither newer than [currentDbVersion] (refused)
     * nor older than [minDbVersion] (nothing to migrate it with). An older but supported schema is fine: Room migrates it.
     */
    fun check(manifest: Manifest?, actualSha: Map<String, String>, currentDbVersion: Int, minDbVersion: Int): BackupCheck {
        if (manifest == null) return BackupCheck.Unreadable
        if (manifest.format != ManifestCodec.FORMAT) return BackupCheck.Corrupt("unknown format ${manifest.format}")
        for (part in listOf(DB_ENTRY, SETTINGS_ENTRY)) {
            val expected = manifest.parts[part] ?: return BackupCheck.Corrupt("$part is not listed")
            val actual = actualSha[part] ?: return BackupCheck.Corrupt("$part is missing")
            if (!expected.equals(actual, ignoreCase = true)) return BackupCheck.Corrupt("$part does not match its checksum")
        }
        if (manifest.dbVersion > currentDbVersion) return BackupCheck.TooNew(manifest.dbVersion)
        if (manifest.dbVersion < minDbVersion) return BackupCheck.TooOld(manifest.dbVersion)
        return BackupCheck.Ok(manifest)
    }
}

object Hashing {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** The file format of snapshots and exported backups: one zip (deflate) with manifest.json, cashflow.db and settings.json. */
object BackupArchive {
    fun write(target: File, manifest: Manifest, dbFile: File, settingsJson: String) {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(ManifestCodec.encode(manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            dbFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(SETTINGS_ENTRY))
            zip.write(settingsJson.toByteArray())
            zip.closeEntry()
        }
    }

    /** The manifest of [file] (null when unreadable) and the checksums of what is really inside. */
    class Opened(val manifest: Manifest?, val actualSha: Map<String, String>)

    fun open(file: File): Opened = try {
        var manifest: Manifest? = null
        val shas = HashMap<String, String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    MANIFEST_ENTRY -> manifest = ManifestCodec.decode(zip.readBytes().toString(Charsets.UTF_8))
                    DB_ENTRY, SETTINGS_ENTRY -> shas[entry.name] = Hashing.sha256(NoClose(zip))
                }
            }
        }
        Opened(manifest, shas)
    } catch (e: Exception) {
        Opened(null, emptyMap())
    }

    fun extract(file: File, entryName: String, dest: File): Boolean {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return false
                if (entry.name == entryName) {
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { zip.copyTo(it) }
                    return true
                }
            }
        }
    }

    fun readText(file: File, entryName: String): String? {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == entryName) return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    /** Reading an entry's bytes must not close the zip stream. */
    private class NoClose(private val inner: ZipInputStream) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() = Unit
    }
}
