package com.cashflow.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.NextExpense
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import kotlinx.coroutines.launch

/** Position of the "To confirm" header in the list below; the chip scrolls here. */
private const val TO_CONFIRM_INDEX = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cashflow") },
                actions = { TextButton(onClick = onOpenSettings) { Text("Settings") } },
            )
        },
    ) { padding ->
        val ui = state
        if (ui == null) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
        } else {
            HomeContent(ui, viewModel::confirm, viewModel::skip, Modifier.padding(padding))
        }
    }
}

@Composable
private fun HomeContent(
    ui: HomeUiState,
    onConfirm: (Long) -> Unit,
    onSkip: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "available") {
            Column(
                Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Available", style = MaterialTheme.typography.labelLarge)
                Text(
                    Money.format(ui.available),
                    style = MaterialTheme.typography.displayMedium,
                    color = if (ui.available < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                if (ui.toConfirm.isNotEmpty()) {
                    AssistChip(
                        onClick = { scope.launch { listState.animateScrollToItem(TO_CONFIRM_INDEX) } },
                        label = { Text("${ui.toConfirm.size} to confirm") },
                    )
                }
            }
        }
        item(key = "next") { NextExpenseCard(ui.nextExpense) }
        item(key = "buttons") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("Add expense") }
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("Add income") }
            }
        }
        item(key = "toConfirmHeader") {
            Text("To confirm", style = MaterialTheme.typography.titleMedium)
        }
        if (ui.toConfirm.isEmpty()) {
            item(key = "caughtUp") {
                Text(
                    "All caught up",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(ui.toConfirm, key = { it.id }) { tx -> ConfirmCard(tx, onConfirm, onSkip) }
        }
        item(key = "bottomSpace") { Column(Modifier.padding(bottom = 16.dp)) {} }
    }
}

@Composable
private fun NextExpenseCard(next: NextExpense?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (next == null) {
                Text("No upcoming expenses", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("Next expense", style = MaterialTheme.typography.labelLarge)
                Text(
                    "${inDays(next.daysAway)}, ${Money.format(next.amount)}",
                    style = MaterialTheme.typography.titleLarge,
                )
                if (next.description.isNotBlank()) {
                    Text(next.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ConfirmCard(tx: Transaction, onConfirm: (Long) -> Unit, onSkip: (Long) -> Unit) {
    val income = tx.kind == TxKind.INCOME
    val amount = (if (income) "+" else "-") + Money.format(tx.amount)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(tx.date.toString(), style = MaterialTheme.typography.labelLarge)
                    Text(
                        tx.description.ifBlank { if (income) "Income" else "Expense" },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    amount,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (income) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConfirm(tx.id) }) { Text("Confirm") }
                OutlinedButton(onClick = { onSkip(tx.id) }) { Text("Skip") }
            }
        }
    }
}

private fun inDays(days: Int) = when (days) {
    0 -> "today"
    1 -> "in 1 day"
    else -> "in $days days"
}
