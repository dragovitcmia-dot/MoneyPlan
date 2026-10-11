package com.cashflow.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.update.UpdateManager
import com.cashflow.app.update.UpdateState

@Composable
internal fun AppUpdatesSection(manager: UpdateManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    // Back from the "install unknown apps" screen: continue with the install if it was granted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { manager.onResumed() }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("App updates", style = MaterialTheme.typography.titleMedium)
        Text("Installed: v${manager.installedVersionName} (${manager.installedVersionCode})")

        when (val s = state) {
            UpdateState.Idle -> Unit
            UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Checking…")
            }
            UpdateState.UpToDate -> Text("You're up to date")
            is UpdateState.UpdateAvailable -> {
                Text("Update available: v${s.version}")
                Button(onClick = manager::download, modifier = Modifier.fillMaxWidth()) { Text("Update") }
            }
            is UpdateState.Downloading -> {
                Text("Downloading…")
                if (s.progress == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            UpdateState.ReadyToInstall -> {
                Text("Update downloaded and verified.")
                Button(onClick = manager::install, modifier = Modifier.fillMaxWidth()) { Text("Install update") }
            }
            UpdateState.NeedsInstallPermission -> {
                Text("To install the update, allow Cashflow to install apps. Turn on \"Allow from this source\", then come back.")
                Button(onClick = manager::openInstallPermissionSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open settings")
                }
            }
            is UpdateState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
        }

        OutlinedButton(
            onClick = manager::check,
            enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Check for updates") }
    }
}
