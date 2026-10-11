package com.cashflow.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.R
import com.cashflow.app.engine.AppSettings
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.RecurrencePattern
import com.cashflow.app.engine.RecurringRule
import com.cashflow.app.engine.SettingsLayout
import com.cashflow.app.engine.TxKind
import com.cashflow.app.engine.WalletMode
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.SectionCard
import com.cashflow.app.ui.theme.cashflow
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

private fun dayName(d: DayOfWeek) = d.getDisplayName(TextStyle.FULL, Locale.getDefault())

@Composable
internal fun scheduleSummary(p: RecurrencePattern): String = when (p) {
    RecurrencePattern.Daily -> stringResource(R.string.sched_daily)
    is RecurrencePattern.Weekly -> stringResource(R.string.sched_weekly, dayName(p.dayOfWeek))
    is RecurrencePattern.DayOfMonth -> stringResource(R.string.sched_month_day, p.day)
    RecurrencePattern.MonthFirst -> stringResource(R.string.sched_first)
    RecurrencePattern.MonthLast -> stringResource(R.string.sched_last)
    is RecurrencePattern.EveryNDays -> stringResource(R.string.sched_every_n, p.n)
}

/**
 * The "Income" card: the automatic-income switch, directly below it the regular income list with its own
 * switches and the add button, and the reminder time. With the main switch off the list stays readable
 * but is dimmed and cannot be touched; the items and their own switches are kept exactly as they were.
 */
@Composable
internal fun IncomeCard(viewModel: SettingsViewModel, settings: AppSettings, layout: SettingsLayout) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RecurringRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    val interactive = layout.incomeListInteractive

    SectionCard {
        Text(stringResource(R.string.income_card_title), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.auto_income_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (settings.walletMode == WalletMode.DYNAMIC) R.string.auto_income_on else R.string.auto_income_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val autoTitle = stringResource(R.string.auto_income_title)
            Switch(
                modifier = Modifier.semantics { contentDescription = autoTitle },
                checked = settings.walletMode == WalletMode.DYNAMIC,
                onCheckedChange = { dynamic ->
                    viewModel.update { it.copy(walletMode = if (dynamic) WalletMode.DYNAMIC else WalletMode.STATIC) }
                },
            )
        }
        HorizontalDivider(color = MaterialTheme.cashflow.hairline)

        Text(stringResource(R.string.regular_income_title), style = MaterialTheme.typography.titleMedium)
        if (layout.incomeOffNote) {
            Text(stringResource(R.string.income_not_auto), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (sources.isEmpty()) {
            Text(stringResource(R.string.regular_income_empty), style = MaterialTheme.typography.bodyMedium)
        }
        for (source in sources) {
            // Dimmed = softer text colours (still above 4.5:1), disabled switch and no tap; never an alpha fade.
            val textColor = if (layout.incomeListDimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            Card(
                Modifier.fillMaxWidth().clickable(enabled = interactive) { editing = source },
                colors = CardDefaults.cardColors(
                    containerColor = if (layout.incomeListDimmed) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(Modifier.heightIn(min = 56.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.description, style = MaterialTheme.typography.bodyLarge, color = textColor)
                        Text(
                            stringResource(R.string.amount_and_schedule, Money.formatDisplay(source.amount), scheduleSummary(source.pattern)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = source.enabled,
                        enabled = interactive,
                        onCheckedChange = { viewModel.setSourceEnabled(source.id, it) },
                        modifier = Modifier.semantics { contentDescription = source.description },
                    )
                }
            }
        }
        AppButton(
            stringResource(R.string.add_regular_income),
            onClick = { adding = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = interactive,
            style = AppButtonStyle.Outlined,
        )
        TimeField(
            label = stringResource(R.string.remind_label),
            value = settings.reminderTime,
            onPick = { t -> viewModel.update { it.copy(reminderTime = t) } },
        )
    }

    if (adding) {
        RuleDialog(
            kind = TxKind.INCOME,
            initial = null,
            newStart = viewModel.newSourceStartDate(),
            onSave = { viewModel.saveSource(it); adding = false },
            onDelete = null,
            onStop = null,
            onDismiss = { adding = false },
        )
    }
    editing?.let { source ->
        RuleDialog(
            kind = TxKind.INCOME,
            initial = source,
            newStart = source.startDate,
            onSave = { viewModel.saveSource(it); editing = null },
            onDelete = { viewModel.deleteSource(source.id); editing = null },
            onStop = null,
            onDismiss = { editing = null },
        )
    }
}
