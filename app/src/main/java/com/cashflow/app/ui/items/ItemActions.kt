package com.cashflow.app.ui.items

import android.content.res.Resources
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.cashflow.app.R
import com.cashflow.app.engine.AppDates
import com.cashflow.app.engine.ConfirmGate
import com.cashflow.app.engine.AcceptUndo
import com.cashflow.app.engine.DeleteOutcome
import com.cashflow.app.engine.ExtendChoice
import com.cashflow.app.engine.SeriesContext
import com.cashflow.app.engine.RecurringRule
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
        // "Accepted. Undo" for six seconds: the token knows the row before and after, so Undo restores exactly that and nothing else.
        val token = AcceptUndo.token(tx, amount = newAmount ?: tx.amount, nowMillis = com.cashflow.app.engine.AppClock.nowMillis())
        announcer.announce(resources.getString(R.string.confirmed_item, labelOf(tx))) { viewModel.undoAccept(token) }
    }

    /** The item whose rejection waits for "Are you sure?" (null: no dialog). */
    var rejectGate by mutableStateOf(ConfirmGate<Transaction>())
        private set

    /** Every swipe and button asks first: rejecting is permanent. */
    fun requestReject(tx: Transaction) {
        rejectGate = rejectGate.request(tx)
    }

    fun cancelReject() {
        rejectGate = rejectGate.cancel()
    }

    /** The user confirmed the dialog: now the item is really rejected. */
    fun confirmReject() {
        val (next, tx) = rejectGate.confirm()
        rejectGate = next
        if (tx != null) skip(tx)
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
        val token = AcceptUndo.token(tx, amount = amount, description = description.trim(), date = date, nowMillis = com.cashflow.app.engine.AppClock.nowMillis())
        announcer.announce(resources.getString(R.string.confirmed_item, labelOf(tx))) { viewModel.undoAccept(token) }
    }

    fun save(tx: Transaction, amount: Long, description: String, date: LocalDate) {
        haptics.confirm()
        viewModel.updateItem(tx.id, amount, description, date)
        announcer.announce(resources.getString(R.string.item_saved))
    }

    /** "This and all future" for a repeating item; Undo brings the series and every removed occurrence back. */
    fun deleteThisAndFuture(tx: Transaction) {
        haptics.reject()
        viewModel.deleteThisAndFuture(tx) { plan ->
            if (plan != null) {
                announcer.announce(resources.getString(R.string.item_deleted_future, labelOf(tx))) { viewModel.undoDeleteThisAndFuture(plan) }
            }
        }
    }

    /** Moves this one item to a later day (only this occurrence when it belongs to a series); Undo moves it back. */
    fun postpone(tx: Transaction, newDate: LocalDate) {
        haptics.tick()
        viewModel.postpone(tx.id, newDate) { moved ->
            if (moved) {
                announcer.announce(resources.getString(R.string.postponed_item, labelOf(tx), AppDates.format(newDate))) {
                    viewModel.moveTo(tx.id, tx.date)
                }
            }
        }
    }

    fun editSeries(rule: RecurringRule, amount: Long, description: String) {
        haptics.confirm()
        viewModel.editSeries(rule, amount, description)
        announcer.announce(resources.getString(R.string.series_changed))
    }

    fun endSeries(rule: RecurringRule, from: LocalDate) {
        haptics.confirm()
        viewModel.endSeriesFrom(rule.id, from) { plan ->
            announcer.announce(resources.getString(R.string.series_ended, AppDates.format(from.minusDays(1)))) {
                if (plan != null) viewModel.undoEndSeries(plan.first, plan.second)
            }
        }
    }

    fun deleteSeries(rule: RecurringRule) {
        haptics.reject()
        viewModel.deleteSeries(rule.id) { deleted ->
            announcer.announce(resources.getString(R.string.series_deleted)) { if (deleted != null) viewModel.undoDeleteSeries(deleted) }
        }
    }

    /** "Extend": the end moves later (never earlier) and the waiting occurrences up to it appear. */
    fun extendSeries(rule: RecurringRule, choice: ExtendChoice) {
        haptics.confirm()
        viewModel.extendSeries(rule.id, choice) { end ->
            announcer.announce(if (end == null) resources.getString(R.string.series_extend_nothing) else resources.getString(R.string.series_extended, end))
        }
    }

    fun saveSeries(rule: RecurringRule, askAmount: Boolean) {
        haptics.confirm()
        viewModel.saveSeries(rule, askAmount)
        announcer.announce(resources.getString(R.string.series_changed))
    }

    fun setAskAmount(rule: RecurringRule, on: Boolean) {
        haptics.tick()
        viewModel.setAskAmount(rule.id, on)
    }

    /** True for an item of a series that asks for the amount each time: accepting it opens the amount sheet first. */
    fun asksAmount(tx: Transaction, series: SeriesContext): Boolean = tx.recurringRuleId?.let { it in series.askAmountRuleIds } == true

    /** Manual items are removed (Undo puts them back); generated ones are skipped for this day only. */
    fun delete(tx: Transaction) {
        haptics.reject()
        viewModel.deleteItem(tx) { outcome ->
            when (outcome) {
                null -> announcer.announce(resources.getString(R.string.item_delete_refused))
                is DeleteOutcome.Remove ->
                    announcer.announce(resources.getString(R.string.item_deleted, labelOf(tx))) { viewModel.restoreItem(tx) }
                is DeleteOutcome.MarkSkipped ->
                    announcer.announce(resources.getString(R.string.item_skipped_this_day, labelOf(tx))) { viewModel.undo(tx.id) }
            }
        }
    }
}

/** The "Reject this?" dialog; place it once on every screen that offers swipe or button rejection. */
@Composable
fun RejectConfirmDialog(actions: ItemActions) {
    if (actions.rejectGate.pending == null) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = actions::cancelReject,
        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.reject_title)) },
        text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.reject_body)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = actions::confirmReject, modifier = androidx.compose.ui.Modifier.heightIn(min = 56.dp)) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.action_reject))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = actions::cancelReject, modifier = androidx.compose.ui.Modifier.heightIn(min = 56.dp)) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
fun rememberItemActions(viewModel: HomeViewModel, snackbar: SnackbarHostState): ItemActions {
    val haptics = rememberHaptics()
    val resources = LocalContext.current.resources
    val announcer = rememberAnnouncer(snackbar)
    return remember(viewModel, haptics, resources, announcer) { ItemActions(viewModel, haptics, resources, announcer) }
}
