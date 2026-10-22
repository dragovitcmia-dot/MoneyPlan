package com.cashflow.app.engine

import java.time.LocalDate

/**
 * A place in the app that something can send the person to by itself (pure data): a screen, a day list, an item sheet with its date
 * picker open, the add wizard on its Name step, the purchase result, the Backups screen. Notifications and the Cashflow Lab use it,
 * so a check never asks anyone to tap their way to the screen it is about.
 */
sealed interface UiTarget {
    data object Home : UiTarget

    /** The Calendar on [date] with that day's list open; with [item] (a name on that day) its item sheet replaces the list, and with [move] its Move date picker is open too. */
    data class CalendarDay(val date: LocalDate, val item: String? = null, val move: Boolean = false) : UiTarget

    /** Upcoming: the occurrence list, or the Repeating (series) list. */
    data class Upcoming(val series: Boolean = false) : UiTarget

    /** Home with the add wizard open, the amount (and name) already filled in: on its Name step, or on the Amount step when [atName] is false. */
    data class Wizard(val kind: TxKind, val amountText: String, val name: String = "", val atName: Boolean = true) : UiTarget

    /** Home with Plan a purchase open on its result for [priceText] (and [parts] chosen, when given). */
    data class Purchase(val priceText: String, val name: String = "Purchase", val parts: Int? = null) : UiTarget

    /** History with [tab] and the search [query] applied; with [openFirst] the first row's (read-only) item sheet is open too. */
    data class History(val tab: UpcomingType = UpcomingType.ALL, val query: String = "", val openFirst: Boolean = false) : UiTarget

    data object Settings : UiTarget

    /** Settings with the full Backups screen open. */
    data object Backups : UiTarget
}
