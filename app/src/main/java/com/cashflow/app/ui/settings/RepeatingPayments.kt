package com.cashflow.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.R
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.RecurringRule
import com.cashflow.app.engine.TxKind
import com.cashflow.app.ui.components.SectionCard
import com.cashflow.app.ui.theme.cashflow
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val UntilDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** The "Repeating payments" card: every repeating expense with its schedule and an on/off switch; tap to change the whole series. */
@Composable
internal fun RepeatingPaymentsCard(viewModel: SettingsViewModel) {
    val payments by viewModel.repeating.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RecurringRule?>(null) }

    SectionCard {
        Text(stringResource(R.string.repeating_title), style = MaterialTheme.typography.titleLarge)
        if (payments.isEmpty()) {
            Text(stringResource(R.string.repeating_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (rule in payments) {
            val switchLabel = stringResource(R.string.repeating_switch_description, rule.description)
            Card(Modifier.fillMaxWidth().clickable { editing = rule }) {
                Row(Modifier.heightIn(min = 56.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(rule.description, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            Money.formatSigned(rule.kind, rule.amount),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (rule.kind == TxKind.INCOME) MaterialTheme.cashflow.positive else MaterialTheme.cashflow.negative,
                        )
                        Text(
                            scheduleSummary(rule.pattern) + (rule.endDate?.let { " · " + stringResource(R.string.repeating_until, it.format(UntilDate)) } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { viewModel.setSourceEnabled(rule.id, it) },
                        modifier = Modifier.semantics { contentDescription = switchLabel },
                    )
                }
            }
        }
    }

    editing?.let { rule ->
        RuleDialog(
            kind = rule.kind,
            initial = rule,
            newStart = rule.startDate,
            onSave = { viewModel.saveSource(it); editing = null },
            onStop = { viewModel.stopRepeating(rule.id); editing = null },
            onDismiss = { editing = null },
        )
    }
}
