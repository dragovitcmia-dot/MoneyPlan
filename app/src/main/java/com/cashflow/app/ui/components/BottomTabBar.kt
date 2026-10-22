package com.cashflow.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Sizes of the bottom bar, as plain numbers so a unit test can hold them. */
object NavBarSpec {
    /** Height of the bar above the system navigation inset; every item is this tall. */
    const val BAR_HEIGHT_DP = 64
    const val ICON_DP = 26

    /** Android's minimum comfortable touch target. */
    const val MIN_TARGET_DP = 48

    /** The CI geometry check wants every item at least this share of the screen width (five items: exactly a fifth). */
    const val MIN_WIDTH_SHARE = 0.20

    /** Every item is at least this wide (dp), whatever the screen: five items fit down to 240 dp. */
    const val MIN_ITEM_WIDTH_DP = 48

    fun cellWidthDp(screenWidthDp: Double, items: Int): Double = screenWidthDp / items
}

class NavTab(val icon: ImageVector, @StringRes val label: Int)

/**
 * The bottom bar: every item takes an equal share of the width and the FULL bar height as its tap area. The
 * selectable sits on the whole cell, not on the icon or the label, and has a bounded ripple.
 */
@Composable
fun BottomTabBar(tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().height(NavBarSpec.BAR_HEIGHT_DP.dp)) {
                tabs.forEachIndexed { index, tab ->
                    TabCell(tab, selected == index, { onSelect(index) }, Modifier.weight(1f).fillMaxHeight())
                }
            }
            Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun TabCell(tab: NavTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.selectable(
            selected = selected,
            onClick = onClick,
            role = Role.Tab,
            interactionSource = source,
            indication = ripple(bounded = true, color = colors.onSurface.copy(alpha = 0.10f)), // a very faint press feedback
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            // A bar on the top edge of the cell: the selected tab is unmistakable at a glance.
            Box(
                Modifier.align(Alignment.TopCenter).width(32.dp).height(3.dp)
                    .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                    .background(colors.primary),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                tab.icon,
                contentDescription = null,
                modifier = Modifier.size(NavBarSpec.ICON_DP.dp),
                tint = if (selected) colors.primary else colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FitText(
                stringResource(tab.label),
                if (selected) colors.primary else colors.onSurfaceVariant,
                MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            )
        }
    }
}
