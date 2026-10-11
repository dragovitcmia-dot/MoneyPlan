package com.cashflow.app.engine

object Money {
    const val MILLIMES_PER_TND = 1000L
    const val CURRENCY = "TND"

    private const val GROUP_SEPARATOR = '\u00A0'
    private const val MINUS = '\u2212'

    /**
     * 75000 -> "75 TND", 75500 -> "75.5 TND", -12345 -> "-12.345 TND".
     * With [grouped], thousands are separated by a no-break space: 1250000 -> "1 250 TND".
     */
    fun format(millimes: Long, withCurrency: Boolean = true, grouped: Boolean = false): String {
        val sign = if (millimes < 0) "-" else ""
        val abs = if (millimes < 0) -millimes else millimes
        val whole = abs / MILLIMES_PER_TND
        val frac = (abs % MILLIMES_PER_TND).toInt()
        val fracText = if (frac == 0) "" else "." + frac.toString().padStart(3, '0').trimEnd('0')
        val wholeText = if (grouped) group(whole.toString()) else whole.toString()
        return sign + wholeText + fracText + if (withCurrency) " $CURRENCY" else ""
    }

    /** What the UI shows: grouped thousands and a proper minus sign ("−1 250 TND"). */
    fun formatDisplay(millimes: Long, withCurrency: Boolean = true): String =
        format(millimes, withCurrency, grouped = true).replace('-', MINUS)

    /** "+30 TND" for income, "−75 TND" for expenses. */
    fun formatSigned(kind: TxKind, millimes: Long): String =
        (if (kind == TxKind.INCOME) "+" else MINUS.toString()) + formatDisplay(millimes)

    /** Groups the digits of an integer string in threes with no-break spaces. */
    fun group(digits: String): String =
        digits.reversed().chunked(3).joinToString(GROUP_SEPARATOR.toString()).reversed()

    private val AMOUNT = Regex("""^(-?)(\d+(?:\.\d{0,3})?|\.\d{1,3})$""")

    /** Parses user input ("12", "12.5", "12,5", "-3.250") to millimes; null if invalid. */
    fun parse(text: String): Long? {
        val cleaned = text.replace(Regex("[\\s\u00A0\u202F]"), "").replace(MINUS, '-').replace(',', '.')
        val match = AMOUNT.matchEntire(cleaned) ?: return null
        val negative = match.groupValues[1].isNotEmpty()
        val parts = match.groupValues[2].split('.')
        val whole = if (parts[0].isEmpty()) 0L else parts[0].toLongOrNull() ?: return null
        val fracDigits = parts.getOrElse(1) { "" }.padEnd(3, '0')
        val frac = fracDigits.toLong()
        if (whole > (Long.MAX_VALUE - frac) / MILLIMES_PER_TND) return null
        val total = whole * MILLIMES_PER_TND + frac
        return if (negative) -total else total
    }
}
