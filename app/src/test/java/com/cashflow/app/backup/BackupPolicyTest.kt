package com.cashflow.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class BackupPolicyTest {
    private val tunis = ZoneId.of("Africa/Tunis") // UTC+1 all year
    private val now: Instant = LocalDateTime.of(2026, 10, 13, 15, 0).atZone(tunis).toInstant()

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int, zone: ZoneId = tunis) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun snap(millis: Long, type: SnapshotType = SnapshotType.AUTO, size: Long = 2048) =
        SnapshotInfo(SnapshotNames.nameOf(millis, type), millis, type, size)

    // ---- names and labels ------------------------------------------------------------------------------

    @Test fun a_snapshot_name_carries_its_time_and_type_and_parses_back() {
        for (type in SnapshotType.entries) {
            val name = SnapshotNames.nameOf(1_760_000_000_123, type)
            val info = SnapshotNames.parse(name, 4096)!!
            assertEquals(1_760_000_000_123, info.createdAtMillis)
            assertEquals(type, info.type)
            assertEquals(4096, info.sizeBytes)
        }
    }

    @Test fun files_that_are_not_snapshots_are_ignored() {
        assertNull(SnapshotNames.parse("db.part", 1))
        assertNull(SnapshotNames.parse("snap-abc-auto.cfb", 1))
        assertNull(SnapshotNames.parse("snap-123-unknown.cfb", 1))
        assertNull(SnapshotNames.parse("snap-123-auto.cfb.part", 1))
    }

    @Test fun the_list_label_is_the_app_date_style_and_the_local_time() {
        val info = snap(at(2026, 10, 13, 7, 52))
        assertEquals("13 OCT 2026, 07:52", SnapshotLabel.time(info, tunis))
        // the same instant in another zone reads differently: labels follow the phone's zone
        assertEquals("13 OCT 2026, 05:52", SnapshotLabel.time(info, ZoneOffset.ofHours(-1)))
    }

    @Test fun tags_are_short_plain_words() {
        assertEquals(listOf("Auto", "Manual", "Before update", "Before restore"), SnapshotType.entries.map { SnapshotLabel.tag(snap(1, it)) })
    }

    @Test fun sizes_round_to_kb_with_a_minimum_of_one() {
        assertEquals(1L, SnapshotLabel.sizeKb(0))
        assertEquals(1L, SnapshotLabel.sizeKb(300))
        assertEquals(1L, SnapshotLabel.sizeKb(1024))
        assertEquals(2L, SnapshotLabel.sizeKb(1536))
        assertEquals("12 KB", SnapshotLabel.size(snap(1, size = 12_000)))
    }

    // ---- debounce and start ---------------------------------------------------------------------------------

    @Test fun nothing_to_save_without_a_change_or_when_the_last_snapshot_is_newer() {
        assertFalse(SnapshotScheduler.afterChange(null, null, 100).needed)
        assertFalse(SnapshotScheduler.afterChange(1_000, 2_000, 500_000).needed)
        assertFalse(SnapshotScheduler.afterChange(1_000, 1_000, 500_000).needed)
    }

    @Test fun a_snapshot_waits_sixty_seconds_after_the_last_change() {
        val d = SnapshotScheduler.afterChange(lastChange = 10_000, lastSnapshot = null, now = 40_000)
        assertFalse(d.takeNow)
        assertEquals(30_000L, d.waitMillis)
        assertTrue(SnapshotScheduler.afterChange(10_000, null, 70_000).takeNow)
        assertTrue(SnapshotScheduler.afterChange(10_000, 5_000, 200_000).takeNow)
        assertFalse(SnapshotScheduler.afterChange(10_000, null, 69_999).takeNow)
    }

    @Test fun many_quick_edits_become_one_snapshot() {
        // edits at 0, 20 s, 40 s: each pushes the due time out; only 60 s after the last one is a snapshot due
        var last = 0L
        for (t in listOf(0L, 20_000L, 40_000L)) {
            last = t
            assertFalse(SnapshotScheduler.afterChange(last, null, t).takeNow)
        }
        assertFalse(SnapshotScheduler.afterChange(last, null, 99_000).takeNow)
        assertTrue(SnapshotScheduler.afterChange(last, null, 100_000).takeNow)
        // once taken, the same change does not take another
        assertFalse(SnapshotScheduler.afterChange(last, 100_000, 400_000).needed)
    }

    @Test fun on_start_a_snapshot_is_taken_only_if_the_last_one_is_older_than_the_last_change() {
        assertTrue(SnapshotScheduler.onStart(5_000, null))
        assertTrue(SnapshotScheduler.onStart(5_000, 4_000))
        assertFalse(SnapshotScheduler.onStart(5_000, 5_000))
        assertFalse(SnapshotScheduler.onStart(5_000, 9_000))
        assertFalse(SnapshotScheduler.onStart(null, null))
    }

    @Test fun a_different_schema_asks_for_a_before_update_copy_before_the_database_opens() {
        assertTrue(SnapshotScheduler.beforeUpdateNeeded(lastSeen = 2, current = 3, databaseExists = true))
        assertFalse(SnapshotScheduler.beforeUpdateNeeded(2, 2, true))
        assertFalse(SnapshotScheduler.beforeUpdateNeeded(null, 2, true)) // first run: nothing to protect
        assertFalse(SnapshotScheduler.beforeUpdateNeeded(2, 3, false)) // no database yet
        assertTrue(SnapshotScheduler.beforeUpdateNeeded(5, 3, true)) // a downgrade is also a different schema
    }

    // ---- consolidation by local day ------------------------------------------------------------------------

    @Test fun todays_snapshots_all_stay() {
        val files = (0..5).map { snap(at(2026, 10, 13, 8 + it, 0)) }
        assertTrue(Retention.plan(files, now, tunis).isEmpty())
    }

    @Test fun a_previous_day_keeps_only_its_last_snapshot() {
        val files = listOf(snap(at(2026, 10, 12, 8, 0)), snap(at(2026, 10, 12, 12, 0)), snap(at(2026, 10, 12, 21, 30)), snap(at(2026, 10, 13, 9, 0)))
        val doomed = Retention.plan(files, now, tunis)
        assertEquals(setOf(files[0], files[1]), doomed.toSet())
    }

    @Test fun midnight_is_decided_by_the_local_day_not_utc() {
        // 23:30 and 00:30 local are the same UTC hour range but different local days
        val lateYesterday = snap(at(2026, 10, 12, 23, 30))
        val earlyToday = snap(at(2026, 10, 13, 0, 30))
        val olderYesterday = snap(at(2026, 10, 12, 10, 0))
        val doomed = Retention.plan(listOf(olderYesterday, lateYesterday, earlyToday), now, tunis)
        assertEquals(listOf(olderYesterday), doomed)
    }

    @Test fun the_same_instants_group_differently_in_another_time_zone() {
        val a = snap(at(2026, 10, 12, 23, 30))
        val b = snap(at(2026, 10, 13, 0, 30))
        // in UTC (one hour behind Tunis) both fall on 12 OCT, so one of them is consolidated away when "today" is 13 OCT
        val nowUtc = LocalDateTime.of(2026, 10, 13, 12, 0).toInstant(ZoneOffset.UTC)
        assertEquals(listOf(a), Retention.plan(listOf(a, b), nowUtc, ZoneOffset.UTC))
    }

    @Test fun a_special_snapshot_that_is_alone_on_its_day_is_never_removed() {
        for (type in listOf(SnapshotType.MANUAL, SnapshotType.BEFORE_UPDATE, SnapshotType.BEFORE_RESTORE)) {
            val files = listOf(snap(at(2026, 10, 10, 9, 0), type))
            assertTrue(Retention.plan(files, now, tunis).isEmpty())
        }
    }

    @Test fun among_several_on_one_earlier_day_the_last_wins_whatever_the_type() {
        val files = listOf(snap(at(2026, 10, 11, 9, 0), SnapshotType.MANUAL), snap(at(2026, 10, 11, 18, 0), SnapshotType.AUTO))
        assertEquals(listOf(files[0]), Retention.plan(files, now, tunis))
    }

    @Test fun each_earlier_day_keeps_exactly_one() {
        val files = (1..5).flatMap { day -> (0..3).map { snap(at(2026, 10, 13 - day, 9 + it, 0)) } }
        val doomed = Retention.plan(files, now, tunis)
        assertEquals(files.size - 5, doomed.size)
        val kept = files - doomed.toSet()
        assertEquals(5, kept.map { it.localDay(tunis) }.toSet().size)
    }

    // ---- 30 days ---------------------------------------------------------------------------------------------

    @Test fun the_thirtieth_day_back_is_kept_and_the_thirty_first_is_deleted() {
        val keep = snap(at(2026, 9, 13, 12, 0)) // 30 days before 13 OCT
        val drop = snap(at(2026, 9, 12, 12, 0)) // 31 days before
        assertEquals(listOf(drop), Retention.plan(listOf(keep, drop), now, tunis))
    }

    @Test fun old_special_snapshots_go_too() {
        val files = listOf(snap(at(2026, 8, 1, 9, 0), SnapshotType.BEFORE_UPDATE), snap(at(2026, 10, 12, 9, 0)))
        assertEquals(listOf(files[0]), Retention.plan(files, now, tunis))
    }

    // ---- caps -------------------------------------------------------------------------------------------------

    @Test fun at_most_200_files_are_kept_oldest_dropped_first() {
        val files = (0 until 230).map { snap(at(2026, 10, 13, 0, 0) + it * 1_000L) }
        val doomed = Retention.plan(files, now, tunis)
        assertEquals(30, doomed.size)
        assertEquals(files.take(30).toSet(), doomed.toSet())
    }

    @Test fun at_most_50_mb_are_kept_oldest_dropped_first() {
        val mb = 1024L * 1024
        val files = (0 until 8).map { snap(at(2026, 10, 13, 1, 0) + it * 1_000L, size = 10 * mb) }
        val doomed = Retention.plan(files, now, tunis)
        assertEquals(3, doomed.size) // 80 MB -> 50 MB
        assertEquals(files.take(3).toSet(), doomed.toSet())
    }

    @Test fun exactly_at_the_caps_nothing_is_dropped() {
        val files = (0 until 200).map { snap(at(2026, 10, 13, 0, 0) + it * 1_000L, size = 1024) }
        assertTrue(Retention.plan(files, now, tunis).isEmpty())
        val mb = 1024L * 1024
        assertTrue(Retention.plan((0 until 5).map { snap(at(2026, 10, 13, 1, 0) + it * 1_000L, size = 10 * mb) }, now, tunis).isEmpty())
    }

    @Test fun the_caps_apply_after_consolidation() {
        val files = (1..40).flatMap { day -> (0..9).map { snap(at(2026, 10, 12, 0, 0) - day * 86_400_000L + it * 1_000L) } }
        val doomed = Retention.plan(files, now, tunis)
        val kept = files - doomed.toSet()
        assertTrue(kept.size <= 30)
        assertEquals(kept.size, kept.map { it.localDay(tunis) }.toSet().size)
    }
}
