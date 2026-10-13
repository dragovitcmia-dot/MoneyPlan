package com.cashflow.app.engine

import java.time.LocalDate

enum class PurchaseStep { PRICE, NAME, RESULT }

enum class PurchaseProblem { PRICE_MISSING, NAME_MISSING }

/** The three screens of "Plan a purchase": price on the numpad, name, result. */
data class PurchaseState(
    val priceText: String = "",
    val name: String = "",
    val step: PurchaseStep = PurchaseStep.PRICE,
    val attempted: Boolean = false,
    /** The chip the user picked (1 = Pay all); null = the default option. */
    val selectedParts: Int? = null,
)

object PurchaseWizard {
    const val STEPS = 3

    fun price(state: PurchaseState): Long = AmountInput.toMillimes(state.priceText)

    fun problems(state: PurchaseState, step: PurchaseStep = state.step): List<PurchaseProblem> = when (step) {
        PurchaseStep.PRICE -> if (price(state) <= 0) listOf(PurchaseProblem.PRICE_MISSING) else emptyList()
        PurchaseStep.NAME -> if (state.name.isBlank()) listOf(PurchaseProblem.NAME_MISSING) else emptyList()
        PurchaseStep.RESULT -> problems(state, PurchaseStep.PRICE) + problems(state, PurchaseStep.NAME)
    }

    fun canAdvance(state: PurchaseState) = problems(state).isEmpty()

    fun next(state: PurchaseState): PurchaseState {
        if (!canAdvance(state)) return state.copy(attempted = true)
        val target = when (state.step) {
            PurchaseStep.PRICE -> PurchaseStep.NAME
            PurchaseStep.NAME -> PurchaseStep.RESULT
            PurchaseStep.RESULT -> return state
        }
        return state.copy(step = target, attempted = false)
    }

    /** The previous screen; null on the first one (the wizard asks before closing). Nothing typed is lost. */
    fun back(state: PurchaseState): PurchaseState? = when (state.step) {
        PurchaseStep.PRICE -> null
        PurchaseStep.NAME -> state.copy(step = PurchaseStep.PRICE, attempted = false)
        PurchaseStep.RESULT -> state.copy(step = PurchaseStep.NAME, attempted = false, selectedParts = null)
    }

    /** A new price changes the options, so a chip picked before no longer applies. */
    fun editPrice(state: PurchaseState, text: String) = state.copy(priceText = text, selectedParts = null)

    fun editName(state: PurchaseState, name: String) = state.copy(name = name)

    fun select(state: PurchaseState, parts: Int) = state.copy(selectedParts = parts)

    fun progress(state: PurchaseState): Pair<Int, Int> = (state.step.ordinal + 1) to STEPS

    /** Anything typed, or a screen past the first, would be lost by closing. */
    fun hasProgress(state: PurchaseState) = state.priceText.isNotEmpty() || state.name.isNotBlank() || state.step != PurchaseStep.PRICE

    /** Closes at once only when nothing was entered; otherwise the "Discard?" question is asked. */
    fun onCloseRequested(state: PurchaseState): CloseDecision = if (hasProgress(state)) CloseDecision.ASK else CloseDecision.CLOSE

    /** The ordinary one-time planned expenses "Plan it" creates: "Name", or "Name 1/3", "Name 2/3" ... */
    fun entriesFor(name: String, option: PurchaseOption): List<NewEntry.Planned> {
        val clean = name.trim()
        return option.outflows.mapIndexed { i, o ->
            NewEntry.Planned(
                kind = TxKind.EXPENSE,
                amount = o.amount,
                description = if (option.parts == 1) clean else "$clean ${i + 1}/${option.parts}",
                date = option.dates[i],
            )
        }
    }

    /** "Add as a normal expense": the normal wizard with the price and name filled in. */
    fun normalExpenseForm(state: PurchaseState, today: LocalDate): EntryFormState =
        EntryFormState.initial(TxKind.EXPENSE, today).copy(amountText = state.priceText, description = state.name.trim())
}
