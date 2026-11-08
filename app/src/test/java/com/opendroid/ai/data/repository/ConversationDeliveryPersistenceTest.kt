package com.opendroid.ai.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.models.ChatDeliveryState
import com.opendroid.ai.data.models.ChatMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class ConversationDeliveryPersistenceTest {
    @Test
    fun `long outgoing message and delivery state survive reopening without duplicate bubbles`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "chat-delivery-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val firstDatabase = Room.databaseBuilder(context, OpenDroidDatabase::class.java, databaseName).build()
        val firstRepository = ConversationRepository(
            firstDatabase,
            firstDatabase.conversationDao(),
            firstDatabase.chatSessionDao(),
        )
        val sessionId = firstRepository.ensureCurrentSessionId()
        val longText = "short-prefix " + "long-message-content ".repeat(700)
        val queued = ChatMessage(
            id = "stable-user-message-id",
            text = longText,
            sender = ChatMessage.Sender.USER,
            imageBase64 = "ZmFrZS1pbWFnZS1ieXRlcw==",
            deliveryState = ChatDeliveryState.QUEUED,
            deliveryTarget = "CLOUD_OPENSWARM",
            deliveryTaskId = "chat-task-attempt-1",
            deliveryMetadata = "{\"plugins\":[\"openswarm_agent\"]}",
        )
        val shortConsecutive = ChatMessage(
            id = "short-consecutive-message-id",
            text = "second send",
            sender = ChatMessage.Sender.USER,
            deliveryState = ChatDeliveryState.QUEUED,
            deliveryTarget = "API_MODEL",
        )

        try {
            firstRepository.insertMessage(sessionId, queued)
            firstRepository.insertMessage(sessionId, shortConsecutive)
            firstRepository.updateMessageDelivery(queued.id, ChatDeliveryState.SENDING)
            firstRepository.updateMessageDelivery(queued.id, ChatDeliveryState.SENT, taskId = "chat-task-attempt-1")
            firstRepository.updateMessageDelivery(
                queued.id,
                ChatDeliveryState.FAILED,
                error = "stage=health_check http_status=503",
                retryable = true,
            )
            firstRepository.updateMessageDelivery(queued.id, ChatDeliveryState.SENDING)
            firstRepository.updateMessageDelivery(queued.id, ChatDeliveryState.SENT)
            firstRepository.updateMessageDelivery(
                queued.id,
                ChatDeliveryState.FAILED,
                error = "stage=health_check http_status=503",
                retryable = true,
            )
            // A repeated persistence callback with the same request id replaces the row,
            // rather than creating a second visible user bubble.
            firstRepository.insertMessage(
                sessionId,
                queued.copy(
                    deliveryState = ChatDeliveryState.FAILED,
                    deliveryError = "stage=health_check http_status=503",
                    deliveryRetryable = true,
                ),
            )
            firstDatabase.conversationDao().recoverInterruptedOutgoingMessages(
                "The app stopped before this queued request was dispatched."
            )
            val beforeRestart = firstRepository.getMessages(sessionId).first()
            assertEquals(1, beforeRestart.count { it.id == queued.id })
            assertEquals(2, beforeRestart.size)
            assertTrue(beforeRestart.first { it.id == queued.id }.text.length > 10_000)
            assertEquals(ChatDeliveryState.FAILED, beforeRestart.first { it.id == shortConsecutive.id }.deliveryState)
            assertEquals(true, beforeRestart.first { it.id == shortConsecutive.id }.deliveryRetryable)
        } finally {
            firstDatabase.close()
        }

        val reopenedDatabase = Room.databaseBuilder(context, OpenDroidDatabase::class.java, databaseName).build()
        try {
            val reopenedRepository = ConversationRepository(
                reopenedDatabase,
                reopenedDatabase.conversationDao(),
                reopenedDatabase.chatSessionDao(),
            )
            val restoredMessages = reopenedRepository.getMessages(sessionId).first()
            assertEquals(2, restoredMessages.size)
            val restored = restoredMessages.first { it.id == queued.id }
            assertEquals(longText, restored.text)
            assertEquals("ZmFrZS1pbWFnZS1ieXRlcw==", restored.imageBase64)
            assertEquals(ChatDeliveryState.FAILED, restored.deliveryState)
            assertEquals("CLOUD_OPENSWARM", restored.deliveryTarget)
            assertEquals("chat-task-attempt-1", restored.deliveryTaskId)
            assertTrue(restored.deliveryError.orEmpty().contains("http_status=503"))
            assertEquals(true, restored.deliveryRetryable)
            val recoveredQueued = restoredMessages.first { it.id == shortConsecutive.id }
            assertEquals(ChatDeliveryState.FAILED, recoveredQueued.deliveryState)
            assertEquals(true, recoveredQueued.deliveryRetryable)
        } finally {
            reopenedDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }
}
