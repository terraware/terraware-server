package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.CreateNotificationModel
import com.terraformation.backend.customer.model.NotificationCountModel
import com.terraformation.backend.customer.model.NotificationModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.NotificationId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.tables.references.NOTIFICATIONS
import java.time.Clock
import java.time.Instant
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

@ManagedBean
class NotificationStore(private val dslContext: DSLContext, private val clock: Clock) {

  fun fetchById(notificationId: NotificationId): NotificationModel? {
    requirePermissions { canReadNotification() }
    return dslContext
        .select(NOTIFICATIONS.asterisk())
        .from(NOTIFICATIONS)
        .where(
            NOTIFICATIONS.ID.eq(notificationId).and(NOTIFICATIONS.USER_ID.eq(currentUser().userId)),
        )
        .fetch { row -> NotificationModel(row) }
        .firstOrNull()
  }

  fun fetchByOrganization(organizationId: OrganizationId?): List<NotificationModel> {
    requirePermissions { canReadNotification() }
    return dslContext
        .select(
            NOTIFICATIONS.asterisk(),
        )
        .from(NOTIFICATIONS)
        .where(
            NOTIFICATIONS
                .ORGANIZATION_ID
                .eq(organizationId)
                .and(
                    NOTIFICATIONS.USER_ID.eq(currentUser().userId),
                ),
        )
        .fetch { row -> NotificationModel(row) }
  }

  fun count(): List<NotificationCountModel> {
    requirePermissions { canReadNotification() }
    return dslContext
        .select(
            NOTIFICATIONS.ORGANIZATION_ID,
            DSL.count(NOTIFICATIONS.ID),
        )
        .from(NOTIFICATIONS)
        .where(
            NOTIFICATIONS.READ_TIME.isNull,
        )
        .groupBy(NOTIFICATIONS.ORGANIZATION_ID)
        .fetch { row -> NotificationCountModel(row.value1(), row.value2()) }
  }

  fun markRead(
      notificationId: NotificationId,
      readTime: Instant?,
      organizationId: OrganizationId?
  ): Boolean {
    requirePermissions { canUpdateNotification() }
    if (fetchById(notificationId) != null) {
      dslContext
          .update(NOTIFICATIONS)
          .set(NOTIFICATIONS.READ_TIME, readTime)
          .where(
              NOTIFICATIONS
                  .ID
                  .eq(notificationId)
                  .and(NOTIFICATIONS.USER_ID.eq(currentUser().userId))
                  .and(NOTIFICATIONS.ORGANIZATION_ID.eq(organizationId)))
          .execute()
      return true
    } else {
      return false
    }
  }

  fun markAllRead(readTime: Instant?, organizationId: OrganizationId?): Unit {
    requirePermissions { canUpdateNotification() }
    dslContext
        .update(NOTIFICATIONS)
        .set(NOTIFICATIONS.READ_TIME, readTime)
        .where(
            NOTIFICATIONS
                .USER_ID
                .eq(currentUser().userId)
                .and(NOTIFICATIONS.ORGANIZATION_ID.eq(organizationId)))
        .execute()
  }

  fun create(notification: CreateNotificationModel) {
    requirePermissions { canCreateNotification() }
    with(NOTIFICATIONS) {
      with(notification) {
        dslContext
            .insertInto(
                NOTIFICATIONS,
                NOTIFICATION_TYPE_ID,
                USER_ID,
                ORGANIZATION_ID,
                TITLE,
                BODY,
                LOCAL_URL,
                CREATED_TIME,
            )
            .values(
                notificationType,
                userId,
                organizationId,
                title,
                body,
                localUrl,
                clock.instant(),
            )
            .execute()
      }
    }
  }
}
