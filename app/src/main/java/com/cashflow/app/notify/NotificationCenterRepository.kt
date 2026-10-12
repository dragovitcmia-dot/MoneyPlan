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

    suspend fun markRead(id: Long) = dao.markRead(id, System.currentTimeMillis())
    suspend fun markAllRead() = dao.markAllRead(System.currentTimeMillis())
    suspend fun remove(id: Long) = dao.delete(id)

    /** Keeps the last 200 entries or 90 days, whichever is smaller. */
    suspend fun prune(now: Instant) {
        val rows = dao.idsWithTime().map { it.id to Instant.ofEpochMilli(it.createdAt) }
        val drop = NotificationCenterModel.idsToPrune(rows, now)
        if (drop.isNotEmpty()) dao.deleteIds(drop)
    }
}
