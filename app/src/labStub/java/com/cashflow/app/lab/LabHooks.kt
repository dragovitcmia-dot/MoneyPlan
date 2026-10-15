package com.cashflow.app.lab

import androidx.compose.runtime.Composable
import com.cashflow.app.data.LedgerRepository

/**
 * No-op stand-ins for the Lab build's hooks. Every variant except "lab" compiles these (source set src/labStub), so the real app
 * contains no lab code, screen or data at all; the Lab build has the real object in src/lab/java.
 */
object LabHooks {
    @Suppress("UNUSED_PARAMETER")
    fun onAppCreate(app: android.app.Application) = Unit

    fun systemNotificationsAllowed(): Boolean = true

    @Composable
    fun Tag() = Unit

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun Card(repository: LedgerRepository, onDataChanged: () -> Unit) = Unit
}
