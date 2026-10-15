package com.cashflow.app.notify

import com.cashflow.app.engine.BellEntry
import com.cashflow.app.engine.NotificationCenterModel
import com.cashflow.app.engine.NotificationDecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class NotificationCenterRepository(private val db: NotificationDatabase) {
    private val dao = db.dao()

    val entries: Flow<List<BellEntry>> = dao.observeAll().map { rows -> rows.map { it.toBell() } }
    val unread: Flow<Int> = dao.observeUnread()

    /** Writes the bell entry of [decision] under [key]; a key that exists already changes nothing. */
    suspend fun record(decision: NotificationDecision, key: String, now: Instant) {
        dao.insert(
            NotificationEntity(
                key = key,
                type = decision.type.name,
                title = decision.title,
                body = decision.body,
                relatedDate = decision.relatedDate.toString(),
                amountMillimes = decision.amountMillimes,
                createdAt = now.toEpochMilli(),
                readAt = null,
            ),
        )
    }

    /** Every row, for deciding which notifications are resolved. */
    suspend fun all(): List<BellEntry> = dao.getAll().map { it.toBell() }

    /** Removes every bell row (the Lab build resets with it). */
    suspend fun clear() = dao.getAll().forEach { dao.delete(it.id) }

    suspend fun removeKey(key: String) = dao.deleteByKey(key)

    suspend fun updateText(key: String, title: String, body: String) = dao.updateText(key, title, body)

    suspend fun markRead(id: Long) = dao.markRead(id, com.cashflow.app.engine.AppClock.nowMillis())
    suspend fun markAllRead() = dao.markAllRead(com.cashflow.app.engine.AppClock.nowMillis())
    suspend fun remove(id: Long) = dao.delete(id)

    /** Keeps the last 200 entries or 90 days, whichever is smaller. */
    suspend fun prune(now: Instant) {
        val rows = dao.idsWithTime().map { it.id to Instant.ofEpochMilli(it.createdAt) }
        val drop = NotificationCenterModel.idsToPrune(rows, now)
        if (drop.isNotEmpty()) dao.deleteIds(drop)
    }
}
