package com.cashflow.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cashflow.app.R
import com.cashflow.app.engine.AppSettings
import com.cashflow.app.engine.RecurrencePattern
import com.cashflow.app.ui.components.SectionCard
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
 * The "Income" card: one switch, "Accept my income automatically", and the time from which the day's income is
 * accepted ("Remind me at"). Repeating income series are listed with the other series under "Repeating payments".
 */
@Composable
internal fun IncomeCard(viewModel: SettingsViewModel, settings: AppSettings) {
    SectionCard {
        Text(stringResource(R.string.income_card_title), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.auto_income_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.auto_income_helper),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val autoTitle = stringResource(R.string.auto_income_title)
            Switch(
                modifier = Modifier.semantics { contentDescription = autoTitle },
                checked = settings.autoAcceptIncome,
                onCheckedChange = { on -> viewModel.update { it.copy(autoAcceptIncome = on) } },
            )
        }
        TimeField(
            label = stringResource(R.string.remind_label),
            value = settings.reminderTime,
            onPick = { t -> viewModel.update { it.copy(reminderTime = t) } },
        )
    }
}
