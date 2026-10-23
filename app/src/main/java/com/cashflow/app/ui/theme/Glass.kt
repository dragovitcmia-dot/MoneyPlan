package com.cashflow.app.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/**
 * Whether frosted glass is on right now (Android 12+, setting on, not in battery saver) and the shared backdrop that
 * the content under the bottom bar and the notification center records for blurring. When it is off every surface
 * keeps its current opaque look: none of the modifiers below do anything.
 */
@Immutable
class Glass(val active: Boolean, val haze: HazeState?) {
    companion object {
        val Off = Glass(false, null)
    }
}

val LocalGlass = staticCompositionLocalOf { Glass.Off }

@Composable
fun rememberBatterySaver(): Boolean {
    val context = LocalContext.current
    var saver by remember { mutableStateOf(isPowerSave(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { saver = isPowerSave(c) }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return saver
}

private fun isPowerSave(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

@Composable
fun rememberGlass(settingOn: Boolean): Glass {
    val saver = rememberBatterySaver()
    val active = GlassSpec.active(Build.VERSION.SDK_INT, saver, settingOn)
    val state = remember { HazeState() }
    return remember(active) { if (active) Glass(true, state) else Glass.Off }
}

/** The content that floats surfaces blur: marks it as the backdrop. Do not put the glass surface itself inside. */
@Composable
fun Modifier.glassSource(): Modifier {
    val g = LocalGlass.current
    return if (g.active && g.haze != null) this.haze(g.haze) else this
}

/**
 * A frosted surface: the backdrop blurred at most 24 dp, a tint (dark #121212 / light white) opaque enough for every
 * text color to pass the ContrastTest over a white or a black backdrop, a 1 dp edge and a faint highlight on top.
 * With glass off it is the plain [fallback] fill, as before.
 */
@Composable
fun Modifier.glassSurface(shape: Shape, fallback: Color, topEdgeOnly: Boolean = false): Modifier {
    val g = LocalGlass.current
    val dark = isSystemInDarkTheme()
    if (!g.active || g.haze == null) return this.clip(shape).background(fallback)
    val tint = Color(GlassSpec.tint(dark)).copy(alpha = GlassSpec.tintAlpha(dark))
    val style = HazeStyle(
        backgroundColor = Color(GlassSpec.tint(dark)),
        tint = HazeTint(tint),
        blurRadius = GlassSpec.BLUR_DP.dp,
        noiseFactor = 0f,
    )
    return this.clip(shape).hazeChild(g.haze, style).glassEdge(shape, dark, topEdgeOnly)
}



/** The 1 dp edge and the faint top highlight, drawn over a glass fill. */
@Composable
fun Modifier.glassEdge(shape: Shape, dark: Boolean = isSystemInDarkTheme(), topEdgeOnly: Boolean = false): Modifier {
    val ink = if (dark) Color.White else Color.Black
    val edge = ink.copy(alpha = GlassSpec.BORDER_ALPHA)
    return this
        .drawWithCache {
            val brush = Brush.verticalGradient(
                0f to ink.copy(alpha = GlassSpec.HIGHLIGHT_ALPHA), 1f to Color.Transparent,
                endY = 40.dp.toPx(),
            )
            onDrawWithContent {
                drawContent()
                drawRect(brush, size = androidx.compose.ui.geometry.Size(size.width, minOf(size.height, 40.dp.toPx())))
                if (topEdgeOnly) drawRect(edge, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
            }
        }
        .then(if (topEdgeOnly) Modifier else Modifier.border(1.dp, edge, shape))
}

/**
 * Every bottom sheet goes through here. With glass on the sheet is a translucent tint with the window behind it
 * blurred by the system (a sheet lives in its own window, so the content behind it is blurred by the window, not by
 * Haze), a 12 dp top corner like the cards, the edge and the highlight; with glass off it is the stock sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    properties: ModalBottomSheetProperties = androidx.compose.material3.ModalBottomSheetDefaults.properties,
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = LocalGlass.current
    if (!g.active) {
        ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, containerColor = containerColor, properties = properties, content = content)
        return
    }
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(topStart = ShapeTokens.cardCorner, topEnd = ShapeTokens.cardCorner)
    val tint = Color(GlassSpec.tint(dark)).copy(alpha = GlassSpec.tintAlpha(dark))
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = shape,
        containerColor = Color.Transparent,
        dragHandle = null,
        properties = properties,
    ) {
        BlurBehindWindow()
        Column(Modifier.fillMaxWidth().clip(shape).background(tint).glassEdge(shape, dark)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }
            content()
        }
    }
}

/** Blurs whatever is behind this dialog window (Android 12+); removed with the window, so it costs nothing once the sheet is gone. */
@Composable
private fun BlurBehindWindow() {
    val view = LocalView.current
    val dp = LocalContext.current.resources.displayMetrics.density
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            val attrs = window.attributes
            attrs.blurBehindRadius = (GlassSpec.BLUR_DP * dp).toInt()
            window.attributes = attrs
        }
        onDispose {
            if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }
}
