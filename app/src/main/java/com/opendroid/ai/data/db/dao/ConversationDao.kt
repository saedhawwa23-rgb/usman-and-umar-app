package com.opendroid.ai.data.db.dao

import androidx.room.*
import com.opendroid.ai.data.db.entities.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY timestamp ASC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLastMessages(limit: Int): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ConversationEntity)

    @Query("UPDATE conversations SET text = :text WHERE id = :messageId")
    suspend fun updateMessageText(messageId: String, text: String)

    @Query("UPDATE conversations SET imageBase64 = :imageBase64 WHERE id = :messageId")
    suspend fun updateMessageImage(messageId: String, imageBase64: String)

    @Query("UPDATE conversations SET deliveryState = :state, deliveryError = :error, deliveryRetryable = :retryable, deliveryTaskId = COALESCE(:taskId, deliveryTaskId) WHERE id = :messageId")
    suspend fun updateMessageDelivery(
        messageId: String,
        state: String,
        error: String?,
        retryable: Boolean?,
        taskId: String?,
    )

    @Query("UPDATE conversations SET deliveryRetryable = CASE WHEN deliveryState = 'QUEUED' THEN 1 ELSE 0 END, deliveryState = 'FAILED', deliveryError = :error WHERE sender = 'USER' AND deliveryState IN ('QUEUED', 'SENDING', 'SENT')")
    suspend fun recoverInterruptedOutgoingMessages(error: String)

    @Query("DELETE FROM conversations")
    suspend fun clearAll()

    /** Messages of a single chat session, oldest first, reactive to inserts/deletes. */
    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<ConversationEntity>>

    /** Newest-first, capped at [limit]; callers wanting chronological order should reverse it. */
    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLastMessagesForSession(sessionId: String, limit: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId AND timestamp <= :timestamp ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLastMessagesForSessionUpTo(sessionId: String, timestamp: Long, limit: Int): List<ConversationEntity>

    /** Deletes a single message by id, regardless of which session it belongs to. */
    @Query("DELETE FROM conversations WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    /**
     * Deletes [messageId] together with every message inserted after it in
     * [sessionId] (by insertion order, i.e. SQLite rowid). This is the
     * "edit and resend" primitive: drop the edited message and every reply
     * that followed it so the edited text can be resubmitted as a new turn.
     */
    @Query(
        """
        DELETE FROM conversations
        WHERE sessionId = :sessionId
        AND rowid >= (
            SELECT rowid FROM conversations WHERE id = :messageId AND sessionId = :sessionId
        )
        """
    )
    suspend fun deleteMessageAndAfter(sessionId: String, messageId: String)

    /** Clears every message of a single session, leaving other sessions untouched. */
    @Query("DELETE FROM conversations WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)
}
