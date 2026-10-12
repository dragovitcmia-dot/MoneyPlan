package com.cashflow.app.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.cashflow.app.CashflowApp
import com.cashflow.app.R
import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.SectionCard
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Debug and preview builds only; the release source set has an empty stand-in with the same signature. */
@Composable
fun DebugSection(repository: LedgerRepository, onDataChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val app = context.applicationContext as CashflowApp
    var confirmClear by remember { mutableStateOf(false) }

    SectionCard {
        Text(stringResource(R.string.developer_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.developer_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppButton(
            stringResource(R.string.debug_load_sample),
            onClick = {
                scope.launch {
                    repository.loadReferenceScenario(LocalDate.now())
                    onDataChanged()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        AppButton(
            stringResource(R.string.debug_send_test),
            onClick = { scope.launch { app.reminderRunner.sendTest() } },
            modifier = Modifier.fillMaxWidth(),
            style = AppButtonStyle.Outlined,
        )
        AppButton(
            stringResource(R.string.debug_run_check),
            onClick = {
                scope.launch {
                    val done = app.reminderRunner.run()
                    Toast.makeText(context, context.getString(R.string.debug_check_done, done.size), Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            style = AppButtonStyle.Outlined,
        )
        AppButton(
            stringResource(R.string.debug_clear_all),
            onClick = { confirmClear = true },
            modifier = Modifier.fillMaxWidth(),
            style = AppButtonStyle.Outlined,
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.clear_body)) } },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        repository.clearAll(LocalDate.now())
                        onDataChanged()
                    }
                }) { Text(stringResource(R.string.clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
