package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmGateTest {
    private val item = tx(d(2026, 10, 12), 30, id = 1)

    @Test fun a_swipe_or_button_only_asks_nothing_is_rejected_yet() {
        val gate = ConfirmGate<Transaction>().request(item)
        assertEquals(item, gate.pending)
    }

    @Test fun confirming_hands_back_the_item_once_and_clears_the_gate() {
        val (cleared, confirmed) = ConfirmGate<Transaction>().request(item).confirm()
        assertEquals(item, confirmed)
        assertNull(cleared.pending)
        assertNull(cleared.confirm().second) // a second confirm rejects nothing
    }

    @Test fun cancelling_rejects_nothing() {
        val gate = ConfirmGate<Transaction>().request(item).cancel()
        assertNull(gate.pending)
        assertNull(gate.confirm().second)
    }

    @Test fun confirming_without_a_request_rejects_nothing() {
        assertNull(ConfirmGate<Transaction>().confirm().second)
    }

    @Test fun a_new_request_replaces_the_previous_one() {
        val other = tx(d(2026, 10, 13), 5, id = 2)
        val gate = ConfirmGate<Transaction>().request(item).request(other)
        assertEquals(other, gate.confirm().second)
    }
}
