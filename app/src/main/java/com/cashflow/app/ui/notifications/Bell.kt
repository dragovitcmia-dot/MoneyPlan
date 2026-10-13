package com.cashflow.app.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cashflow.app.R
import com.cashflow.app.engine.NotificationCenterModel

const val BELL_TAP_DP = 48

/** The slim bar above every main screen: the bell with its unread badge at the top right. */
@Composable
fun AppTopBar(unread: Int, onBell: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).statusBarsPadding().height(BELL_TAP_DP.dp).padding(end = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) { Bell(unread, onBell) }
}

/**
 * A 48 dp tap target. The badge is a Material [BadgedBox] badge anchored to the top-right corner of the bell icon:
 * about 16 dp high, the count centered in 10 sp bold, "9+" above nine, hidden at zero. Nothing around it clips it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bell(unread: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val description = if (unread > 0) stringResource(R.string.bell_desc_unread, unread) else stringResource(R.string.bell_desc)
    val badge = NotificationCenterModel.badge(unread)
    Box(
        Modifier.size(BELL_TAP_DP.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 24.dp),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        BadgedBox(
            badge = {
                if (badge != null) {
                    Badge(containerColor = colors.primary, contentColor = colors.onPrimary) {
                        Text(badge, fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center)
                    }
                }
            },
        ) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(26.dp))
        }
    }
}
