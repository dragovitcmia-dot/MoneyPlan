package com.cashflow.app.engine

/** Pure logic behind the in-app numeric keypad: digits, one decimal point, up to 2 decimals. */
object AmountInput {
    const val MAX_INTEGER_DIGITS = 9
    const val MAX_DECIMALS = 2

    /** Applies a key ('0'..'9' or '.') to the text typed so far; invalid presses are ignored. */
    fun press(current: String, key: Char): String {
        val dot = current.indexOf('.')
        return when {
            key == '.' -> when {
                dot >= 0 -> current
                current.isEmpty() -> "0."
                else -> "$current."
            }
            key !in '0'..'9' -> current
            dot >= 0 -> if (current.length - dot - 1 >= MAX_DECIMALS) current else current + key
            current == "0" -> if (key == '0') current else key.toString()
            current.length >= MAX_INTEGER_DIGITS -> current
            else -> current + key
        }
    }

    fun backspace(current: String): String = current.dropLast(1)

    /** Millimes for the typed text; 0 when empty or incomplete. */
    fun toMillimes(text: String): Long = if (text.isEmpty()) 0 else Money.parse(text) ?: 0

    /** The typed text with grouped thousands, e.g. "1250.5" -> "1 250.5". Empty shows as "0". */
    fun display(text: String): String {
        if (text.isEmpty()) return "0"
        val dot = text.indexOf('.')
        return if (dot < 0) Money.group(text) else Money.group(text.substring(0, dot)) + text.substring(dot)
    }
}
