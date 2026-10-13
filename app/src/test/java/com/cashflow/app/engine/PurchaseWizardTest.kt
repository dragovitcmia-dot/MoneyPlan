package com.cashflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseWizardTest {
    private val today = d(2026, 10, 12)

    private fun typed(price: String = "", name: String = "") =
        PurchaseWizard.editName(PurchaseWizard.editPrice(PurchaseState(), price), name)

    @Test fun a_fresh_wizard_starts_on_the_price_with_nothing_entered() {
        val s = PurchaseState()
        assertEquals(PurchaseStep.PRICE, s.step)
        assertFalse(PurchaseWizard.hasProgress(s))
        assertEquals(1 to 3, PurchaseWizard.progress(s))
    }

    @Test fun next_needs_a_price_and_marks_the_attempt() {
        val s = PurchaseWizard.next(PurchaseState())
        assertEquals(PurchaseStep.PRICE, s.step)
        assertTrue(s.attempted)
        assertEquals(listOf(PurchaseProblem.PRICE_MISSING), PurchaseWizard.problems(s))
    }

    @Test fun a_zero_price_is_missing_too() {
        assertFalse(PurchaseWizard.canAdvance(typed("0")))
        assertFalse(PurchaseWizard.canAdvance(typed("0.")))
        assertTrue(PurchaseWizard.canAdvance(typed("0.5")))
    }

    @Test fun the_name_is_required_before_the_result() {
        val onName = PurchaseWizard.next(typed("300"))
        assertEquals(PurchaseStep.NAME, onName.step)
        val stay = PurchaseWizard.next(onName)
        assertEquals(PurchaseStep.NAME, stay.step)
        assertEquals(listOf(PurchaseProblem.NAME_MISSING), PurchaseWizard.problems(stay))
        assertEquals(PurchaseStep.NAME, PurchaseWizard.next(PurchaseWizard.editName(onName, "  ")).step)
    }

    @Test fun the_path_is_price_name_result_and_the_result_is_the_end() {
        var s = typed("300", "Phone")
        s = PurchaseWizard.next(s)
        s = PurchaseWizard.next(s)
        assertEquals(PurchaseStep.RESULT, s.step)
        assertEquals(3 to 3, PurchaseWizard.progress(s))
        assertEquals(s, PurchaseWizard.next(s))
    }

    @Test fun back_goes_one_screen_keeps_what_was_typed_and_asks_at_the_first() {
        var s = typed("300", "Phone")
        s = PurchaseWizard.next(PurchaseWizard.next(s))
        s = PurchaseWizard.back(s)!!
        assertEquals(PurchaseStep.NAME, s.step)
        assertEquals("Phone", s.name)
        s = PurchaseWizard.back(s)!!
        assertEquals(PurchaseStep.PRICE, s.step)
        assertEquals("300", s.priceText)
        assertNull(PurchaseWizard.back(s))
    }

    @Test fun an_untouched_wizard_closes_at_once_and_one_with_progress_asks() {
        assertEquals(CloseDecision.CLOSE, PurchaseWizard.onCloseRequested(PurchaseState()))
        assertEquals(CloseDecision.ASK, PurchaseWizard.onCloseRequested(typed("1")))
        assertEquals(CloseDecision.ASK, PurchaseWizard.onCloseRequested(typed(name = "x")))
        assertEquals(CloseDecision.ASK, PurchaseWizard.onCloseRequested(PurchaseState(step = PurchaseStep.NAME)))
    }

    @Test fun picking_a_chip_sticks_until_the_price_changes() {
        var s = PurchaseWizard.select(typed("300", "Phone"), 3)
        assertEquals(3, s.selectedParts)
        s = PurchaseWizard.editName(s, "Phone X")
        assertEquals(3, s.selectedParts)
        s = PurchaseWizard.editPrice(s, "310")
        assertNull(s.selectedParts)
    }

    @Test fun going_back_from_the_result_forgets_the_chip() {
        val s = PurchaseWizard.select(PurchaseState(priceText = "300", name = "Phone", step = PurchaseStep.RESULT), 4)
        assertNull(PurchaseWizard.back(s)!!.selectedParts)
    }

    @Test fun pay_all_creates_one_planned_expense_with_the_plain_name() {
        val b = PlanBaseline(today, List(60) { tnd(500) })
        val option = PurchasePlanner.plan(b, tnd(300)).option(1)!!
        val entries = PurchaseWizard.entriesFor("  Phone ", option)
        assertEquals(1, entries.size)
        assertEquals(NewEntry.Planned(TxKind.EXPENSE, tnd(300), "Phone", today), entries[0])
    }

    @Test fun installments_create_numbered_expenses_that_add_up_to_the_price() {
        val b = PlanBaseline(today, List(60) { tnd(500) })
        val option = PurchasePlanner.plan(b, tnd(100)).option(3)!!
        val entries = PurchaseWizard.entriesFor("Phone", option)
        assertEquals(listOf("Phone 1/3", "Phone 2/3", "Phone 3/3"), entries.map { it.description })
        assertEquals(tnd(100), entries.sumOf { it.amount })
        assertEquals(listOf(today, today.plusDays(7), today.plusDays(14)), entries.map { it.date })
        assertTrue(entries.all { it.kind == TxKind.EXPENSE })
    }

    @Test fun the_planned_entries_become_ordinary_pending_items() {
        val b = PlanBaseline(today, List(60) { tnd(500) })
        val entry = PurchaseWizard.entriesFor("Phone", PurchasePlanner.plan(b, tnd(100)).option(2)!!)[1]
        val row = EntryPlanner.transactionFor(entry, today)!!
        assertEquals(TxStatus.PENDING, row.status)
        assertEquals(TxSource.MANUAL, row.source)
        assertEquals(today.plusDays(7), row.date)
    }

    @Test fun add_as_a_normal_expense_carries_the_price_and_the_name() {
        val form = PurchaseWizard.normalExpenseForm(typed("1250.5", " Sofa "), today)
        assertEquals("1250.5", form.amountText)
        assertEquals("Sofa", form.description)
        assertEquals(TxKind.EXPENSE, form.kind)
        val wizard = EntryWizard.start(TxKind.EXPENSE, today, amountText = "1250.5", description = "Sofa")
        assertEquals(form, wizard.form)
    }

    @Test fun the_normal_wizard_still_starts_empty_by_default() {
        val s = EntryWizard.start(TxKind.EXPENSE, today)
        assertEquals("", s.form.amountText)
        assertEquals("", s.form.description)
    }
}
