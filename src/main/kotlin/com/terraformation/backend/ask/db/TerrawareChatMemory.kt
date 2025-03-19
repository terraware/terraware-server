package com.terraformation.backend.ask.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.ChatMemoryMessageType
import com.terraformation.backend.db.default_schema.tables.references.CHAT_MEMORY_CONVERSATIONS
import com.terraformation.backend.db.default_schema.tables.references.CHAT_MEMORY_MESSAGES
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.InstantSource
import java.util.UUID
import org.jooq.DSLContext
import org.springframework.ai.chat.memory.ChatMemory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage

/**
 * Stores the messages from chat conversations in the database. This is an implementation of the
 * Spring AI [ChatMemory] interface; the built-in implementations in Spring AI aren't suitable for
 * our environment.
 *
 * TODO: Prune old conversations. Currently the conversation history tables will grow without bound.
 */
@Named
class TerrawareChatMemory(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) : ChatMemory {
  private val log = perClassLogger()

  override fun add(conversationId: String, messages: List<Message>) {
    dslContext.transaction { _ ->
      with(CHAT_MEMORY_CONVERSATIONS) {
        dslContext
            .insertInto(CHAT_MEMORY_CONVERSATIONS)
            .set(ID, UUID.fromString(conversationId))
            .set(CREATED_BY, currentUser().userId)
            .set(CREATED_TIME, clock.instant())
            .set(MODIFIED_TIME, clock.instant())
            .onConflict(ID)
            .doUpdate()
            .set(MODIFIED_TIME, clock.instant())
            .execute()
      }

      messages.forEach { message ->
        val messageType =
            when (message.messageType) {
              MessageType.ASSISTANT -> ChatMemoryMessageType.Assistant
              MessageType.SYSTEM -> ChatMemoryMessageType.System
              MessageType.TOOL -> ChatMemoryMessageType.ToolResponse
              MessageType.USER -> ChatMemoryMessageType.User
              else -> null
            }

        if (messageType != null) {
          with(CHAT_MEMORY_MESSAGES) {
            dslContext
                .insertInto(CHAT_MEMORY_MESSAGES)
                .set(CONVERSATION_ID, UUID.fromString(conversationId))
                .set(CREATED_TIME, clock.instant())
                .set(MESSAGE_TYPE_ID, messageType)
                .set(CONTENT, message.text)
                .execute()
          }
        } else {
          log.error("Unknown message type ${message.messageType}")
        }
      }
    }
  }

  override fun get(conversationId: String, lastN: Int): List<Message> {
    return with(CHAT_MEMORY_MESSAGES) {
      dslContext
          .select(CONTENT, MESSAGE_TYPE_ID)
          .from(CHAT_MEMORY_MESSAGES)
          .where(CONVERSATION_ID.eq(UUID.fromString(conversationId)))
          .and(chatMemoryConversations.CREATED_BY.eq(currentUser().userId))
          .orderBy(ID.desc())
          .limit(lastN)
          .fetch { record ->
            val content = record[CONTENT]
            when (record[MESSAGE_TYPE_ID]!!) {
              ChatMemoryMessageType.Assistant -> AssistantMessage(content)
              ChatMemoryMessageType.System -> SystemMessage(content)
              // TODO: Figure out if we need to handle tool responses at all; they aren't simple
              //       text values
              ChatMemoryMessageType.ToolResponse -> null
              ChatMemoryMessageType.User -> UserMessage(content)
            }
          }
          .filterNotNull()
          .reversed()
    }
  }

  override fun clear(conversationId: String) {
    with(CHAT_MEMORY_CONVERSATIONS) {
      // Cascading delete will clean up the messages.
      dslContext
          .deleteFrom(CHAT_MEMORY_CONVERSATIONS)
          .where(ID.eq(UUID.fromString(conversationId)))
          .and(CREATED_BY.eq(currentUser().userId))
          .execute()
    }
  }
}
