package com.cashflow.app.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cashflow.app.CashflowApp
import com.cashflow.app.R
import com.cashflow.app.engine.BellEntry
import com.cashflow.app.engine.BellSection
import com.cashflow.app.engine.BellTone
import com.cashflow.app.engine.BellTones
import com.cashflow.app.ui.theme.cashflow
import com.cashflow.app.engine.NotificationCenterModel
import com.cashflow.app.engine.NotificationType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

private fun iconOf(type: NotificationType): ImageVector = when (type) {
    NotificationType.HEADS_UP_WEEK, NotificationType.HEADS_UP_DAY -> Icons.Filled.DateRange
    NotificationType.DAY_OF -> Icons.Filled.Notifications
    NotificationType.SHORTAGE -> Icons.Filled.Warning
    NotificationType.AUTO_INCOME -> Icons.Filled.Check
    NotificationType.INFO -> Icons.Filled.Info
}

/** The bell's history: newest first in Today / Earlier; tap a row to open its screen, swipe to remove it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterScreen(
    app: CashflowApp,
    contentPadding: PaddingValues,
    onClose: () -> Unit,
    onOpen: (BellEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val entries by app.notificationCenter.entries.collectAsStateWithLifecycle(emptyList())
    val zone = remember { ZoneId.systemDefault() }
    val now = remember(entries) { Instant.now() }
    val groups = remember(entries, now) { NotificationCenterModel.group(entries, now, zone) }
    val anyUnread = entries.any { it.unread }

    Column(
        Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding(), start = 8.dp, end = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.center_back))
            }
            Text(stringResource(R.string.center_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { app.notificationCenter.markAllRead() } }, enabled = anyUnread, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.center_mark_all))
            }
        }
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.center_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 24.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(start = 12.dp),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((section, rows) in groups) {
                    item(key = "header-$section") {
                        Text(
                            stringResource(if (section == BellSection.TODAY) R.string.center_today else R.string.center_earlier),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(rows, key = { it.id }) { entry ->
                        SwipeToRemove(onRemove = { scope.launch { app.notificationCenter.remove(entry.id) } }) {
                            BellRow(entry, NotificationCenterModel.relativeTime(entry.createdAt, now, zone)) {
                                scope.launch { app.notificationCenter.markRead(entry.id) }
                                onOpen(entry)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemove(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onRemove()
                true
            } else {
                false
            }
        },
        positionalThreshold = { total -> total * 0.4f },
    )
    val label = stringResource(R.string.center_remove)
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val active = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium).background(if (active) MaterialTheme.colorScheme.errorContainer else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (active) Icon(Icons.Filled.Delete, contentDescription = label, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) { content() }
}

@Composable
private fun BellRow(entry: BellEntry, time: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val unreadText = stringResource(R.string.center_unread_marker)
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(if (entry.unread) colors.surfaceContainerHighest else colors.surface)
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { if (entry.unread) contentDescription = "$unreadText. ${entry.title}. ${entry.body}. $time" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val cash = MaterialTheme.cashflow
        val tone = BellTones.of(entry.type, entry.amountMillimes)
        val (fill, ink) = when (tone) {
            BellTone.EXPENSE -> cash.expenseFill to cash.buttonLabel
            BellTone.INCOME -> cash.incomeFill to cash.buttonLabel
            BellTone.SHORTAGE -> cash.badge to colors.background
            BellTone.INFO -> colors.secondaryContainer to colors.onSecondaryContainer
        }
        Box(Modifier.size(32.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            Icon(iconOf(entry.type), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (entry.unread) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
            )
            if (entry.body.isNotEmpty()) {
                Text(entry.body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(time, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
            if (entry.unread) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.primary))
        }
    }
}
