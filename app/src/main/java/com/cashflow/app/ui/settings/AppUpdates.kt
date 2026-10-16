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

    SectionCard {
        Text(stringResource(R.string.updates_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.version_line, manager.installedVersionName), style = MaterialTheme.typography.bodyLarge)

        when (val s = state) {
            UpdateState.Idle -> Unit
            UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(stringResource(R.string.checking), style = MaterialTheme.typography.bodyLarge)
            }
            UpdateState.UpToDate -> Text(stringResource(R.string.up_to_date), style = MaterialTheme.typography.bodyLarge)
            is UpdateState.Downloading -> {
                Text(stringResource(R.string.downloading_version, s.version), style = MaterialTheme.typography.bodyLarge)
                if (s.progress == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            is UpdateState.Installing -> {
                Text(stringResource(R.string.installing_version, s.version), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.install_notice), style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is UpdateState.NeedsInstallPermission -> Unit // the explanation dialog below
            is UpdateState.WaitingForPermission -> Text(stringResource(R.string.waiting_permission), style = MaterialTheme.typography.bodyLarge)
            is UpdateState.Error -> Text(s.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }

        AppButton(
            stringResource(R.string.check_updates),
            onClick = manager::check,
            enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading && state !is UpdateState.Installing,
            modifier = Modifier.fillMaxWidth(),
            style = AppButtonStyle.Outlined,
        )
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
