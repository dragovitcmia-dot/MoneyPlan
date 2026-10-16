package com.cashflow.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import com.cashflow.app.ui.theme.Spacing
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.CashflowApp
import com.cashflow.app.R
import com.cashflow.app.engine.AppSettings
import com.cashflow.app.ui.components.SectionCard
import com.cashflow.app.ui.notifications.LocalNotificationPermission
import com.cashflow.app.ui.notifications.NotificationPermission

/**
 * Settings > Notifications: the master switch, "Remind me at", the three kinds of reminder, the system permission
 * state and the battery hint. The toggles live in preferences; the reminder time stays with the other settings.
 */
@Composable
internal fun NotificationsCard(viewModel: SettingsViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val app = context.applicationContext as CashflowApp
    val prefs = app.notificationPrefs
    val toggles by prefs.toggles.collectAsStateWithLifecycle()
    val permission = LocalNotificationPermission.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permission?.refresh() }

    SectionCard {
        Text(stringResource(R.string.notif_card_title), style = MaterialTheme.typography.titleLarge)
        SwitchRow(
            title = stringResource(R.string.notif_master_title),
            helper = stringResource(R.string.notif_master_helper),
            checked = toggles.enabled,
            onChange = { on ->
                prefs.update { it.copy(enabled = on) }
                if (on) permission?.request() // asked in context: right when the user turns reminders on
            },
        )
        TimeField(
            label = stringResource(R.string.remind_label),
            value = settings.reminderTime,
            onPick = { t -> viewModel.update { it.copy(reminderTime = t) } },
        )
        SwitchRow(
            title = stringResource(R.string.notif_headsup_title),
            helper = stringResource(R.string.notif_headsup_helper),
            checked = toggles.headsUp,
            enabled = toggles.enabled,
            onChange = { on -> prefs.update { it.copy(headsUp = on) } },
        )
        SwitchRow(
            title = stringResource(R.string.notif_keep_title),
            helper = stringResource(R.string.notif_keep_helper),
            checked = toggles.keepReminding,
            enabled = toggles.enabled,
            onChange = { on -> prefs.update { it.copy(keepReminding = on) } },
        )
        SwitchRow(
            title = stringResource(R.string.notif_shortage_title),
            helper = stringResource(R.string.notif_shortage_helper),
            checked = toggles.shortageAlerts,
            enabled = toggles.enabled,
            onChange = { on -> prefs.update { it.copy(shortageAlerts = on) } },
        )
        val allowed = permission?.allowed ?: true
        if (!allowed) {
            Text(
                stringResource(R.string.notif_perm_blocked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!allowed) {
            OutlinedButton(
                onClick = { NotificationPermission.openNotificationSettings(context) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.notif_perm_open)) }
        }
        OutlinedButton(
            onClick = { NotificationPermission.openAppInfo(context) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(stringResource(R.string.notif_battery_open)) }
    }
}

@Composable
private fun SwitchRow(title: String, helper: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val haptics = com.cashflow.app.ui.rememberHaptics()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(helper, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = title },
            checked = checked,
            onCheckedChange = { on -> haptics.tick(); onChange(on) },
            enabled = enabled,
        )
    }
}
