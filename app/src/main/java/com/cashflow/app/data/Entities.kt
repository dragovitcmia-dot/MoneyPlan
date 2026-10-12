package com.cashflow.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cashflow.app.engine.AppSettings
import com.cashflow.app.engine.RecurrencePattern
import com.cashflow.app.engine.RecurringRule
import com.cashflow.app.engine.Transaction
import com.cashflow.app.engine.TxKind
import com.cashflow.app.engine.TxSource
import com.cashflow.app.engine.TxStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = RecurringRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["recurringRuleId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("date"),
        // One row per (rule, occurrence). NULL rule ids (manual rows) never collide in SQLite.
        Index("recurringRuleId", "occurrenceDate", unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val amount: Long,
    val kind: TxKind,
    val description: String,
    val status: TxStatus,
    val source: TxSource,
    val recurringRuleId: Long?,
    val occurrenceDate: LocalDate?,
    val isPlanned: Boolean,
)

enum class PatternType { DAILY, WEEKLY, EVERY_N_DAYS, MONTH_FIRST, MONTH_LAST, DAY_OF_MONTH }

@Entity(tableName = "recurring_rules")
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Long,
    val kind: TxKind,
    val description: String,
    val patternType: PatternType,
    /** WEEKLY: ISO day of week 1..7; EVERY_N_DAYS: n; DAY_OF_MONTH: day 1..31; else null. */
    val patternValue: Int?,
    /** EVERY_N_DAYS only. */
    val anchorDate: LocalDate?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val enabled: Boolean,
    val confirmMinutes: Int,
)

/**
 * The old Dynamic/Static wallet mode. The app no longer has the feature; the column stays in the table (no
 * schema change) and always holds DYNAMIC.
 */
enum class LegacyWalletMode { DYNAMIC, STATIC }

/** Single-row table (id is always 1). Times are minutes after midnight. */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val startingBalance: Long,
    val balanceStartDate: LocalDate,
    val walletMode: LegacyWalletMode,
    val reminderMinutes: Int,
)

fun TransactionEntity.toDomain() = Transaction(
    id = id, date = date, amount = amount, kind = kind, description = description,
    status = status, source = source, recurringRuleId = recurringRuleId,
    occurrenceDate = occurrenceDate, isPlanned = isPlanned,
)

fun Transaction.toEntity() = TransactionEntity(
    id = id, date = date, amount = amount, kind = kind, description = description,
    status = status, source = source, recurringRuleId = recurringRuleId,
    occurrenceDate = occurrenceDate, isPlanned = isPlanned,
)

fun RecurringRuleEntity.toDomain(): RecurringRule {
    val pattern = when (patternType) {
        PatternType.DAILY -> RecurrencePattern.Daily
        PatternType.WEEKLY -> RecurrencePattern.Weekly(DayOfWeek.of(patternValue!!))
        PatternType.EVERY_N_DAYS -> RecurrencePattern.EveryNDays(patternValue!!, anchorDate!!)
        PatternType.MONTH_FIRST -> RecurrencePattern.MonthFirst
        PatternType.MONTH_LAST -> RecurrencePattern.MonthLast
        PatternType.DAY_OF_MONTH -> RecurrencePattern.DayOfMonth(patternValue!!)
    }
    return RecurringRule(
        id, amount, kind, description, pattern, startDate, endDate, enabled,
        LocalTime.ofSecondOfDay(confirmMinutes * 60L),
    )
}

fun RecurringRule.toEntity(): RecurringRuleEntity {
    val type: PatternType
    var value: Int? = null
    var anchor: LocalDate? = null
    when (val p = pattern) {
        RecurrencePattern.Daily -> type = PatternType.DAILY
        is RecurrencePattern.Weekly -> { type = PatternType.WEEKLY; value = p.dayOfWeek.value }
        is RecurrencePattern.EveryNDays -> { type = PatternType.EVERY_N_DAYS; value = p.n; anchor = p.anchorDate }
        RecurrencePattern.MonthFirst -> type = PatternType.MONTH_FIRST
        RecurrencePattern.MonthLast -> type = PatternType.MONTH_LAST
        is RecurrencePattern.DayOfMonth -> { type = PatternType.DAY_OF_MONTH; value = p.day }
    }
    return RecurringRuleEntity(
        id, amount, kind, description, type, value, anchor, startDate, endDate, enabled,
        confirmTime.hour * 60 + confirmTime.minute,
    )
}

/** [autoAcceptIncome] lives in the app's preferences (no schema change), so the caller supplies it. */
fun SettingsEntity.toDomain(autoAcceptIncome: Boolean) = AppSettings(
    startingBalance = startingBalance,
    balanceStartDate = balanceStartDate,
    autoAcceptIncome = autoAcceptIncome,
    reminderTime = LocalTime.ofSecondOfDay(reminderMinutes * 60L),
)

fun AppSettings.toEntity() = SettingsEntity(
    startingBalance = startingBalance,
    balanceStartDate = balanceStartDate,
    walletMode = LegacyWalletMode.DYNAMIC, // legacy column, unused
    reminderMinutes = reminderTime.hour * 60 + reminderTime.minute,
)
