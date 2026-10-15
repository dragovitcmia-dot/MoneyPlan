package com.cashflow.app.engine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The one source of "now" for the whole app. In the real app the offset is always zero (nothing ever sets it), so it is the
 * system clock. The Lab build's time machine sets an offset so a scenario can pretend it is another day or hour; time keeps
 * running from there.
 */
object AppClock {
    @Volatile
    var offsetMillis: Long = 0L

    fun nowMillis(): Long = System.currentTimeMillis() + offsetMillis

    fun now(): Instant = Instant.ofEpochMilli(nowMillis())

    fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.ofInstant(now(), zone)

    fun dateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime = LocalDateTime.ofInstant(now(), zone)

    /** Offset that makes [target] (local time) "now" at [realNowMillis]. */
    fun offsetFor(target: LocalDateTime, zone: ZoneId, realNowMillis: Long): Long =
        target.atZone(zone).toInstant().toEpochMilli() - realNowMillis
}
