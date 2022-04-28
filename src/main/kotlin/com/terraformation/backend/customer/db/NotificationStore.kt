package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.CreateNotificationModel
import com.terraformation.backend.customer.model.NotificationCountModel
import com.terraformation.backend.customer.model.NotificationModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.NotificationId
import com.terraformation.backend.db.NotificationNotFoundException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.daos.NotificationsDao
import com.terraformation.backend.db.tables.references.NOTIFICATIONS
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

@ManagedBean
class NotificationStore(
    private val dslContext: DSLContext,
    private val notificationsDao: NotificationsDao,
    private val clock: Clock,
) {

  fun isOwner(userId: UserId, notificationId: NotificationId): Boolean =
      notificationsDao.fetchOneById(notificationId)?.userId?.equals(userId)
          ?: throw NotificationNotFoundException(notificationId)

  fun fetchById(notificationId: NotificationId): NotificationModel {
    requirePermissions { readNotification(notificationId) }
    return dslContext
        .select(NOTIFICATIONS.asterisk())
        .from(NOTIFICATIONS)
        .where(NOTIFICATIONS.ID.eq(notificationId).and(isCurrentUserOwnerClause()))
        .fetch { row -> NotificationModel(row) }
        .firstOrNull()
        ?: throw NotificationNotFoundException(notificationId)
  }

  fun fetchByOrganization(organizationId: OrganizationId?): List<NotificationModel> {
    requirePermissions { listNotifications(organizationId) }
    return dslContext
        .select(NOTIFICATIONS.asterisk())
        .from(NOTIFICATIONS)
        .where(isOrganizationIdClause(organizationId).and(isCurrentUserOwnerClause()))
        .fetch { row -> NotificationModel(row) }
  }

  fun count(): List<NotificationCountModel> {
    requirePermissions { countNotifications() }
    return dslContext
        .select(NOTIFICATIONS.ORGANIZATION_ID, DSL.count(NOTIFICATIONS.ID))
        .from(NOTIFICATIONS)
        .where(NOTIFICATIONS.IS_READ.isFalse.and(isCurrentUserOwnerClause()))
        .groupBy(NOTIFICATIONS.ORGANIZATION_ID)
        .fetch { row -> NotificationCountModel(row.value1(), row.value2()) }
  }

  fun markRead(notificationId: NotificationId, read: Boolean): Unit {
    requirePermissions { updateNotification(notificationId) }
    dslContext
        .update(NOTIFICATIONS)
        .set(NOTIFICATIONS.IS_READ, read)
        .where(NOTIFICATIONS.ID.eq(notificationId).and(isCurrentUserOwnerClause()))
        .execute()
  }

  fun markAllRead(read: Boolean, organizationId: OrganizationId?): Unit {
    requirePermissions { updateNotifications(organizationId) }
    dslContext
        .update(NOTIFICATIONS)
        .set(NOTIFICATIONS.IS_READ, read)
        .where(isOrganizationIdClause(organizationId).and(isCurrentUserOwnerClause()))
        .execute()
  }

  fun create(notification: CreateNotificationModel) {
    requirePermissions { createNotification(notification.userId, notification.organizationId) }
    with(NOTIFICATIONS) {
      with(notification) {
        dslContext
            .insertInto(NOTIFICATIONS)
            .set(NOTIFICATION_TYPE_ID, notificationType)
            .set(USER_ID, userId)
            .set(ORGANIZATION_ID, if (isGlobalNotification) organizationId else null)
            .set(TITLE, title)
            .set(BODY, body)
            .set(LOCAL_URL, localUrl)
            .set(CREATED_TIME, clock.instant())
            .execute()
      }
    }
  }

  private fun isOrganizationIdClause(organizationId: OrganizationId?) =
      if (organizationId == null) NOTIFICATIONS.ORGANIZATION_ID.isNull
      else NOTIFICATIONS.ORGANIZATION_ID.eq(organizationId)

  private fun isCurrentUserOwnerClause() = NOTIFICATIONS.USER_ID.eq(currentUser().userId)
}
