package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.CreateNotificationModel
import com.terraformation.backend.customer.model.NotificationCountModel
import com.terraformation.backend.customer.model.NotificationModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.NotificationId
import com.terraformation.backend.db.NotificationNotFoundException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.tables.references.NOTIFICATIONS
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

@ManagedBean
class NotificationStore(
    private val dslContext: DSLContext,
    private val clock: Clock,
) {

  /**
   * Fetches a notification by unique id
   *
   * @param notificationId ID of notification for which to retrieve information
   * @return NotificationModel representation of the notification
   * @throws NotificationNotFoundException if notification for id was not retrievable
   */
  fun fetchById(notificationId: NotificationId): NotificationModel {
    requirePermissions { readNotification(notificationId) }
    return dslContext
        .select(NOTIFICATIONS.asterisk())
        .from(NOTIFICATIONS)
        .where(NOTIFICATIONS.ID.eq(notificationId))
        .and(NOTIFICATIONS.USER_ID.eq(currentUser().userId))
        .fetch { row -> NotificationModel(row) }
        .firstOrNull()
        ?: throw NotificationNotFoundException(notificationId)
  }

  /**
   * Fetch notifications for a user within a specific organization or globally. If the input
   * organizationId is null, assumes globally scoped notifications.
   *
   * @param organizationId The organization id for which to retrieve user's notifications for. If
   * null, retrieves globally scoped notifications
   * @return List<NotificationModel> list of notifications
   */
  fun fetchByOrganization(organizationId: OrganizationId?): List<NotificationModel> {
    requirePermissions { listNotifications(organizationId) }
    return dslContext
        .select(NOTIFICATIONS.asterisk())
        .from(NOTIFICATIONS)
        .where(isOrganizationIdClause(organizationId))
        .and(NOTIFICATIONS.USER_ID.eq(currentUser().userId))
        .fetch { row -> NotificationModel(row) }
  }

  /**
   * Retrieve unread count of notifications across all organizations and global scope, for user
   *
   * @return List<NotificationCountModel> list of organizations with unread count, if any
   */
  fun count(): List<NotificationCountModel> {
    requirePermissions { countNotifications() }
    return dslContext
        .select(NOTIFICATIONS.ORGANIZATION_ID, DSL.count(NOTIFICATIONS.ID))
        .from(NOTIFICATIONS)
        .where(NOTIFICATIONS.IS_READ.isFalse)
        .and(NOTIFICATIONS.USER_ID.eq(currentUser().userId))
        .groupBy(NOTIFICATIONS.ORGANIZATION_ID)
        .fetch { row -> NotificationCountModel(row.value1(), row.value2()) }
  }

  /**
   * Marks a notification as read or unread
   *
   * @param read Boolean status to be set for notification isRead property
   * @param notificationId id of notification for which to set the status
   */
  fun markRead(read: Boolean, notificationId: NotificationId) {
    requirePermissions { updateNotification(notificationId) }
    dslContext
        .update(NOTIFICATIONS)
        .set(NOTIFICATIONS.IS_READ, read)
        .where(NOTIFICATIONS.ID.eq(notificationId))
        .and(NOTIFICATIONS.USER_ID.eq(currentUser().userId))
        .execute()
  }

  /**
   * Marks all of user's notifications within an organization (or globally), as read or unread
   *
   * @param read Boolean status to be set for notification isRead property
   * @param organizationId id of organization within which to set the notifications' status. If id
   * is null, notifications within the global scope will be set
   */
  fun markAllRead(read: Boolean, organizationId: OrganizationId?) {
    requirePermissions { updateNotifications(organizationId) }
    dslContext
        .update(NOTIFICATIONS)
        .set(NOTIFICATIONS.IS_READ, read)
        .where(isOrganizationIdClause(organizationId))
        .and(NOTIFICATIONS.USER_ID.eq(currentUser().userId))
        .execute()
  }

  /**
   * Create a user notification
   *
   * @param notification The notification to insert into the table. This could be scoped to an
   * organization or global. Global notifications will have the organizationId property set as null.
   * @param targetOrganizationId The organization id that was relevant to this notification, used
   * primarily to validate permissions allowing creation of this notification. For global
   * notifications, the organization id in the notification model will be null, hence this addition
   * organization id parameter to capture context.
   */
  fun create(notification: CreateNotificationModel, targetOrganizationId: OrganizationId) {
    requirePermissions { createNotification(notification.userId, targetOrganizationId) }
    with(NOTIFICATIONS) {
      with(notification) {
        dslContext
            .insertInto(NOTIFICATIONS)
            .set(NOTIFICATION_TYPE_ID, notificationType)
            .set(USER_ID, userId)
            .set(ORGANIZATION_ID, organizationId)
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
}
