package com.cashflow.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY date, id")
    fun observeAll(): Flow<List<TransactionEntity>>

    /** IGNORE makes inserts idempotent against the unique (rule, occurrence) index. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<TransactionEntity>): List<Long>

    @Update
    suspend fun updateAll(rows: List<TransactionEntity>)

    @Query("SELECT occurrenceDate FROM transactions WHERE recurringRuleId = :ruleId AND occurrenceDate IS NOT NULL")
    suspend fun occurrenceDates(ruleId: Long): List<LocalDate>

    @Query("SELECT * FROM transactions WHERE source = 'DAILY_INCOME'")
    suspend fun dailyIncomeRows(): List<TransactionEntity>

    /** Not-yet-answered daily income rows follow the amount set in Settings. */
    @Query("UPDATE transactions SET amount = :amount WHERE source = 'DAILY_INCOME' AND status = 'PENDING' AND autoApplied = 0")
    suspend fun updateOpenDailyIncomeAmount(amount: Long)

    @Query("DELETE FROM transactions WHERE source = 'DAILY_INCOME' AND status = 'PENDING' AND autoApplied = 0")
    suspend fun deleteOpenDailyIncome()

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

@Dao
interface RecurringRuleDao {
    @Query("SELECT * FROM recurring_rules ORDER BY id")
    suspend fun getAll(): List<RecurringRuleEntity>

    @Insert
    suspend fun insert(rule: RecurringRuleEntity): Long

    @Query("DELETE FROM recurring_rules")
    suspend fun deleteAll()
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun get(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: SettingsEntity)

    @Query("DELETE FROM settings")
    suspend fun deleteAll()
}
