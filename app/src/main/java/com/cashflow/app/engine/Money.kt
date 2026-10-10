package com.cashflow.app.engine

object Money {
    const val MILLIMES_PER_TND = 1000L
    const val CURRENCY = "TND"

    /** 75000 -> "75 TND", 75500 -> "75.5 TND", -12345 -> "-12.345 TND". */
    fun format(millimes: Long, withCurrency: Boolean = true): String {
        val sign = if (millimes < 0) "-" else ""
        val abs = if (millimes < 0) -millimes else millimes
        val whole = abs / MILLIMES_PER_TND
        val frac = (abs % MILLIMES_PER_TND).toInt()
        val fracText = if (frac == 0) "" else "." + frac.toString().padStart(3, '0').trimEnd('0')
        return sign + whole + fracText + if (withCurrency) " $CURRENCY" else ""
    }

    private val AMOUNT = Regex("""^(-?)(\d+(?:\.\d{0,3})?|\.\d{1,3})$""")

    /** Parses user input ("12", "12.5", "12,5", "-3.250") to millimes; null if invalid. */
    fun parse(text: String): Long? {
        val match = AMOUNT.matchEntire(text.trim().replace(',', '.')) ?: return null
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
