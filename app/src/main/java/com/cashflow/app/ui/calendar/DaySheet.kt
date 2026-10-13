package com.cashflow.app.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cashflow.app.R
import com.cashflow.app.engine.AppDates
import com.cashflow.app.engine.DayItem
import com.cashflow.app.engine.DaySummary
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import com.cashflow.app.engine.TxStatus
import com.cashflow.app.ui.components.AppCard
import com.cashflow.app.ui.components.NameWithRepeat
import com.cashflow.app.ui.components.KindButtons
import com.cashflow.app.ui.theme.cashflow
import java.time.LocalDate


/** Everything about one day: the closing balance, its items (tap one to change it) and the add buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySheet(
    date: LocalDate,
    summary: DaySummary?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onOpenItem: (Transaction) -> Unit,
    onAdd: (TxKind) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(AppDates.withWeekday(date), style = MaterialTheme.typography.headlineSmall)

            if (summary != null && summary.hasForecast) {
                val amount = Money.formatDisplay(if (summary.closing < 0) -summary.closing else summary.closing, withCurrency = false)
                Text(
                    stringResource(
                        when {
                            summary.negative -> R.string.day_end_short
                            date.isBefore(today) -> R.string.day_end_had
                            else -> R.string.day_end_have
                        },
                        amount,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (summary.negative) MaterialTheme.cashflow.negative else MaterialTheme.colorScheme.onSurface,
                )
            }

            if (summary == null || summary.items.isEmpty()) {
                Text(
                    stringResource(R.string.day_nothing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (item in summary.items) {
                    DayItemRow(item, today, onClick = { onOpenItem(item.tx) })
                }
            }

            KindButtons(onExpense = { onAdd(TxKind.EXPENSE) }, onIncome = { onAdd(TxKind.INCOME) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayItemRow(item: DayItem, today: LocalDate, onClick: () -> Unit) {
    val tx = item.tx
    val income = tx.kind == TxKind.INCOME
    val skipped = tx.status == TxStatus.SKIPPED
    val colors = MaterialTheme.cashflow
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NameWithRepeat(
                    tx.description.ifBlank { stringResource(if (income) R.string.item_income else R.string.item_expense) },
                    repeating = item.fromSeries,
                    income = income,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(statusText(tx, today), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.last) com.cashflow.app.ui.upcoming.LastTag()
                }
            }
            Text(
                Money.formatSigned(tx.kind, tx.amount),
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    skipped -> MaterialTheme.colorScheme.onSurfaceVariant
                    income -> colors.positive
                    else -> colors.negative
                },
                textDecoration = if (skipped) TextDecoration.LineThrough else null,
            )
        }
    }
}

@Composable
internal fun statusText(tx: Transaction, today: LocalDate): String = stringResource(
    when (tx.status) {
        TxStatus.CONFIRMED -> R.string.status_done
        TxStatus.SKIPPED -> R.string.status_skipped
        TxStatus.PENDING -> if (tx.date.isAfter(today)) R.string.status_planned else R.string.status_waiting
    },
)
