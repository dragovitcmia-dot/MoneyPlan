package com.cashflow.app.ui.items

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.cashflow.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shows a message for 6 seconds, with an Undo action when one is given. One message at a time. */
@Stable
class Announcer(
    private val scope: CoroutineScope,
    private val host: SnackbarHostState,
    private val undoLabel: String,
) {
    fun announce(message: String, onUndo: (() -> Unit)? = null) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val timer = launch {
                delay(6_000)
                host.currentSnackbarData?.dismiss()
            }
            val result = host.showSnackbar(
                message = message,
                actionLabel = if (onUndo != null) undoLabel else null,
                duration = SnackbarDuration.Indefinite,
            )
            timer.cancel()
            if (result == SnackbarResult.ActionPerformed) onUndo?.invoke()
        }
    }
}

@Composable
fun rememberAnnouncer(host: SnackbarHostState): Announcer {
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.action_undo)
    return remember(host, scope, undoLabel) { Announcer(scope, host, undoLabel) }
}
