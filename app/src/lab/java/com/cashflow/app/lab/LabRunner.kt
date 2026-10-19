package com.cashflow.app.lab

import com.cashflow.app.CashflowApp
import com.cashflow.app.debug.lab.LabFlow
import com.cashflow.app.debug.lab.LabScenarios
import com.cashflow.app.debug.lab.LabSession
import com.cashflow.app.engine.AppClock
import com.cashflow.app.engine.UiTarget
import com.cashflow.app.ui.UiTargets
import kotlinx.coroutines.launch

/**
 * Runs the checks: loads a scenario's data, sends the app to the screen of each step by itself, and moves on when the person marks it.
 * Nothing here asks anyone to type, create, edit or delete. The pure rules (which step or scenario comes next) are in LabFlow.
 */
object LabRunner {
    /**
     * The runner outlives every screen: a mark that is in the middle of loading the next scenario must not be cancelled when the sheet
     * that holds the button leaves the screen (the button's own scope would be).
     */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    fun beginAsync(app: CashflowApp, ids: List<String>) {
        scope.launch { begin(app, ids) }
    }

    fun markAsync(app: CashflowApp, ok: Boolean) {
        scope.launch { mark(app, ok) }
    }

    /** "Run all checks" ([ids] = every scenario) or one scenario. */
    suspend fun begin(app: CashflowApp, ids: List<String>) {
        val session = LabFlow.start(ids) ?: return
        LabState.setSession(session)
        LabState.setPanelExpanded(true)
        LabState.hideOverlay()
        enter(app, session, load = true)
    }

    /** "Looks right" or "Problem" on the screen that is showing. */
    suspend fun mark(app: CashflowApp, ok: Boolean) {
        val session = LabState.session.value ?: return
        val scenario = LabScenarios.byId(session.scenarioId) ?: return
        val marked = LabFlow.mark(session, ok, scenario.steps.size)
        marked.finishedId?.let { LabState.record(it, marked.verdict == true) }
        val next = marked.session
        LabState.setSession(next)
        if (next == null) {
            UiTargets.request(UiTarget.Home)
            LabState.showResults()
        } else {
            enter(app, next, load = next.scenarioId != session.scenarioId)
        }
    }

    /** Ends the run without marking the screen that is showing. */
    fun stop() {
        LabState.setSession(null)
        LabState.showHome()
        UiTargets.request(UiTarget.Home)
    }

    private suspend fun enter(app: CashflowApp, session: LabSession, load: Boolean) {
        LabState.setBusy(true)
        try {
            enterNow(app, session, load)
        } finally {
            kotlinx.coroutines.delay(600) // the screen needs a moment to build what the target opens
            LabState.setBusy(false)
        }
    }

    private suspend fun enterNow(app: CashflowApp, session: LabSession, load: Boolean) {
        val scenario = LabScenarios.byId(session.scenarioId) ?: return
        if (load) LabApply.load(app, scenario)
        val step = scenario.steps[session.step]
        // The step may move the Lab clock (the time machine) by itself, relative to the scenario's own clock.
        val base = scenario.clock
        if (base != null && step.clockShiftDays != LabState.shiftDays) {
            LabState.setClockTo(base.plusDays(step.clockShiftDays.toLong()))
            LabState.shiftDays = step.clockShiftDays
            LabApply.clockMoved(app)
        }
        UiTargets.request(step.target(scenario.today(AppClock.today().minusDays(step.clockShiftDays.toLong()))))
    }
}
