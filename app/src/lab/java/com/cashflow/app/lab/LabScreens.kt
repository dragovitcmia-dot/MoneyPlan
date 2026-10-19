package com.cashflow.app.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.CashflowApp
import com.cashflow.app.debug.lab.LabScenario
import com.cashflow.app.debug.lab.LabScenarios
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The check panel: a compact collapsible strip with the 2 to 4 yes/no questions of the screen on show and two buttons, "Looks right" and
 * "Problem". It sits in the layout (above the navigation bar, or at the bottom of a sheet or dialog), never over content, so a collapsed
 * panel hides nothing. Shows nothing while no check runs.
 */
@Composable
internal fun LabPanel() {
    val session by LabState.session.collectAsStateWithLifecycle()
    val expanded by LabState.panelExpanded.collectAsStateWithLifecycle()
    val busy by LabState.busy.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as CashflowApp
    val scope = rememberCoroutineScope()
    val s = session ?: return
    val scenario = LabScenarios.byId(s.scenarioId) ?: return
    val step = scenario.steps.getOrNull(s.step) ?: return

    Surface(
        Modifier.fillMaxWidth().semantics { contentDescription = "Lab check panel" },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { LabState.setPanelExpanded(!expanded) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text(
                    "Check ${s.index + 1} of ${s.total} · ${scenario.title}" + if (scenario.steps.size > 1) " · screen ${s.step + 1}/${scenario.steps.size}" else "",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = if (expanded) "Collapse the panel" else "Expand the panel",
                    modifier = Modifier.size(24.dp),
                )
                IconButton(onClick = { LabRunner.stop() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Stop the checks", modifier = Modifier.size(24.dp))
                }
            }
            if (busy) {
                Text("Preparing the data and the screen…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = Spacing.m))
            } else if (expanded) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    for (check in step.checks) Text("• $check", style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    AppButton("Problem", onClick = { LabRunner.markAsync(app, false) }, modifier = Modifier.weight(1f), style = AppButtonStyle.Outlined)
                    AppButton("Looks right", onClick = { LabRunner.markAsync(app, true) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** The Lab home and the Results screen: they cover the tab content (the top bar with the LAB tag and the bottom bar stay). */
@Composable
internal fun LabOverlays(onOpenSettings: () -> Unit) {
    val overlay by LabState.overlay.collectAsStateWithLifecycle()
    val running by LabState.session.collectAsStateWithLifecycle()
    if (running != null) return
    when (overlay) {
        LabState.Overlay.NONE -> Unit
        LabState.Overlay.HOME -> LabHome(onOpenSettings)
        LabState.Overlay.RESULTS -> LabResultsScreen()
    }
}

@Composable
private fun LabHome(onOpenSettings: () -> Unit) {
    val results by LabState.results.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as CashflowApp
    val scope = rememberCoroutineScope()
    val ids = LabScenarios.all.map { it.id }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 104.dp, start = Spacing.l, end = Spacing.l, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text("Cashflow Lab", style = MaterialTheme.typography.headlineMedium)
            AppButton("Run all checks", onClick = { LabRunner.beginAsync(app, ids) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                AppButton("Results", onClick = { LabState.showResults() }, modifier = Modifier.weight(1f), style = AppButtonStyle.Outlined)
                AppButton("Tools", onClick = { LabState.hideOverlay(); onOpenSettings() }, modifier = Modifier.weight(1f), style = AppButtonStyle.Outlined)
            }
            Text(results.summary(ids), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (scenario in LabScenarios.all) ScenarioRow(scenario, results.mark(scenario.id)) { LabRunner.beginAsync(app, listOf(scenario.id)) }
        }
    }
}

@Composable
private fun ScenarioRow(scenario: LabScenario, mark: Boolean?, onRun: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onRun).padding(vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(markGlyph(mark), style = MaterialTheme.typography.titleLarge, color = markColor(mark), modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(scenario.title, style = MaterialTheme.typography.titleMedium)
                Text(scenario.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Run", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun markGlyph(mark: Boolean?) = when (mark) {
    true -> "✓"
    false -> "✗"
    null -> "–"
}

@Composable
private fun markColor(mark: Boolean?) = when (mark) {
    true -> MaterialTheme.colorScheme.onSurface
    false -> MaterialTheme.colorScheme.error
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** One line per scenario with a tick or a cross, readable at a glance in one screenshot. */
@Composable
private fun LabResultsScreen() {
    val results by LabState.results.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as CashflowApp
    val scope = rememberCoroutineScope()
    val ids = LabScenarios.all.map { it.id }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().padding(top = 104.dp, start = Spacing.l, end = Spacing.l, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text("Results", style = MaterialTheme.typography.headlineMedium)
            Text(results.summary(ids), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (scenario in LabScenarios.all) {
                    val mark = results.mark(scenario.id)
                    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        Text(markGlyph(mark), style = MaterialTheme.typography.titleMedium, color = markColor(mark), fontWeight = FontWeight.Bold, modifier = Modifier.size(24.dp))
                        Text(scenario.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                AppButton("Lab home", onClick = { LabState.showHome() }, modifier = Modifier.weight(1f), style = AppButtonStyle.Outlined)
                AppButton("Clear results", onClick = { LabState.clearResults() }, modifier = Modifier.weight(1f), style = AppButtonStyle.Outlined)
            }
            AppButton("Run all checks", onClick = { LabRunner.beginAsync(app, ids) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The same panel for the Move date picker: the picker is a dialog window of its own, so the panel gets a small non-modal window at the TOP
 * of the screen, created after the picker so it is on top; touches outside the panel go to the picker.
 */
@Composable
internal fun LabPickerPanel() {
    val session by LabState.session.collectAsStateWithLifecycle()
    if (session == null) return
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        androidx.compose.runtime.SideEffect {
            window?.let {
                it.setGravity(android.view.Gravity.TOP)
                it.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                it.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
        }
        LabPanel()
    }
}
