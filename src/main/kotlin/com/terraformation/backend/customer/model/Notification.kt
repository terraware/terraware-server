package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.NOTIFICATIONS
import java.net.URI
import java.time.Instant
import org.jooq.Record

data class NotificationCountModel(val organizationId: OrganizationId?, val unread: Int)

data class CreateNotificationModel(
    val notificationType: NotificationType,
    val userId: UserId,
    val organizationId: OrganizationId?,
    val title: String,
    val body: String,
    val localUrl: URI,
)

data class NotificationModel(
    val id: NotificationId,
    val notificationType: NotificationType,
    val organizationId: OrganizationId?,
    val title: String,
    val body: String,
    val localUrl: URI,
    val createdTime: Instant,
    val isRead: Boolean,
) {
  constructor(
      record: Record
  ) : this(
      id = record[NOTIFICATIONS.ID] ?: throw IllegalArgumentException("Id is required"),
      notificationType =
          record[NOTIFICATIONS.NOTIFICATION_TYPE_ID]
              ?: throw IllegalArgumentException("Notification type is required"),
      organizationId = record[NOTIFICATIONS.ORGANIZATION_ID],
      title = record[NOTIFICATIONS.TITLE] ?: throw IllegalArgumentException("Title is required"),
      body = record[NOTIFICATIONS.BODY] ?: throw IllegalArgumentException("Body is required"),
      localUrl =
          record[NOTIFICATIONS.LOCAL_URL]
              ?: throw IllegalArgumentException("Local URL is required"),
      createdTime =
          record[NOTIFICATIONS.CREATED_TIME]
              ?: throw IllegalArgumentException("Created time is required"),
      isRead =
          record[NOTIFICATIONS.IS_READ]
              ?: throw IllegalArgumentException("Notification read is required"),
  )
}
