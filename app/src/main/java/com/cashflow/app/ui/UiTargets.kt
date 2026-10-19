package com.cashflow.app.ui

import com.cashflow.app.engine.UiTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The one mailbox for "go there by yourself": whoever wants the app to show a [UiTarget] posts it here, the main activity takes it. */
object UiTargets {
    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending

    /** A request with a serial number, so the same target sent twice is still two requests. */
    class Pending(val serial: Long, val target: UiTarget)

    private var serial = 0L

    fun request(target: UiTarget) {
        _pending.value = Pending(++serial, target)
    }

    fun consume(request: Pending) {
        if (_pending.value === request) _pending.value = null
    }
}
