package com.terraformation.backend.customer

import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.NotificationStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.model.CreateNotificationModel
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.event.DeviceUnresponsiveEvent
import com.terraformation.backend.device.event.SensorBoundsAlertTriggeredEvent
import com.terraformation.backend.device.event.UnknownAutomationTriggeredEvent
import com.terraformation.backend.email.WebAppUrls
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.NotificationMessage
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.event.NurserySeedlingBatchReadyEvent
import com.terraformation.backend.seedbank.event.AccessionDryingEndEvent
import java.net.URI
import javax.inject.Named
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

@Named
class AppNotificationService(
    private val automationStore: AutomationStore,
    private val deviceStore: DeviceStore,
    private val dslContext: DSLContext,
    private val facilityStore: FacilityStore,
    private val notificationStore: NotificationStore,
    private val organizationStore: OrganizationStore,
    private val parentStore: ParentStore,
    private val userStore: UserStore,
    private val messages: Messages,
    private val webAppUrls: WebAppUrls,
) {
  private val log = perClassLogger()

  @EventListener
  fun on(event: FacilityIdleEvent) {
    log.info("Creating app notification for facility \"${event.facilityId}\" idle event.")
    val facilityUrl = webAppUrls.facilityMonitoring(event.facilityId)
    val message = messages.facilityIdle()
    insertFacilityNotifications(
        event.facilityId, NotificationType.FacilityIdle, message, facilityUrl)
  }

  @EventListener
  fun on(event: SensorBoundsAlertTriggeredEvent) {
    val automation = automationStore.fetchOneById(event.automationId)
    val timeseriesName =
        automation.timeseriesName
            ?: throw IllegalStateException("Automation ${automation.id} has no timeseries name")
    val deviceId =
        automation.deviceId
            ?: throw IllegalStateException("Automation ${automation.id} has no device ID")
    val device = deviceStore.fetchOneById(deviceId)
    val facility = facilityStore.fetchOneById(automation.facilityId)

    val facilityUrl = webAppUrls.facilityMonitoring(facility.id, device)
    val message = messages.sensorBoundsAlert(device, facility.name, timeseriesName, event.value)

    insertFacilityNotifications(
        facility.id, NotificationType.SensorOutOfBounds, message, facilityUrl)
  }

  @EventListener
  fun on(event: UnknownAutomationTriggeredEvent) {
    val automation = automationStore.fetchOneById(event.automationId)
    val facility = facilityStore.fetchOneById(automation.facilityId)

    val facilityUrl = webAppUrls.facilityMonitoring(facility.id)
    val message = messages.unknownAutomationTriggered(automation.name, facility.name, event.message)

    insertFacilityNotifications(
        facility.id, NotificationType.UnknownAutomationTriggered, message, facilityUrl)
  }

  @EventListener
  fun on(event: DeviceUnresponsiveEvent) {
    val device = deviceStore.fetchOneById(event.deviceId)
    val deviceName =
        device.name ?: throw IllegalStateException("Device ${event.deviceId} has no name")
    val facilityId =
        device.facilityId ?: throw IllegalStateException("Device ${event.deviceId} has no facility")

    val facilityUrl = webAppUrls.facilityMonitoring(facilityId, device)
    val message = messages.deviceUnresponsive(deviceName)

    insertFacilityNotifications(
        facilityId, NotificationType.DeviceUnresponsive, message, facilityUrl)
  }

  @EventListener
  fun on(event: UserAddedToOrganizationEvent) {
    userStore.fetchOneById(event.addedBy)
    val user = userStore.fetchOneById(event.userId)
    val organization = organizationStore.fetchOneById(event.organizationId)

    val organizationHomeUrl = webAppUrls.organizationHome(event.organizationId)
    val message = messages.userAddedToOrganizationNotification(organization.name)

    log.info(
        "Creating app notification for user ${event.userId} being added to an organization" +
            "${event.organizationId}.")

    insert(
        NotificationType.UserAddedtoOrganization,
        user.userId,
        null,
        message,
        organizationHomeUrl,
        organization.id)
  }

  @EventListener
  fun on(event: AccessionDryingEndEvent) {
    val accessionUrl = webAppUrls.accession(event.accessionId)
    val message = messages.accessionDryingEndNotification(event.accessionNumber)

    log.info("Creating app notifications for accession ${event.accessionNumber} ends drying.")

    insertFacilityNotifications(
        event.accessionId,
        NotificationType.AccessionScheduledtoEndDrying,
        message,
        accessionUrl,
    )
  }

  @EventListener
  fun on(event: NurserySeedlingBatchReadyEvent) {
    val batchUrl = webAppUrls.batch(event.batchNumber, event.speciesId)
    val message =
        messages.nurserySeedlingBatchReadyNotification(event.batchNumber, event.nurseryName)

    log.info("Creating app notifications for batchId ${event.batchId.value} ready.")

    val facilityId = parentStore.getFacilityId(event.batchId)!!
    insertFacilityNotifications(
        facilityId,
        NotificationType.NurserySeedlingBatchReady,
        message,
        batchUrl,
    )
  }

  private fun insertFacilityNotifications(
      accessionId: AccessionId,
      notificationType: NotificationType,
      message: NotificationMessage,
      localUrl: URI
  ) {
    val facilityId = parentStore.getFacilityId(accessionId)!!
    insertFacilityNotifications(facilityId, notificationType, message, localUrl)
  }

  private fun insertFacilityNotifications(
      facilityId: FacilityId,
      notificationType: NotificationType,
      message: NotificationMessage,
      localUrl: URI
  ) {
    val organizationId = parentStore.getOrganizationId(facilityId)!!
    val recipients =
        organizationStore.fetchEmailRecipients(organizationId, false).mapNotNull {
          userStore.fetchByEmail(it)
        }
    dslContext.transaction { _ ->
      recipients.forEach { user ->
        insert(notificationType, user.userId, organizationId, message, localUrl, organizationId)
      }
    }
  }

  private fun insert(
      notificationType: NotificationType,
      userId: UserId,
      organizationId: OrganizationId?,
      message: NotificationMessage,
      localUrl: URI,
      targetOrganizationId: OrganizationId
  ) {
    val notification =
        CreateNotificationModel(
            notificationType, userId, organizationId, message.title, message.body, localUrl)
    notificationStore.create(notification, targetOrganizationId)
  }
}
