package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.DayOfWeek

/** The encoding of every schedule into the columns the database already has: no schema change. */
class PatternCodecTest {
    private val all = listOf(
        RecurrencePattern.Daily,
        RecurrencePattern.Weekly(DayOfWeek.WEDNESDAY),
        RecurrencePattern.WeeklyDays(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)),
        RecurrencePattern.WeeklyDays(DayOfWeek.entries.toSet()),
        RecurrencePattern.EveryNDays(14, d(2026, 10, 15)),
        RecurrencePattern.EveryNDays(3, d(2026, 1, 1)),
        RecurrencePattern.MonthFirst,
        RecurrencePattern.MonthLast,
        RecurrencePattern.DayOfMonth(1),
        RecurrencePattern.DayOfMonth(31),
        RecurrencePattern.Yearly(2, 29),
        RecurrencePattern.Yearly(12, 31),
    )

    @Test fun every_pattern_survives_encode_then_decode() {
        for (p in all) {
            val e = PatternCodec.encode(p)
            assertEquals("$p", p, PatternCodec.decode(e.type, e.value, e.anchor))
        }
    }

    @Test fun the_stored_values_are_what_the_columns_expect() {
        assertEquals(EncodedPattern("DAILY", null, null), PatternCodec.encode(RecurrencePattern.Daily))
        assertEquals(EncodedPattern("WEEKLY", 3, null), PatternCodec.encode(RecurrencePattern.Weekly(DayOfWeek.WEDNESDAY)))
        assertEquals(EncodedPattern("EVERY_N_DAYS", 14, d(2026, 10, 15)), PatternCodec.encode(RecurrencePattern.EveryNDays(14, d(2026, 10, 15))))
        assertEquals(EncodedPattern("DAY_OF_MONTH", 31, null), PatternCodec.encode(RecurrencePattern.DayOfMonth(31)))
        assertEquals(EncodedPattern("YEARLY", 229, null), PatternCodec.encode(RecurrencePattern.Yearly(2, 29)))
        assertEquals(EncodedPattern("YEARLY", 1231, null), PatternCodec.encode(RecurrencePattern.Yearly(12, 31)))
        assertNull(PatternCodec.encode(RecurrencePattern.MonthFirst).value)
        assertNull(PatternCodec.encode(RecurrencePattern.MonthLast).value)
    }

    @Test fun weekday_sets_are_a_bit_mask_monday_first() {
        assertEquals(0b0001001, PatternCodec.maskOf(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)))
        assertEquals(0b1111111, PatternCodec.maskOf(DayOfWeek.entries.toSet()))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), PatternCodec.daysOf(0b0001001))
        assertEquals(emptySet<DayOfWeek>(), PatternCodec.daysOf(0))
        assertEquals(setOf(DayOfWeek.SUNDAY), PatternCodec.daysOf(0b1000000))
        val e = PatternCodec.encode(RecurrencePattern.WeeklyDays(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)))
        assertEquals("WEEKDAYS", e.type)
        assertEquals(9, e.value)
    }

    @Test fun a_stored_one_day_mask_reads_back_as_a_plain_weekly() {
        assertEquals(RecurrencePattern.Weekly(DayOfWeek.FRIDAY), PatternCodec.decode("WEEKDAYS", 0b0010000, null))
    }

    @Test fun the_type_names_match_the_data_layers_enum_and_unknown_names_fail_loudly() {
        val names = all.map { PatternCodec.encode(it).type }.toSet()
        assertEquals(setOf("DAILY", "WEEKLY", "WEEKDAYS", "EVERY_N_DAYS", "MONTH_FIRST", "MONTH_LAST", "DAY_OF_MONTH", "YEARLY"), names)
        assertThrows(IllegalArgumentException::class.java) { PatternCodec.decode("NOPE", null, null) }
    }
}
