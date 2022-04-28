package com.terraformation.backend.customer

import com.terraformation.backend.customer.db.NotificationStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.FacilityAlertRequestedEvent
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.model.CreateNotificationModel
import com.terraformation.backend.db.NotificationType
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.email.WebAppUrls
import com.terraformation.backend.log.perClassLogger
import java.net.URI
import javax.annotation.ManagedBean
import org.springframework.context.event.EventListener

@ManagedBean
class AppNotificationService(
    private val organizationStore: OrganizationStore,
    private val userStore: UserStore,
    private val webAppUrls: WebAppUrls,
    private val notificationStore: NotificationStore,
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
    val admin = userStore.fetchById(event.addedBy) ?: throw UserNotFoundException(event.addedBy)
    val user = userStore.fetchById(event.userId) ?: throw UserNotFoundException(event.userId)
    val organization =
        organizationStore.fetchById(event.organizationId)
            ?: throw OrganizationNotFoundException(event.organizationId)

    val organizationHomeUrl = webAppUrls.organizationHome(event.organizationId).toString()

    log.info("Creating app notification for user being added to an organization.")

    val metadata: Map<String, Any> =
        mapOf(
            "organizationName" to organization.name,
            "adminEmail" to admin.email,
            "organizationId" to event.organizationId)

    val notification =
        CreateNotificationModel(
            userId = user.userId,
            organizationId = null,
            localUrl = URI.create(organizationHomeUrl),
            metadata = metadata,
            notificationType = NotificationType.UserAddedtoOrganization,
        )
    notificationStore.create(notification)
  }
}
