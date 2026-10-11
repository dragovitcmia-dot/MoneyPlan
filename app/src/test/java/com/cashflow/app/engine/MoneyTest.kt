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

    @Test fun grouped_format_uses_no_break_spaces() {
        val nb = "\u00A0"
        assertEquals("1${nb}250 TND", Money.format(1_250_000, grouped = true))
        assertEquals("75 TND", Money.format(75_000, grouped = true))
        assertEquals("999 TND", Money.format(999_000, grouped = true))
        assertEquals("1${nb}000 TND", Money.format(1_000_000, grouped = true))
        assertEquals("12${nb}345${nb}678.5 TND", Money.format(12_345_678_500, grouped = true))
    }

    @Test fun display_format_groups_and_uses_a_proper_minus() {
        val nb = "\u00A0"
        assertEquals("\u22121${nb}250 TND", Money.formatDisplay(-1_250_000))
        assertEquals("\u221213 TND", Money.formatDisplay(-13_000))
        assertEquals("0 TND", Money.formatDisplay(0))
        assertEquals("599 TND", Money.formatDisplay(599_000))
    }

    @Test fun signed_format_for_cards() {
        assertEquals("+30 TND", Money.formatSigned(TxKind.INCOME, 30_000))
        assertEquals("\u221275 TND", Money.formatSigned(TxKind.EXPENSE, 75_000))
    }

    @Test fun parse_accepts_grouped_and_typographic_input() {
        assertEquals(1_250_000L, Money.parse("1\u00A0250"))
        assertEquals(1_250_000L, Money.parse("1 250"))
        assertEquals(-1_250_500L, Money.parse("\u22121\u00A0250.5"))
    }

    @Test fun grouped_display_round_trips_through_parse() {
        for (v in listOf(0L, 999L, 1_000L, 1_250_000L, -75_500L, 12_345_678_500L)) {
            assertEquals(v, Money.parse(Money.formatDisplay(v, withCurrency = false)))
        }
    }
}
