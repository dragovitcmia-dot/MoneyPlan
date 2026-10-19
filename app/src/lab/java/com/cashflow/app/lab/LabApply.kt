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
        seedBackups(app, scenario, seed, today)
        LabState.shiftDays = 0
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

    /** The scenario's backups: each is a real snapshot of the seeded data, taken with the Lab clock set to its day (the newest at "now"). */
    private suspend fun seedBackups(app: CashflowApp, scenario: LabScenario, seed: com.cashflow.app.debug.lab.LabSeed, today: java.time.LocalDate) {
        if (seed.backups.isEmpty()) return
        val zone = java.time.ZoneId.systemDefault()
        val real = System.currentTimeMillis()
        val realOffset = AppClock.offsetMillis
        for (b in seed.backups.sortedByDescending { it.daysAgo }) {
            if (b.daysAgo > 0) {
                val at = java.time.LocalDateTime.of(today.minusDays(b.daysAgo.toLong()), java.time.LocalTime.of(b.hour, b.minute))
                AppClock.offsetMillis = AppClock.offsetFor(at, zone, real)
            } else {
                AppClock.offsetMillis = realOffset // "now": after everything was seeded, so no automatic snapshot follows
            }
            app.backupManager.snapshot(b.type)
        }
        AppClock.offsetMillis = realOffset
        app.backupManager.refresh()
    }

    private suspend fun wipe(app: CashflowApp, today: java.time.LocalDate) {
        java.io.File(app.filesDir, "backups").listFiles()?.forEach { it.delete() }
        app.backupManager.refresh()
        app.repository.clearAll(today)
        app.notificationCenter.clear()
        app.notificationPrefs.savePostedKeys(emptySet())
    }
}
