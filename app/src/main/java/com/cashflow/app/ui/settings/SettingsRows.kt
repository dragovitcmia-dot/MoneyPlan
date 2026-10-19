package com.cashflow.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cashflow.app.ui.components.AppCard
import com.cashflow.app.ui.components.SectionHeader
import com.cashflow.app.ui.rememberHaptics
import com.cashflow.app.ui.theme.ShapeTokens
import com.cashflow.app.ui.theme.Spacing

/**
 * The ONE Settings row style: a title, one line of subtitle, and at the end a chevron (it opens something), a switch, or nothing
 * (a plain value). At least 56 dp tall. Every Settings screen is built from these rows only.
 */
@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = ShapeTokens.rowMinHeight)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** A row that opens something: the chevron says so. */
@Composable
internal fun ChevronRow(title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SettingsRow(title, subtitle, modifier, onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
    }
}

/** A row with a switch at the end (light tick on every change). The switch carries the title as its description. */
@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val haptics = rememberHaptics()
    SettingsRow(title, subtitle) {
        Switch(
            modifier = Modifier.semantics { contentDescription = title },
            checked = checked,
            onCheckedChange = { on -> haptics.tick(); onChange(on) },
            enabled = enabled,
        )
    }
}

/** A titled group of rows in ONE card, hairline dividers between the rows. */
@Composable
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader(title)
        AppCard(Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

@Composable
internal fun RowDivider() = HorizontalDivider(Modifier.padding(horizontal = Spacing.l))
