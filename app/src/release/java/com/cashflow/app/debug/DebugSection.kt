package com.cashflow.app.debug

import androidx.compose.runtime.Composable
import com.cashflow.app.data.LedgerRepository

/** Release builds have no debug actions. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun DebugSection(repository: LedgerRepository, onDataChanged: () -> Unit) = Unit
