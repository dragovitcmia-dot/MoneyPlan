package com.cashflow.app.ui.items

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.ui.settings.RuleDialog
import com.cashflow.app.ui.settings.SettingsViewModel

/** Opens the series an item came from for editing (the same dialog as in Settings). */
@Composable
fun RuleEditorHost(ruleId: Long, settings: SettingsViewModel, onDismiss: () -> Unit) {
    val rules by settings.rules.collectAsStateWithLifecycle()
    val rule = rules.firstOrNull { it.id == ruleId } ?: return // still loading, or the series is gone
    RuleDialog(
        kind = rule.kind,
        initial = rule,
        newStart = rule.startDate,
        onSave = { settings.saveSource(it); onDismiss() },
        onStop = { settings.stopRepeating(rule.id); onDismiss() },
        onDismiss = onDismiss,
    )
}
