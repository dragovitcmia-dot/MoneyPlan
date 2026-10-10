package com.cashflow.app.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cashflow.app.data.LedgerRepository
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Debug builds only; the release source set has an empty stand-in with the same signature. */
@Composable
fun DebugSection(repository: LedgerRepository, onDataChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("Debug", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = {
                scope.launch {
                    repository.loadReferenceScenario(LocalDate.now())
                    onDataChanged()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Load sample data") }
        OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Clear all data")
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all data?") },
            text = { Text("Deletes every transaction, recurring rule and setting.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        repository.clearAll(LocalDate.now())
                        onDataChanged()
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
