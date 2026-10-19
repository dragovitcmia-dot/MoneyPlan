package com.cashflow.app.lab

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.CashflowApp
import com.cashflow.app.R
import com.cashflow.app.backup.BackupCheck
import com.cashflow.app.data.LedgerRepository
import com.cashflow.app.engine.AppClock
import com.cashflow.app.engine.AppDates
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter

/**
 * Settings in the Lab build: scenarios, the time machine, the notifications switch, reset, importing a real backup as a copy
 * and the developer buttons. It replaces "Developer tools" there. Lab only: the real app has none of this code.
 */
@Composable
internal fun LabCard(@Suppress("UNUSED_PARAMETER") repository: LedgerRepository, onDataChanged: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CashflowApp
    val scope = rememberCoroutineScope()
    val offset by LabState.offset.collectAsStateWithLifecycle()
    var notificationsOn by remember { mutableStateOf(LabState.notificationsOn) }
    var confirmReset by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf<ImportTarget?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    fun launchBusy(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            block()
            tick++
            onDataChanged()
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val picked = withContext(Dispatchers.IO) {
                val copy = File(app.cacheDir, "import.cfb")
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                    copy
                }.getOrNull()
            }
            if (picked == null) {
                message = "Could not read that file."
            } else {
                when (val check = withContext(Dispatchers.IO) { app.backupManager.check(picked) }) {
                    is BackupCheck.Ok -> imported = ImportTarget(picked, check.manifest.items, check.manifest.createdAtMillis)
                    is BackupCheck.WrongVariant -> message = "That is not a real backup."
                    is BackupCheck.TooNew -> message = context.getString(R.string.backup_too_new)
                    is BackupCheck.TooOld -> message = context.getString(R.string.backup_too_old)
                    is BackupCheck.Corrupt -> message = context.getString(R.string.backup_corrupt)
                    BackupCheck.Unreadable -> message = context.getString(R.string.backup_unreadable)
                }
            }
            busy = false
        }
    }

    SectionCard {
        Text("Cashflow Lab", style = MaterialTheme.typography.titleLarge)
        Text(
            "A test copy of the app with its own data. Nothing here touches the real Cashflow.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        // ---- time machine
        Text("Time machine", style = MaterialTheme.typography.titleMedium)
        val now = remember(offset, tick) { AppClock.dateTime() }
        val days = offset / 86_400_000L
        Text(
            AppDates.format(now.toLocalDate()) + ", " + now.format(DateTimeFormatter.ofPattern("HH:mm")) +
                if (offset == 0L) "  (real time)" else "  (real time ${if (days >= 0) "+" else ""}$days d)",
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, shift) in listOf("-1 d" to -1, "+1 d" to 1, "+7 d" to 7, "+30 d" to 30)) {
                AppButton(
                    label,
                    onClick = { launchBusy { LabState.shiftDays(shift); LabApply.clockMoved(app) } },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    style = AppButtonStyle.Outlined,
                )
            }
        }
        AppButton(
            "Back to real time",
            onClick = { launchBusy { LabState.realTime(); LabApply.clockMoved(app) } },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && offset != 0L,
            style = AppButtonStyle.Outlined,
        )

        // ---- notifications
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("System notifications", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Off by default so the real app and the Lab never both notify. The bell works either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = notificationsOn,
                onCheckedChange = { notificationsOn = it; LabState.notificationsOn = it },
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        // ---- the checks (the scenarios themselves live on the Lab home)
        HorizontalDivider()
        AppButton(
            "Open Lab checks",
            onClick = { LabState.showHome() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )

        // ---- data
        HorizontalDivider()
        AppButton(
            "Reset lab data",
            onClick = { confirmReset = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            style = AppButtonStyle.Outlined,
        )
        AppButton(
            "Import real backup copy",
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            style = AppButtonStyle.Outlined,
        )

        // ---- developer buttons
        HorizontalDivider()
        Text("Developer tools", style = MaterialTheme.typography.titleMedium)
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
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset lab data?") },
            text = { Text("All Lab data is deleted and the clock goes back to real time. The real app is not touched.") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; launchBusy { LabApply.reset(app) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        )
    }
    imported?.let { target ->
        val whenText = remember(target) {
            val at = java.time.Instant.ofEpochMilli(target.createdAtMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
            AppDates.format(at.toLocalDate()) + ", " + at.format(DateTimeFormatter.ofPattern("HH:mm"))
        }
        AlertDialog(
            onDismissRequest = { imported = null },
            title = { Text("Import a real backup copy?") },
            text = { Text("Made on $whenText with ${target.items} items. It replaces the Lab data (a Before restore snapshot is saved first) and the Lab restarts. The real app is not touched.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        imported = null
                        scope.launch {
                            busy = true
                            val ok = (context as? Activity)?.let { app.backupManager.restore(target.file, it) } ?: false
                            if (!ok) { message = context.getString(R.string.backup_failed); busy = false }
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { imported = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        )
    }
}

private class ImportTarget(val file: File, val items: Long, val createdAtMillis: Long)
