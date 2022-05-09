package com.terraformation.backend.customer

import com.terraformation.backend.customer.db.NotificationStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.FacilityAlertRequestedEvent
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.event.UserAddedToProjectEvent
import com.terraformation.backend.customer.model.CreateNotificationModel
import com.terraformation.backend.db.NotificationType
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.email.WebAppUrls
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import java.net.URI
import javax.annotation.ManagedBean
import org.springframework.context.event.EventListener

@ManagedBean
class AppNotificationService(
    private val notificationStore: NotificationStore,
    private val organizationStore: OrganizationStore,
    private val projectStore: ProjectStore,
    private val userStore: UserStore,
    private val messages: Messages,
    private val webAppUrls: WebAppUrls,
) {
  private val log = perClassLogger()

  @EventListener
  fun on(event: FacilityAlertRequestedEvent) {
    log.info(
        "Creating app notification for facility \"${event.facilityId}\" alert requested event.")
    // TODO create notification
  }

  @EventListener
  fun on(event: FacilityIdleEvent) {
    log.info("Creating app notification for facility \"${event.facilityId}\" idle event.")
    // TODO create notification
  }

  @EventListener
  fun on(event: UserAddedToOrganizationEvent) {
    userStore.fetchById(event.addedBy) ?: throw UserNotFoundException(event.addedBy)
    val user = userStore.fetchById(event.userId) ?: throw UserNotFoundException(event.userId)
    val organization =
        organizationStore.fetchById(event.organizationId)
            ?: throw OrganizationNotFoundException(event.organizationId)

    val organizationHomeUrl = webAppUrls.organizationHome(event.organizationId).toString()
    val message = messages.userAddedToOrganizationNotification(organization.name)

    log.info(
        "Creating app notification for user ${event.userId} being added to an organization" +
            "${event.organizationId}.")

    val notification =
        CreateNotificationModel(
            userId = user.userId,
            organizationId = null,
            title = message.title,
            body = message.body,
            localUrl = URI.create(organizationHomeUrl),
            notificationType = NotificationType.UserAddedtoOrganization)
    notificationStore.create(notification, organization.id)
  }

  @EventListener
  fun on(event: UserAddedToProjectEvent) {
    userStore.fetchById(event.addedBy) ?: throw UserNotFoundException(event.addedBy)
    val user = userStore.fetchById(event.userId) ?: throw UserNotFoundException(event.userId)
    val project =
        projectStore.fetchById(event.projectId) ?: throw ProjectNotFoundException(event.projectId)
    val organization =
        organizationStore.fetchById(project.organizationId)
            ?: throw OrganizationNotFoundException(project.organizationId)

    val projectHomeUrl = webAppUrls.projectHome(event.projectId).toString()
    val message = messages.userAddedToProjectNotification(project.name, organization.name)

    log.info(
        "Creating app notification for user ${event.userId} being added to a project " +
            "${event.projectId}.")

    val notification =
        CreateNotificationModel(
            userId = user.userId,
            organizationId = organization.id,
            title = message.title,
            body = message.body,
            localUrl = URI.create(projectHomeUrl),
            notificationType = NotificationType.UserAddedtoOrganization)
    notificationStore.create(notification, organization.id)
  }
}
