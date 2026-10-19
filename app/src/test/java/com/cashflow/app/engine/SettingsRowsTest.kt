package com.cashflow.app.engine

import com.cashflow.app.backup.BackupSummary
import com.cashflow.app.backup.SnapshotInfo
import com.cashflow.app.backup.SnapshotType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class SettingsRowsTest {
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun snap(millis: Long) = SnapshotInfo("snap-$millis-manual.cfb", millis, SnapshotType.MANUAL, 2048)

    @Test fun the_backups_row_names_the_newest_backup_and_how_many_are_saved() {
        val list = (0 until 7).map { snap(at(2026, 10, 13 + it % 7, 10, 0)) } + snap(at(2026, 10, 19, 16, 24))
        val seven = list.drop(1)
        assertEquals("Last backup 19 OCT 2026, 16:24 · 7 saved", BackupSummary.line(seven, ZoneOffset.UTC))
    }

    @Test fun the_order_of_the_list_does_not_matter() {
        val a = snap(at(2026, 10, 19, 16, 24))
        val b = snap(at(2026, 10, 12, 8, 5))
        assertEquals("Last backup 19 OCT 2026, 16:24 · 2 saved", BackupSummary.line(listOf(b, a), ZoneOffset.UTC))
        assertEquals("Last backup 19 OCT 2026, 16:24 · 2 saved", BackupSummary.line(listOf(a, b), ZoneOffset.UTC))
    }

    @Test fun no_backups_says_so_in_one_line() {
        assertEquals("No backups yet", BackupSummary.line(emptyList(), ZoneOffset.UTC))
    }

    @Test fun backups_stay_one_card_of_the_settings_plan() {
        assertEquals(1, SettingsPlan.build().cards.count { it == SettingsCard.BACKUP })
    }
}
