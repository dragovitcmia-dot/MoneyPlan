package com.cashflow.app.notify

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.cashflow.app.engine.BellEntry
import com.cashflow.app.engine.NotificationType
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/** The bell's history. A separate database file, so the main data and its migrations are never touched. */
@Entity(tableName = "notifications", indices = [Index(value = ["key"], unique = true)])
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val type: String,
    val title: String,
    val body: String,
    /** ISO date or null. */
    val relatedDate: String?,
    val amountMillimes: Long?,
    /** Epoch millis. */
    val createdAt: Long,
    val readAt: Long?,
)

fun NotificationEntity.toBell() = BellEntry(
    id = id,
    type = runCatching { NotificationType.valueOf(type) }.getOrDefault(NotificationType.DAY_OF),
    title = title,
    body = body,
    relatedDate = relatedDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    amountMillimes = amountMillimes,
    createdAt = Instant.ofEpochMilli(createdAt),
    readAt = readAt?.let(Instant::ofEpochMilli),
    key = key,
)

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE readAt IS NULL")
    fun observeUnread(): Flow<Int>

    /** IGNORE: the unique key makes writing the same entry twice a no-op. Returns -1 when it already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: NotificationEntity): Long

    @Query("UPDATE notifications SET readAt = :now WHERE id = :id AND readAt IS NULL")
    suspend fun markRead(id: Long, now: Long)

    @Query("UPDATE notifications SET readAt = :now WHERE readAt IS NULL")
    suspend fun markAllRead(now: Long)

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM notifications WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("SELECT * FROM notifications")
    suspend fun getAll(): List<NotificationEntity>

    @Query("DELETE FROM notifications WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("UPDATE notifications SET title = :title, body = :body WHERE `key` = :key")
    suspend fun updateText(key: String, title: String, body: String)

    @Query("SELECT id, createdAt FROM notifications")
    suspend fun idsWithTime(): List<IdTime>
}

class IdTime(val id: Long, val createdAt: Long)

@Database(entities = [NotificationEntity::class], version = 1, exportSchema = true)
abstract class NotificationDatabase : RoomDatabase() {
    abstract fun dao(): NotificationDao

    companion object {
        fun create(context: Context): NotificationDatabase =
            Room.databaseBuilder(context.applicationContext, NotificationDatabase::class.java, "notifications.db").build()
    }
}
