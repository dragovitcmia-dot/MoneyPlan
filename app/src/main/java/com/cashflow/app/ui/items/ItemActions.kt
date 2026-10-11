package com.cashflow.app.ui.items

import android.content.res.Resources
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.cashflow.app.R
import com.cashflow.app.engine.DeleteOutcome
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import com.cashflow.app.engine.TxStatus
import com.cashflow.app.ui.Haptics
import com.cashflow.app.ui.home.HomeViewModel
import com.cashflow.app.ui.rememberHaptics
import java.time.LocalDate

/**
 * What can be done with a single item, shared by Home, Calendar and Upcoming so they behave the same:
 * the same haptics, the same messages and the same Undo.
 */
@Stable
class ItemActions(
    private val viewModel: HomeViewModel,
    private val haptics: Haptics,
    private val resources: Resources,
    val announcer: Announcer,
) {
    fun labelOf(tx: Transaction): String = tx.description.ifBlank {
        resources.getString(if (tx.kind == TxKind.INCOME) R.string.item_income else R.string.item_expense)
    }

    fun announce(message: String, onUndo: (() -> Unit)? = null) = announcer.announce(message, onUndo)

    fun confirm(tx: Transaction, newAmount: Long? = null) {
        haptics.confirm()
        viewModel.confirm(tx.id, newAmount)
        announcer.announce(resources.getString(R.string.confirmed_item, labelOf(tx))) {
            viewModel.undo(tx.id, if (newAmount != null) tx.amount else null)
        }
    }

    fun skip(tx: Transaction) {
        haptics.reject()
        viewModel.skip(tx.id)
        announcer.announce(resources.getString(R.string.skipped_item, labelOf(tx))) { viewModel.undo(tx.id) }
    }

    /** Back to "waiting" from Done or Skipped; Undo puts the previous answer back. */
    fun markWaiting(tx: Transaction) {
        haptics.tick()
        viewModel.undo(tx.id)
        announcer.announce(resources.getString(R.string.marked_waiting, labelOf(tx))) {
            if (tx.status == TxStatus.CONFIRMED) viewModel.confirm(tx.id) else viewModel.skip(tx.id)
        }
    }

    /** "Done" from the edit sheet: saves the edited fields and confirms in one step. */
    fun done(tx: Transaction, amount: Long, description: String, date: LocalDate) {
        haptics.confirm()
        viewModel.updateItem(tx.id, amount, description, date, TxStatus.CONFIRMED)
        announcer.announce(resources.getString(R.string.confirmed_item, labelOf(tx))) { viewModel.undo(tx.id) }
    }

    fun save(tx: Transaction, amount: Long, description: String, date: LocalDate) {
        haptics.confirm()
        viewModel.updateItem(tx.id, amount, description, date)
        announcer.announce(resources.getString(R.string.item_saved))
    }

    /** Manual items are removed (Undo puts them back); generated ones are skipped for this day only. */
    fun delete(tx: Transaction) {
        haptics.reject()
        viewModel.deleteItem(tx) { outcome ->
            when (outcome) {
                is DeleteOutcome.Remove ->
                    announcer.announce(resources.getString(R.string.item_deleted, labelOf(tx))) { viewModel.restoreItem(tx) }
                is DeleteOutcome.MarkSkipped ->
                    announcer.announce(resources.getString(R.string.item_skipped_this_day, labelOf(tx))) { viewModel.undo(tx.id) }
            }
        }
    }
}

@Composable
fun rememberItemActions(viewModel: HomeViewModel, snackbar: SnackbarHostState): ItemActions {
    val haptics = rememberHaptics()
    val resources = LocalContext.current.resources
    val announcer = rememberAnnouncer(snackbar)
    return remember(viewModel, haptics, resources, announcer) { ItemActions(viewModel, haptics, resources, announcer) }
}
