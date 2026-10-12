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

/** Settings is only about the app itself: repeating payments are made and managed from Home and an item's details. */
enum class SettingsCard { INCOME, STARTING_POINT, APP_UPDATES, DEVELOPER_TOOLS }

/** What the Settings screen shows, decided without any UI. */
data class SettingsLayout(
    /** Cards from top to bottom. */
    val cards: List<SettingsCard>,
    val startingPointReadOnly: Boolean,
    /** Show "Clear all data to start again". */
    val startingPointNote: Boolean,
)

object SettingsPlan {
    fun build(
        startingPointEditable: Boolean = SettingsConfig.STARTING_POINT_EDITABLE,
        showDeveloperTools: Boolean = SettingsConfig.SHOW_DEVELOPER_TOOLS,
    ): SettingsLayout = SettingsLayout(
        cards = buildList {
            add(SettingsCard.INCOME)
            add(SettingsCard.STARTING_POINT)
            add(SettingsCard.APP_UPDATES)
            if (showDeveloperTools) add(SettingsCard.DEVELOPER_TOOLS) // always last
        },
        startingPointReadOnly = !startingPointEditable,
        startingPointNote = !startingPointEditable,
    )
}
