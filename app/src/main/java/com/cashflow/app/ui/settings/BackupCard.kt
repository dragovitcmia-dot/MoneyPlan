package com.cashflow.app.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.CashflowApp
import com.cashflow.app.R
import com.cashflow.app.backup.BackupCheck
import com.cashflow.app.backup.Manifest
import com.cashflow.app.backup.SnapshotInfo
import com.cashflow.app.backup.SnapshotLabel
import com.cashflow.app.backup.SnapshotType
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** What a confirmation dialog is about: a stored snapshot, or a file the user picked. */
private class RestoreTarget(val file: File, val whenText: String, val items: Long?, val imported: Boolean)

/**
 * Settings > Backup: "Back up now", the snapshots (newest first, with their kind and size, each with Restore), and export or
 * import of one small .cfbackup file. A restore always saves the current state first and then restarts the app.
 */
@Composable
internal fun BackupCard() {
    val context = LocalContext.current
    val app = context.applicationContext as CashflowApp
    val manager = app.backupManager
    val scope = rememberCoroutineScope()
    val snapshots by manager.snapshots.collectAsStateWithLifecycle()
    var showAll by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var target by remember { mutableStateOf<RestoreTarget?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { manager.refresh() } }
    val zone = remember { ZoneId.systemDefault() }

    fun explain(check: BackupCheck): Int = when (check) {
        is BackupCheck.TooNew -> R.string.backup_too_new
        is BackupCheck.TooOld -> R.string.backup_too_old
        is BackupCheck.Corrupt -> R.string.backup_corrupt
        BackupCheck.Unreadable -> R.string.backup_unreadable
        is BackupCheck.WrongVariant -> R.string.backup_lab_refused
        is BackupCheck.Ok -> R.string.backup_done
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
                message = R.string.backup_unreadable
            } else {
                when (val check = withContext(Dispatchers.IO) { manager.check(picked) }) {
                    is BackupCheck.Ok -> target = RestoreTarget(picked, whenOf(check.manifest, zone), check.manifest.items, imported = true)
                    else -> message = explain(check)
                }
            }
            busy = false
        }
    }

    SectionCard {
        Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleLarge)
        AppButton(
            stringResource(R.string.backup_now),
            onClick = {
                scope.launch {
                    busy = true
                    message = if (manager.snapshot(SnapshotType.MANUAL) != null) R.string.backup_done else R.string.backup_failed
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        message?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (snapshots.isEmpty()) {
            Text(stringResource(R.string.backup_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val shown = if (showAll) snapshots else snapshots.take(INITIAL_ROWS)
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                for ((index, info) in shown.withIndex()) {
                    if (index > 0) HorizontalDivider()
                    SnapshotRow(info, zone, enabled = !busy) {
                        target = RestoreTarget(manager.file(info), SnapshotLabel.time(info, zone), null, imported = false)
                    }
                }
            }
            if (snapshots.size > INITIAL_ROWS) {
                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (showAll) stringResource(R.string.backup_show_less) else stringResource(R.string.backup_show_all, snapshots.size))
                }
            }
        }
        AppButton(
            stringResource(R.string.backup_export),
            onClick = {
                scope.launch {
                    busy = true
                    val file = manager.exportFile()
                    if (file == null) {
                        message = R.string.backup_failed
                    } else {
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("application/octet-stream")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.backup_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        message = R.string.backup_done
                    }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            style = AppButtonStyle.Outlined,
        )
        AppButton(
            stringResource(R.string.backup_import),
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            style = AppButtonStyle.Outlined,
        )
    }

    target?.let { t ->
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(stringResource(R.string.backup_restore_title)) },
            text = {
                Text(
                    if (t.imported) stringResource(R.string.backup_import_body, t.whenText, (t.items ?: 0L).toInt())
                    else stringResource(R.string.backup_restore_body, t.whenText),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        target = null
                        scope.launch {
                            busy = true
                            message = R.string.backup_restoring
                            val ok = (context as? Activity)?.let { manager.restore(t.file, it) } ?: false
                            if (!ok) {
                                message = R.string.backup_failed
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.backup_confirm_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { target = null }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private const val INITIAL_ROWS = 6

private fun whenOf(manifest: Manifest, zone: ZoneId): String =
    SnapshotLabel.time(SnapshotInfo("", manifest.createdAtMillis, manifest.type, 0), zone)

@Composable
private fun SnapshotRow(info: SnapshotInfo, zone: ZoneId, enabled: Boolean, onRestore: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(SnapshotLabel.time(info, zone), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                    Text(SnapshotLabel.tag(info), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Text(SnapshotLabel.size(info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onRestore, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.backup_restore)) }
    }
}
