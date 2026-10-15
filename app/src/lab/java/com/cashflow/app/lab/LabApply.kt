package com.cashflow.app.lab

import com.cashflow.app.CashflowApp
import com.cashflow.app.debug.lab.LabScenario
import com.cashflow.app.engine.AppClock

/** Executes the Lab actions on the (Lab) database. The seeds themselves are pure and tested (LabScenarios). */
object LabApply {
    /** Wipes the Lab database only, applies the scenario's clock and seeds exactly that scenario. */
    suspend fun load(app: CashflowApp, scenario: LabScenario) {
        val target = scenario.clock
        if (target != null) LabState.setClockTo(target) else LabState.realTime()
        val today = AppClock.today()
        val seed = scenario.seed(today)
        wipe(app, today)
        val repo = app.repository
        for (rule in seed.rules) repo.insertRule(rule)
        repo.insertTransactions(seed.transactions)
        repo.updateSettings(seed.settings, today) // also generates the rows of the series
        for (id in seed.askAmountRuleIds) repo.setAskAmount(id, true)
        LabState.rememberScenario(scenario.id)
        app.snapshots.refreshDay()
    }

    /** Empty data, real time, no bell entries, nothing remembered about posted notifications. */
    suspend fun reset(app: CashflowApp) {
        LabState.realTime()
        wipe(app, AppClock.today())
        LabState.rememberScenario(null)
        app.snapshots.refreshDay()
    }

    /** After the clock moved: the series generate rows up to the new horizon and the screens re-read the day. */
    suspend fun clockMoved(app: CashflowApp) {
        app.repository.runMaintenance(AppClock.today())
        app.snapshots.refreshDay()
    }

    private suspend fun wipe(app: CashflowApp, today: java.time.LocalDate) {
        app.repository.clearAll(today)
        app.notificationCenter.clear()
        app.notificationPrefs.savePostedKeys(emptySet())
    }
}
