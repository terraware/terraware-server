package com.terraformation.backend.customer

import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.NotificationStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.event.UserAddedToTerrawareEvent
import com.terraformation.backend.customer.model.CreateNotificationModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.event.DeviceUnresponsiveEvent
import com.terraformation.backend.device.event.SensorBoundsAlertTriggeredEvent
import com.terraformation.backend.device.event.UnknownAutomationTriggeredEvent
import com.terraformation.backend.email.WebAppUrls
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.NotificationMessage
import com.terraformation.backend.i18n.use
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.event.NurserySeedlingBatchReadyEvent
import com.terraformation.backend.report.event.ReportCreatedEvent
import com.terraformation.backend.seedbank.event.AccessionDryingEndEvent
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.event.ObservationUpcomingNotificationDueEvent
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import java.net.URI
import java.util.Locale
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
    private val plantingSiteStore: PlantingSiteStore,
    private val userStore: UserStore,
    private val messages: Messages,
    private val webAppUrls: WebAppUrls,
) {
  private val log = perClassLogger()

  @EventListener
  fun on(event: FacilityIdleEvent) {
    log.info("Creating app notification for facility \"${event.facilityId}\" idle event.")
    val facilityUrl = webAppUrls.facilityMonitoring(event.facilityId)
    val renderMessage = { messages.facilityIdle() }
    insertFacilityNotifications(
        event.facilityId, NotificationType.FacilityIdle, renderMessage, facilityUrl)
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
    val renderMessage = {
      messages.sensorBoundsAlert(device, facility.name, timeseriesName, event.value)
    }

    insertFacilityNotifications(
        facility.id, NotificationType.SensorOutOfBounds, renderMessage, facilityUrl)
  }

  @EventListener
  fun on(event: UnknownAutomationTriggeredEvent) {
    val automation = automationStore.fetchOneById(event.automationId)
    val facility = facilityStore.fetchOneById(automation.facilityId)

    val facilityUrl = webAppUrls.facilityMonitoring(facility.id)
    val renderMessage = {
      messages.unknownAutomationTriggered(automation.name, facility.name, event.message)
    }

    insertFacilityNotifications(
        facility.id, NotificationType.UnknownAutomationTriggered, renderMessage, facilityUrl)
  }

  @EventListener
  fun on(event: DeviceUnresponsiveEvent) {
    val device = deviceStore.fetchOneById(event.deviceId)
    val deviceName =
        device.name ?: throw IllegalStateException("Device ${event.deviceId} has no name")
    val facilityId =
        device.facilityId ?: throw IllegalStateException("Device ${event.deviceId} has no facility")

    val facilityUrl = webAppUrls.facilityMonitoring(facilityId, device)
    val renderMessage = { messages.deviceUnresponsive(deviceName) }

    insertFacilityNotifications(
        facilityId, NotificationType.DeviceUnresponsive, renderMessage, facilityUrl)
  }

  @EventListener
  fun on(event: UserAddedToOrganizationEvent) {
    insertUserAddedToOrganizationNotification(event.addedBy, event.userId, event.organizationId)
  }

  @EventListener
  fun on(event: UserAddedToTerrawareEvent) {
    insertUserAddedToOrganizationNotification(event.addedBy, event.userId, event.organizationId)
  }

  @EventListener
  fun on(event: AccessionDryingEndEvent) {
    val accessionUrl = webAppUrls.accession(event.accessionId)
    val renderMessage = { messages.accessionDryingEndNotification(event.accessionNumber) }

    log.info("Creating app notifications for accession ${event.accessionNumber} ends drying.")

    insertFacilityNotifications(
        event.accessionId,
        NotificationType.AccessionScheduledToEndDrying,
        renderMessage,
        accessionUrl,
    )
  }

  @EventListener
  fun on(event: NurserySeedlingBatchReadyEvent) {
    val batchUrl = webAppUrls.batch(event.batchNumber, event.speciesId)
    val renderMessage = {
      messages.nurserySeedlingBatchReadyNotification(event.batchNumber, event.nurseryName)
    }

    log.info("Creating app notifications for batchId ${event.batchId.value} ready.")

    val facilityId = parentStore.getFacilityId(event.batchId)!!
    insertFacilityNotifications(
        facilityId,
        NotificationType.NurserySeedlingBatchReady,
        renderMessage,
        batchUrl,
    )
  }

  @EventListener
  fun on(event: ReportCreatedEvent) {
    val reportUrl = webAppUrls.report(event.metadata.id)
    val renderMessage = { messages.reportCreated(event.metadata.year, event.metadata.quarter) }

    log.info("Creating app notifications for report ${event.metadata.id} created.")

    insertOrganizationNotifications(
        event.metadata.organizationId,
        NotificationType.ReportCreated,
        renderMessage,
        reportUrl,
        setOf(Role.Owner, Role.Admin))
  }

  @EventListener
  fun on(event: ObservationUpcomingNotificationDueEvent) {
    val plantingSite =
        plantingSiteStore.fetchSiteById(event.observation.plantingSiteId, PlantingSiteDepth.Site)
    val observationsUrl = webAppUrls.observations(plantingSite.organizationId, plantingSite.id)
    val renderMessage = {
      messages.observationUpcoming(plantingSite.name, event.observation.startDate)
    }

    log.info("Creating app notifications for observation ${event.observation.id} upcoming.")

    insertOrganizationNotifications(
        plantingSite.organizationId,
        NotificationType.ObservationUpcoming,
        renderMessage,
        observationsUrl,
    )
  }

  private fun insertFacilityNotifications(
      accessionId: AccessionId,
      notificationType: NotificationType,
      renderMessage: () -> NotificationMessage,
      localUrl: URI
  ) {
    val facilityId = parentStore.getFacilityId(accessionId)!!
    insertFacilityNotifications(facilityId, notificationType, renderMessage, localUrl)
  }

  private fun insertFacilityNotifications(
      facilityId: FacilityId,
      notificationType: NotificationType,
      renderMessage: () -> NotificationMessage,
      localUrl: URI
  ) {
    val organizationId = parentStore.getOrganizationId(facilityId)!!
    insertOrganizationNotifications(organizationId, notificationType, renderMessage, localUrl)
  }

  private fun insertUserAddedToOrganizationNotification(
      addedBy: UserId,
      userId: UserId,
      organizationId: OrganizationId
  ) {
    userStore.fetchOneById(addedBy)
    val user = userStore.fetchOneById(userId)
    val organization = organizationStore.fetchOneById(organizationId)

    val organizationHomeUrl = webAppUrls.organizationHome(organizationId)
    val renderMessage = { messages.userAddedToOrganizationNotification(organization.name) }

    log.info(
        "Creating app notification for user ${userId} being added to an organization " +
            "${organizationId}.")

    insert(
        NotificationType.UserAddedToOrganization,
        user,
        null,
        renderMessage,
        organizationHomeUrl,
        organization.id)
  }

  private fun insertOrganizationNotifications(
      organizationId: OrganizationId,
      notificationType: NotificationType,
      renderMessage: () -> NotificationMessage,
      localUrl: URI,
      roles: Set<Role>? = null,
  ) {
    val recipients = userStore.fetchByOrganizationId(organizationId, false, roles)

    dslContext.transaction { _ ->
      recipients.forEach { user ->
        insert(notificationType, user, organizationId, renderMessage, localUrl, organizationId)
      }
    }
  }

  private fun insert(
      notificationType: NotificationType,
      user: TerrawareUser,
      organizationId: OrganizationId?,
      renderMessage: () -> NotificationMessage,
      localUrl: URI,
      targetOrganizationId: OrganizationId
  ) {
    val locale = user.locale ?: Locale.ENGLISH
    val message = locale.use { renderMessage() }
    val notification =
        CreateNotificationModel(
            notificationType, user.userId, organizationId, message.title, message.body, localUrl)
    notificationStore.create(notification, targetOrganizationId)
  }
}
