package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test fun whole_numbers_have_no_decimals() {
        assertEquals("75 TND", Money.format(75_000))
        assertEquals("0 TND", Money.format(0))
        assertEquals("-10 TND", Money.format(-10_000))
    }

    @Test fun decimals_only_when_nonzero_and_trimmed() {
        assertEquals("75.5 TND", Money.format(75_500))
        assertEquals("12.345 TND", Money.format(12_345))
        assertEquals("0.005 TND", Money.format(5))
        assertEquals("-0.5 TND", Money.format(-500))
        assertEquals("1.05 TND", Money.format(1_050))
    }

    @Test fun format_without_currency() = assertEquals("75", Money.format(75_000, withCurrency = false))

    @Test fun parse_accepts_dot_comma_and_signs() {
        assertEquals(75_000L, Money.parse("75"))
        assertEquals(12_500L, Money.parse("12.5"))
        assertEquals(12_500L, Money.parse("12,5"))
        assertEquals(-3_250L, Money.parse("-3.250"))
        assertEquals(500L, Money.parse(".5"))
        assertEquals(5_000L, Money.parse("5."))
        assertEquals(1_000L, Money.parse(" 1 "))
    }

    @Test fun parse_rejects_garbage_and_extra_precision() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("-"))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("1.2345"))
        assertNull(Money.parse("1.2.3"))
        assertNull(Money.parse("99999999999999999999"))
    }

    @Test fun parse_format_round_trip() {
        for (v in listOf(0L, 1L, 999L, 1_000L, 12_345L, -75_500L)) {
            assertEquals(v, Money.parse(Money.format(v, withCurrency = false)))
        }
    }
}
