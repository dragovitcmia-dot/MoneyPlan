package com.cashflow.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.engine.Money
import com.cashflow.app.engine.NextExpense

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
            HomeContent(ui, Modifier.padding(padding))
        }
    }
}

@Composable
private fun HomeContent(ui: HomeUiState, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Current balance", style = MaterialTheme.typography.labelLarge)
            Text(
                Money.format(ui.currentBalance),
                style = MaterialTheme.typography.displayMedium,
                color = if (ui.currentBalance < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (ui.balanceAfterPending != ui.currentBalance) {
                Text(
                    "After pending: ${Money.format(ui.balanceAfterPending)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (ui.balanceAfterPending < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        NextExpenseCard(ui.nextExpense)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("Add expense") }
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("Add income") }
        }
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

private fun inDays(days: Int) = when (days) {
    0 -> "today"
    1 -> "in 1 day"
    else -> "in $days days"
}
