package com.cashflow.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupFormatTest {
    private val sha = Hashing.sha256("x".toByteArray())

    private fun manifest(db: String = sha, settings: String = sha, dbVersion: Int = 2, format: Int = 1) = Manifest(
        format, "0.9.0", dbVersion, 1_760_000_000_000, SnapshotType.MANUAL,
        mapOf("transactions" to 41L, "recurring_rules" to 3L), mapOf(DB_ENTRY to db, SETTINGS_ENTRY to settings),
    )

    private val shas = mapOf(DB_ENTRY to sha, SETTINGS_ENTRY to sha)

    // ---- json -------------------------------------------------------------------------------------------------

    @Test fun json_round_trips_nested_values_and_escapes() {
        val value = linkedMapOf("a" to 1L, "b" to "quote \" slash \\ line\nend", "c" to listOf(true, false, null), "d" to linkedMapOf("e" to "é — ü"))
        assertEquals(value, MiniJson.parse(MiniJson.write(value)))
    }

    @Test fun invalid_json_gives_null() {
        for (bad in listOf("", "{", "{\"a\":}", "[1,", "nope", "{\"a\":1} x")) assertNull(bad, MiniJson.parse(bad))
    }

    // ---- manifest ----------------------------------------------------------------------------------------------

    @Test fun a_manifest_round_trips() {
        val m = manifest()
        assertEquals(m, ManifestCodec.decode(ManifestCodec.encode(m)))
        assertEquals(41L, m.items)
    }

    @Test fun a_broken_manifest_does_not_decode() {
        assertNull(ManifestCodec.decode("not json"))
        assertNull(ManifestCodec.decode("{\"format\":1}"))
        assertNull(ManifestCodec.decode(ManifestCodec.encode(manifest()).replace("\"manual\"", "\"weird\"")))
    }

    // ---- variant (real app / Lab) ----------------------------------------------------------------------------------

    private fun lab() = manifest().copy(variant = BackupVariant.LAB)

    @Test fun the_variant_round_trips_and_a_manifest_without_one_is_a_real_backup() {
        assertEquals(BackupVariant.LAB, ManifestCodec.decode(ManifestCodec.encode(lab()))!!.variant)
        assertEquals(BackupVariant.REAL, ManifestCodec.decode(ManifestCodec.encode(manifest()))!!.variant)
        val old = ManifestCodec.encode(manifest()).replace(",\"variant\":\"real\"", "").replace("\"variant\":\"real\",", "")
        assertTrue(!old.contains("variant"))
        assertEquals(BackupVariant.REAL, ManifestCodec.decode(old)!!.variant)
    }

    @Test fun an_unknown_variant_is_not_guessed() {
        assertNull(ManifestCodec.decode(ManifestCodec.encode(lab()).replace("\"lab\"", "\"beta\"")))
        assertNull(ManifestCodec.decode(ManifestCodec.encode(lab()).replace("\"variant\":\"lab\"", "\"variant\":5")))
    }

    @Test fun the_real_app_refuses_a_lab_backup() {
        val check = BackupValidator.check(lab(), shas, 2, 2, BackupVariant.REAL)
        assertEquals(BackupCheck.WrongVariant(BackupVariant.LAB), check)
        // the default is the real app
        assertEquals(BackupCheck.WrongVariant(BackupVariant.LAB), BackupValidator.check(lab(), shas, 2, 2))
    }

    @Test fun the_real_app_accepts_real_backups_and_the_lab_accepts_both() {
        assertTrue(BackupValidator.check(manifest(), shas, 2, 2, BackupVariant.REAL) is BackupCheck.Ok)
        assertTrue(BackupValidator.check(manifest(), shas, 2, 2, BackupVariant.LAB) is BackupCheck.Ok)
        assertTrue(BackupValidator.check(lab(), shas, 2, 2, BackupVariant.LAB) is BackupCheck.Ok)
        assertTrue(BackupVariants.canImport(BackupVariant.LAB, BackupVariant.REAL))
        assertTrue(BackupVariants.canImport(BackupVariant.LAB, BackupVariant.LAB))
        assertTrue(BackupVariants.canImport(BackupVariant.REAL, BackupVariant.REAL))
        assertTrue(!BackupVariants.canImport(BackupVariant.REAL, BackupVariant.LAB))
    }

    @Test fun a_damaged_file_is_reported_as_damaged_before_its_variant_is_looked_at() {
        val other = Hashing.sha256("y".toByteArray())
        assertTrue(BackupValidator.check(lab(), mapOf(DB_ENTRY to other, SETTINGS_ENTRY to sha), 2, 2, BackupVariant.REAL) is BackupCheck.Corrupt)
    }

    @Test fun export_names_tell_the_variant() {
        val day = java.time.LocalDate.of(2026, 10, 13)
        assertEquals("cashflow-2026-10-13.cfbackup", BackupEngine.exportName(day))
        assertEquals("cashflow-lab-2026-10-13.cfbackup", BackupEngine.exportName(day, BackupVariant.LAB))
    }

    // ---- validation ----------------------------------------------------------------------------------------------

    @Test fun a_matching_backup_of_the_current_schema_is_accepted() {
        assertTrue(BackupValidator.check(manifest(), shas, 2, 2) is BackupCheck.Ok)
    }

    @Test fun an_unreadable_manifest_is_refused() {
        assertTrue(BackupValidator.check(null, shas, 2, 2) is BackupCheck.Unreadable)
    }

    @Test fun a_changed_part_fails_its_checksum() {
        val other = Hashing.sha256("y".toByteArray())
        assertTrue(BackupValidator.check(manifest(), mapOf(DB_ENTRY to other, SETTINGS_ENTRY to sha), 2, 2) is BackupCheck.Corrupt)
        assertTrue(BackupValidator.check(manifest(), mapOf(DB_ENTRY to sha, SETTINGS_ENTRY to other), 2, 2) is BackupCheck.Corrupt)
    }

    @Test fun a_missing_part_is_corrupt() {
        assertTrue(BackupValidator.check(manifest(), mapOf(DB_ENTRY to sha), 2, 2) is BackupCheck.Corrupt)
        assertTrue(BackupValidator.check(manifest().copy(parts = mapOf(DB_ENTRY to sha)), shas, 2, 2) is BackupCheck.Corrupt)
    }

    @Test fun an_unknown_format_is_corrupt() {
        assertTrue(BackupValidator.check(manifest(format = 9), shas, 2, 2) is BackupCheck.Corrupt)
    }

    @Test fun a_newer_schema_than_the_app_is_refused() {
        val r = BackupValidator.check(manifest(dbVersion = 3), shas, currentDbVersion = 2, minDbVersion = 2)
        assertEquals(BackupCheck.TooNew(3), r)
    }

    @Test fun an_older_supported_schema_is_accepted_and_migrated_by_room() {
        assertTrue(BackupValidator.check(manifest(dbVersion = 2), shas, currentDbVersion = 4, minDbVersion = 2) is BackupCheck.Ok)
    }

    @Test fun a_schema_older_than_anything_the_app_can_migrate_is_refused() {
        assertEquals(BackupCheck.TooOld(1), BackupValidator.check(manifest(dbVersion = 1), shas, 4, 2))
    }

    @Test fun checksum_comparison_ignores_case() {
        assertTrue(BackupValidator.check(manifest(db = sha.uppercase()), shas, 2, 2) is BackupCheck.Ok)
    }

    // ---- settings json ---------------------------------------------------------------------------------------------

    @Test fun settings_round_trip_with_every_value_type_and_the_per_series_flag_sets() {
        val snapshot = SettingsSnapshot(
            mapOf(
                "settings_prefs" to mapOf<String, Any>(
                    "auto_accept_income" to true,
                    "ask_amount_rule_ids" to setOf("3", "11", "7"),
                    "ended_rule_ids" to emptySet<String>(),
                ),
                "notification_prefs" to mapOf<String, Any>("enabled" to false, "heads_up" to true, "keep_reminding" to true, "reminder" to 720, "last" to 1_760_000_000_000L, "label" to "noon \"time\""),
                "ui_prefs" to mapOf<String, Any>("swipe_hint_dismissed" to true),
            ),
        )
        val back = SettingsJson.decode(SettingsJson.encode(snapshot))!!
        assertEquals(snapshot, back)
        assertEquals(setOf("3", "7", "11"), back.stores.getValue("settings_prefs")["ask_amount_rule_ids"])
    }

    @Test fun an_empty_settings_snapshot_round_trips() {
        assertEquals(SettingsSnapshot(emptyMap()), SettingsJson.decode(SettingsJson.encode(SettingsSnapshot(emptyMap()))))
    }

    @Test fun broken_settings_json_does_not_decode() {
        assertNull(SettingsJson.decode("nope"))
        assertNull(SettingsJson.decode("{\"s\":{\"k\":{\"t\":\"zz\",\"v\":1}}}"))
    }

    // ---- the archive -------------------------------------------------------------------------------------------------

    private fun tmp(): File = Files.createTempDirectory("cfb").toFile()

    @Test fun an_archive_holds_the_three_parts_and_its_checksums_match() {
        val dir = tmp()
        val db = File(dir, "db").also { it.writeBytes(ByteArray(5000) { b -> (b % 7).toByte() }) }
        val settings = SettingsJson.encode(SettingsSnapshot(mapOf("s" to mapOf<String, Any>("k" to true))))
        val m = manifest(db = Hashing.sha256(db), settings = Hashing.sha256(settings.toByteArray()))
        val file = File(dir, "x.cfb")
        BackupArchive.write(file, m, db, settings)
        val opened = BackupArchive.open(file)
        assertEquals(m, opened.manifest)
        assertTrue(BackupValidator.check(opened.manifest, opened.actualSha, 2, 2) is BackupCheck.Ok)
        assertEquals(settings, BackupArchive.readText(file, SETTINGS_ENTRY))
        val out = File(dir, "out")
        assertTrue(BackupArchive.extract(file, DB_ENTRY, out))
        assertEquals(db.readBytes().toList(), out.readBytes().toList())
        assertTrue(file.length() < db.length() + 2000) // deflate keeps it small
    }

    @Test fun a_file_that_is_not_an_archive_is_unreadable() {
        val dir = tmp()
        val junk = File(dir, "junk").also { it.writeText("this is not a zip") }
        val opened = BackupArchive.open(junk)
        assertNull(opened.manifest)
        assertTrue(BackupValidator.check(opened.manifest, opened.actualSha, 2, 2) is BackupCheck.Unreadable)
        assertNotNull(BackupEngine.exportName(java.time.LocalDate.of(2026, 10, 13)))
        assertEquals("cashflow-2026-10-13.cfbackup", BackupEngine.exportName(java.time.LocalDate.of(2026, 10, 13)))
    }
}
