package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class NotificationCenterModelTest {
    private val zone = ZoneId.of("Africa/Tunis")
    private val now = d(2026, 10, 12).atTime(15, 0).atZone(zone).toInstant()

    private fun entry(id: Long, ago: Duration, readAt: Instant? = null) = BellEntry(
        id, NotificationType.DAY_OF, "t$id", "b$id", null, null, now.minus(ago), readAt,
    )

    @Test fun the_badge_is_empty_for_zero_and_capped_at_nine_plus() {
        assertNull(NotificationCenterModel.badge(0))
        assertEquals("1", NotificationCenterModel.badge(1))
        assertEquals("9", NotificationCenterModel.badge(9))
        assertEquals("9+", NotificationCenterModel.badge(10))
        assertEquals("9+", NotificationCenterModel.badge(250))
    }

    @Test fun history_keeps_at_most_200_entries() {
        val entries = (1L..230L).map { it to now.minusSeconds(it * 60) }
        val prune = NotificationCenterModel.idsToPrune(entries, now)
        assertEquals(30, prune.size)
        assertTrue(prune.all { it > 200 }) // the oldest go
    }

    @Test fun history_drops_entries_older_than_90_days() {
        val entries = listOf(1L to now.minus(Duration.ofDays(89)), 2L to now.minus(Duration.ofDays(91)), 3L to now)
        assertEquals(listOf(2L), NotificationCenterModel.idsToPrune(entries, now))
    }

    @Test fun whichever_limit_is_smaller_wins() {
        val old = (1L..10L).map { it to now.minus(Duration.ofDays(100)).minusSeconds(it) }
        val fresh = (11L..250L).map { it to now.minusSeconds(it) }
        val prune = NotificationCenterModel.idsToPrune(old + fresh, now).toSet()
        assertEquals(10 + 40, prune.size)
        assertTrue((1L..10L).all { it in prune })
    }

    @Test fun nothing_is_pruned_below_the_limits() {
        assertTrue(NotificationCenterModel.idsToPrune(listOf(1L to now), now).isEmpty())
    }

    @Test fun rows_are_grouped_into_today_and_earlier_newest_first() {
        val rows = listOf(entry(1, Duration.ofDays(3)), entry(2, Duration.ofHours(2)), entry(3, Duration.ofMinutes(5)), entry(4, Duration.ofHours(20)))
        val groups = NotificationCenterModel.group(rows, now, zone)
        assertEquals(listOf(BellSection.TODAY, BellSection.EARLIER), groups.map { it.first })
        assertEquals(listOf(3L, 2L), groups[0].second.map { it.id })
        assertEquals(listOf(4L, 1L), groups[1].second.map { it.id })
    }

    @Test fun an_empty_section_is_left_out() {
        val groups = NotificationCenterModel.group(listOf(entry(1, Duration.ofMinutes(1))), now, zone)
        assertEquals(listOf(BellSection.TODAY), groups.map { it.first })
        assertTrue(NotificationCenterModel.group(emptyList(), now, zone).isEmpty())
    }

    @Test fun relative_time_reads_naturally() {
        fun text(ago: Duration) = NotificationCenterModel.relativeTime(now.minus(ago), now, zone)
        assertEquals("Just now", text(Duration.ofSeconds(20)))
        assertEquals("5 min ago", text(Duration.ofMinutes(5)))
        assertEquals("2 h ago", text(Duration.ofMinutes(125)))
        assertEquals("Yesterday", text(Duration.ofHours(20)))
        assertEquals("08 OCT", text(Duration.ofDays(4)))
    }

    @Test fun unread_means_not_yet_read() {
        assertTrue(entry(1, Duration.ZERO).unread)
        assertTrue(!entry(2, Duration.ZERO, readAt = now).unread)
    }
}

class BellTonesTest {
    @Test fun rows_get_their_colour_family() {
        assertEquals(BellTone.SHORTAGE, BellTones.of(NotificationType.SHORTAGE, 10_000))
        assertEquals(BellTone.INCOME, BellTones.of(NotificationType.AUTO_INCOME, 30_000))
        assertEquals(BellTone.INFO, BellTones.of(NotificationType.INFO, null))
        assertEquals(BellTone.EXPENSE, BellTones.of(NotificationType.HEADS_UP_WEEK, -300_000))
        assertEquals(BellTone.EXPENSE, BellTones.of(NotificationType.HEADS_UP_DAY, -300_000))
    }

    @Test fun a_day_summary_is_green_when_only_income_is_waiting_and_red_otherwise() {
        assertEquals(BellTone.INCOME, BellTones.of(NotificationType.DAY_OF, 30_000))
        assertEquals(BellTone.EXPENSE, BellTones.of(NotificationType.DAY_OF, -90_000))
        assertEquals(BellTone.EXPENSE, BellTones.of(NotificationType.DAY_OF, null))
    }
}
