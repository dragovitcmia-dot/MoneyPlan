package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AmountInputTest {
    private fun type(vararg keys: Char) = keys.fold("") { acc, k -> AmountInput.press(acc, k) }

    @Test fun digits_accumulate() = assertEquals("125", type('1', '2', '5'))

    @Test fun leading_zero_is_replaced_and_double_zero_ignored() {
        assertEquals("5", type('0', '5'))
        assertEquals("0", type('0', '0', '0'))
    }

    @Test fun decimal_point_rules() {
        assertEquals("0.", type('.'))
        assertEquals("12.", type('1', '2', '.'))
        assertEquals("12.5", type('1', '2', '.', '.', '5'))
    }

    @Test fun at_most_two_decimals() = assertEquals("1.23", type('1', '.', '2', '3', '4', '5', '6'))

    @Test fun at_most_nine_integer_digits() = assertEquals("123456789", type(*"1234567890".toCharArray()))

    @Test fun ignores_non_keypad_characters() = assertEquals("1", AmountInput.press("1", 'x'))

    @Test fun backspace() {
        assertEquals("12", AmountInput.backspace("125"))
        assertEquals("", AmountInput.backspace("5"))
        assertEquals("", AmountInput.backspace(""))
        assertEquals("1", AmountInput.backspace("1."))
    }

    @Test fun to_millimes() {
        assertEquals(0L, AmountInput.toMillimes(""))
        assertEquals(12_500L, AmountInput.toMillimes("12.5"))
        assertEquals(5_000L, AmountInput.toMillimes("5."))
        assertEquals(0L, AmountInput.toMillimes("0."))
        assertEquals(1_250_000L, AmountInput.toMillimes("1250"))
    }

    @Test fun display_groups_thousands_and_keeps_the_typed_tail() {
        val nb = " "
        assertEquals("0", AmountInput.display(""))
        assertEquals("1${nb}250", AmountInput.display("1250"))
        assertEquals("1${nb}250.5", AmountInput.display("1250.5"))
        assertEquals("12.", AmountInput.display("12."))
        assertEquals("0.", AmountInput.display("0."))
    }
}
