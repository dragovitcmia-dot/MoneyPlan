package com.cashflow.app.debug

import com.cashflow.app.data.LedgerRepository
import java.time.LocalDate

/** Replaces all data with the reference scenario (settings, one-off expenses, Sunday rule). */
suspend fun LedgerRepository.loadReferenceScenario(today: LocalDate) {
    clearAll(today)
    insertTransactions(ReferenceScenario.oneOffExpenses)
    insertRule(ReferenceScenario.sundayRule.copy(id = 0))
    updateSettings(ReferenceScenario.settings, today) // also materializes the rule and catches up
}
