package com.cashflow.app.debug

import com.cashflow.app.data.LedgerRepository
import java.time.LocalDate

/** Replaces all data with the reference scenario (settings, one-off expenses, Sunday rule, one daily income source). */
suspend fun LedgerRepository.loadReferenceScenario(today: LocalDate) {
    clearAll(today)
    insertTransactions(ReferenceScenario.oneOffExpenses)
    insertRule(ReferenceScenario.sundayRule.copy(id = 0))
    insertRule(ReferenceScenario.dailyIncomeSource.copy(id = 0))
    insertRule(ReferenceScenario.endingIncomeSource.copy(id = 0))
    updateSettings(ReferenceScenario.settings, today) // also materializes the rules
}
