package com.cashflow.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

@Database(
    entities = [TransactionEntity::class, RecurringRuleEntity::class, SettingsEntity::class],
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun recurringRuleDao(): RecurringRuleDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        /** The schema version of the main database. The backup code compares it with what it saw before an update. */
        const val SCHEMA_VERSION = 2

        /** All migrations, oldest first. Version 2 is the baseline, so there are none yet. */
        val MIGRATIONS: Array<Migration> = arrayOf()

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "cashflow.db")
                // Schema version 2 is the baseline. Never destructive: every change adds a Migration.
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
