package com.terraformation.backend.customer.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.db.NotificationId
import com.terraformation.backend.db.NotificationType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.references.NOTIFICATIONS
import java.net.URI
import java.time.Instant
import org.jooq.Record

data class NotificationCountModel(val organizationId: OrganizationId?, val unread: Int)

data class CreateNotificationModel(
    val notificationType: NotificationType,
    val userId: UserId,
    val organizationId: OrganizationId?,
    val localUrl: URI,
    val metadata: Map<String, Any?>
)

data class NotificationModel(
    val id: NotificationId,
    val notificationType: NotificationType,
    val userId: UserId,
    val organizationId: OrganizationId?,
    val localUrl: URI,
    val metadata: Map<String, Any?>,
    val createdTime: Instant,
    val readTime: Instant?,
) {
  constructor(
      row: Record,
      objectMapper: ObjectMapper
  ) : this(
      id = row[NOTIFICATIONS.ID] ?: throw IllegalArgumentException("Id is required"),
      notificationType = row[NOTIFICATIONS.NOTIFICATION_TYPE_ID]
              ?: throw IllegalArgumentException("Notification type is required"),
      userId = row[NOTIFICATIONS.USER_ID] ?: throw IllegalArgumentException("User Id is required"),
      organizationId = row[NOTIFICATIONS.ORGANIZATION_ID],
      localUrl = row[NOTIFICATIONS.LOCAL_URL]
              ?: throw IllegalArgumentException("Local URL is required"),
      metadata = row[NOTIFICATIONS.METADATA]?.data()?.let { objectMapper.readValue(it) }
              ?: throw IllegalArgumentException("Metadata is required"),
      createdTime = row[NOTIFICATIONS.CREATED_TIME]
              ?: throw IllegalArgumentException("Created time is required"),
      readTime = row[NOTIFICATIONS.READ_TIME])
}
