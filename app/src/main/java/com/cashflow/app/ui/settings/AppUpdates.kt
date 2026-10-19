package com.cashflow.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import com.cashflow.app.ui.theme.Spacing
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.R
import com.cashflow.app.ui.components.AppButton
import com.cashflow.app.ui.components.AppButtonStyle
import com.cashflow.app.ui.components.SectionCard
import com.cashflow.app.update.UpdateManager
import com.cashflow.app.update.UpdateState

/** One button: it looks for a new version and, if there is one, downloads and installs it. */
@Composable
internal fun AppUpdatesSection(manager: UpdateManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    // Back from the "install unknown apps" screen: the update carries on by itself if it was allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { manager.onResumed() }

    val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
    val subtitle = when (val s = state) {
        UpdateState.Idle -> stringResource(R.string.version_line, manager.installedVersionName)
        UpdateState.Checking -> stringResource(R.string.checking)
        UpdateState.UpToDate -> stringResource(R.string.version_line, manager.installedVersionName) + " \u00B7 " + stringResource(R.string.up_to_date)
        is UpdateState.Downloading -> stringResource(R.string.downloading_version, s.version)
        is UpdateState.Installing -> stringResource(R.string.installing_version, s.version)
        is UpdateState.NeedsInstallPermission -> stringResource(R.string.version_line, manager.installedVersionName)
        is UpdateState.WaitingForPermission -> stringResource(R.string.waiting_permission)
        is UpdateState.Error -> s.message
    }
    SettingsGroup(stringResource(R.string.updates_title)) {
        SettingsRow(
            title = stringResource(R.string.check_updates),
            subtitle = subtitle,
            onClick = if (busy) null else manager::check,
        ) {
            if (state is UpdateState.Checking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
        when (val s = state) {
            is UpdateState.Downloading ->
                if (s.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s))
                else LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s))
            is UpdateState.Installing -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s))
            else -> Unit
        }
    }

    if (state is UpdateState.NeedsInstallPermission) {
        AlertDialog(
            onDismissRequest = manager::dismissPermissionPrompt,
            title = { Text(stringResource(R.string.permission_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.permission_body)) } },
            confirmButton = { TextButton(onClick = manager::openInstallPermissionSettings) { Text(stringResource(R.string.permission_continue)) } },
            dismissButton = { TextButton(onClick = manager::dismissPermissionPrompt) { Text(stringResource(R.string.permission_not_now)) } },
        )
    }
}
