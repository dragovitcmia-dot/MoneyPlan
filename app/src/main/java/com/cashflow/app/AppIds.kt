package com.cashflow.app

/**
 * Identifiers that must differ between the real app and the Lab build installed next to it. The suffix is empty in the real
 * app, so its ids are exactly what they always were.
 */
object AppIds {
    const val SUFFIX: String = BuildConfig.ID_SUFFIX
    const val IS_LAB: Boolean = BuildConfig.IS_LAB

    const val CHANNEL_PAYMENTS = "payments" + SUFFIX
    const val CHANNEL_HEADS_UP = "heads_up" + SUFFIX
    const val REMINDER_WORK = "reminders" + SUFFIX
    const val UPDATE_STATUS_ACTION = BuildConfig.APPLICATION_ID + ".UPDATE_STATUS"
}
