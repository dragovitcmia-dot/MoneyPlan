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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** A 48 dp tap target; the badge shows the unread count, capped at "9+". */
@Composable
fun Bell(unread: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val description = if (unread > 0) stringResource(R.string.bell_desc_unread, unread) else stringResource(R.string.bell_desc)
    val badge = NotificationCenterModel.badge(unread)
    Box(
        Modifier.size(BELL_TAP_DP.dp).clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Notifications, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(26.dp))
        if (badge != null) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 6.dp).widthIn(min = 18.dp).height(18.dp)
                    .clip(CircleShape).background(colors.primary).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge, color = colors.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}
