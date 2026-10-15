package com.cashflow.app.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class AppClockTest {
    private val utc = ZoneId.of("UTC")
    private val tunis = ZoneId.of("Africa/Tunis")

    @After fun back() {
        AppClock.offsetMillis = 0L
    }

    @Test fun without_an_offset_it_is_the_system_clock() {
        val before = System.currentTimeMillis()
        val now = AppClock.nowMillis()
        val after = System.currentTimeMillis()
        assertTrue(now in before..after)
        assertEquals(0L, AppClock.offsetMillis)
    }

    @Test fun the_offset_for_a_target_makes_it_now_whatever_the_zone() {
        val real = LocalDateTime.of(2026, 10, 15, 22, 30).atZone(utc).toInstant().toEpochMilli()
        val target = LocalDateTime.of(2026, 10, 10, 9, 0)
        for (zone in listOf(utc, tunis, ZoneId.of("Pacific/Auckland"))) {
            val offset = AppClock.offsetFor(target, zone, real)
            val shown = java.time.Instant.ofEpochMilli(real + offset).atZone(zone).toLocalDateTime()
            assertEquals(zone.id, target, shown)
        }
    }

    @Test fun time_keeps_running_after_the_clock_is_set() {
        AppClock.offsetMillis = AppClock.offsetFor(LocalDateTime.of(2027, 2, 1, 12, 0), utc, System.currentTimeMillis())
        val a = AppClock.nowMillis()
        Thread.sleep(20)
        assertTrue(AppClock.nowMillis() - a >= 15)
    }

    @Test fun today_and_the_date_time_follow_the_offset() {
        AppClock.offsetMillis = AppClock.offsetFor(LocalDateTime.of(2027, 2, 1, 12, 0), tunis, System.currentTimeMillis())
        assertEquals(LocalDate.of(2027, 2, 1), AppClock.today(tunis))
        val dt = AppClock.dateTime(tunis)
        assertEquals(2027, dt.year)
        assertEquals(2, dt.monthValue)
        assertEquals(1, dt.dayOfMonth)
        assertTrue(dt.hour == 12) // moments after the target: still noon
    }
}
