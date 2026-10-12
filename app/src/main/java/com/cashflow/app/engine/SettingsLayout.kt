package com.cashflow.app.engine

/** The two switches that decide what the Settings screen offers. Change them here and nowhere else. */
object SettingsConfig {
    /**
     * Whether "Money I started with" and "Starting date" can be edited. Set to false to lock them once a
     * first-run setup exists; they then show read-only with a note.
     */
    const val STARTING_POINT_EDITABLE = true

    /** Whether the "Developer tools" card (sample data, erase everything) is part of the screen. */
    const val SHOW_DEVELOPER_TOOLS = true
}

enum class SettingsCard { INCOME, REPEATING_PAYMENTS, STARTING_POINT, APP_UPDATES, DEVELOPER_TOOLS }

/** What the Settings screen shows, decided without any UI. */
data class SettingsLayout(
    /** Cards from top to bottom. */
    val cards: List<SettingsCard>,
    /** Automatic income is off: the "Regular income" list stays readable but dimmed and not touchable. */
    val incomeListDimmed: Boolean,
    val incomeListInteractive: Boolean,
    /** Show "Income is not being added automatically." */
    val incomeOffNote: Boolean,
    val startingPointReadOnly: Boolean,
    /** Show "Clear all data to start again". */
    val startingPointNote: Boolean,
)

object SettingsPlan {
    fun build(
        walletMode: WalletMode,
        startingPointEditable: Boolean = SettingsConfig.STARTING_POINT_EDITABLE,
        showDeveloperTools: Boolean = SettingsConfig.SHOW_DEVELOPER_TOOLS,
    ): SettingsLayout {
        val off = walletMode == WalletMode.STATIC
        return SettingsLayout(
            cards = buildList {
                add(SettingsCard.INCOME)
                add(SettingsCard.REPEATING_PAYMENTS)
                add(SettingsCard.STARTING_POINT)
                add(SettingsCard.APP_UPDATES)
                if (showDeveloperTools) add(SettingsCard.DEVELOPER_TOOLS) // always last
            },
            incomeListDimmed = off,
            incomeListInteractive = !off,
            incomeOffNote = off,
            startingPointReadOnly = !startingPointEditable,
            startingPointNote = !startingPointEditable,
        )
    }
}
