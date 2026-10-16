package com.cashflow.app.engine

/**
 * What a color MEANS. Green is money in, red is money out, everything else is neutral. The UI maps these tokens to the palette
 * (income text, negative text, normal text) in one place; this pure function decides which token applies.
 */
enum class ColorToken { INCOME, EXPENSE, NEUTRAL }

object SemanticColor {
    /** An item's amount, its icon and its repeat arrow: by what the item is. */
    fun ofKind(kind: TxKind): ColorToken = if (kind == TxKind.INCOME) ColorToken.INCOME else ColorToken.EXPENSE

    /** A signed money change (+ in, - out): green, red, or neutral for zero. */
    fun ofChange(amount: Long): ColorToken = when {
        amount > 0 -> ColorToken.INCOME
        amount < 0 -> ColorToken.EXPENSE
        else -> ColorToken.NEUTRAL
    }

    /** A balance (Available, a day's closing): normal text color when it is zero or more, red when it is negative. Never green. */
    fun ofBalance(balance: Long): ColorToken = if (balance < 0) ColorToken.EXPENSE else ColorToken.NEUTRAL

    /** The Upcoming tab: All is neutral, Expenses red, Income green (selected look; unselected tabs are plain text). */
    fun ofTab(type: UpcomingType): ColorToken = when (type) {
        UpcomingType.ALL -> ColorToken.NEUTRAL
        UpcomingType.EXPENSES -> ColorToken.EXPENSE
        UpcomingType.INCOME -> ColorToken.INCOME
    }

    /** The repeat arrow next to a series item's name. */
    fun ofRepeatIcon(kind: TxKind): ColorToken = ofKind(kind)

    /** A confirmation of an accepted item (check, "Accepted"): by the item's kind. */
    fun ofConfirmation(kind: TxKind): ColorToken = ofKind(kind)

    /** Controls that are not money (selection, switch, slider, focus, link, selected nav bar, progress, primary buttons). */
    val accent: ColorToken = ColorToken.NEUTRAL
}
