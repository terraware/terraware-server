package com.terraformation.backend.email

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.FacilityAlertRequestedEvent
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.event.UserAddedToTerrawareEvent
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.daily.NotificationJobFinishedEvent
import com.terraformation.backend.daily.NotificationJobStartedEvent
import com.terraformation.backend.daily.NotificationJobSucceededEvent
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.event.DeviceUnresponsiveEvent
import com.terraformation.backend.device.event.SensorBoundsAlertTriggeredEvent
import com.terraformation.backend.device.event.UnknownAutomationTriggeredEvent
import com.terraformation.backend.email.model.AccessionDryingEnd
import com.terraformation.backend.email.model.DeviceUnresponsive
import com.terraformation.backend.email.model.EmailTemplateModel
import com.terraformation.backend.email.model.FacilityAlertRequested
import com.terraformation.backend.email.model.FacilityIdle
import com.terraformation.backend.email.model.MissingContact
import com.terraformation.backend.email.model.NurserySeedlingBatchReady
import com.terraformation.backend.email.model.ObservationNotScheduled
import com.terraformation.backend.email.model.ObservationPlotReplaced
import com.terraformation.backend.email.model.ObservationRescheduled
import com.terraformation.backend.email.model.ObservationScheduled
import com.terraformation.backend.email.model.ObservationStarted
import com.terraformation.backend.email.model.ObservationUpcoming
import com.terraformation.backend.email.model.ReportCreated
import com.terraformation.backend.email.model.ScheduleObservation
import com.terraformation.backend.email.model.ScheduleObservationReminder
import com.terraformation.backend.email.model.SensorBoundsAlert
import com.terraformation.backend.email.model.UnknownAutomationTriggered
import com.terraformation.backend.email.model.UserAddedToOrganization
import com.terraformation.backend.email.model.UserAddedToTerraware
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.event.NurserySeedlingBatchReadyEvent
import com.terraformation.backend.report.event.ReportCreatedEvent
import com.terraformation.backend.seedbank.event.AccessionDryingEndEvent
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.event.ObservationNotScheduledNotificationEvent
import com.terraformation.backend.tracking.event.ObservationPlotReplacedEvent
import com.terraformation.backend.tracking.event.ObservationRescheduledEvent
import com.terraformation.backend.tracking.event.ObservationScheduledEvent
import com.terraformation.backend.tracking.event.ObservationStartedEvent
import com.terraformation.backend.tracking.event.ObservationUpcomingNotificationDueEvent
import com.terraformation.backend.tracking.event.ScheduleObservationNotificationEvent
import com.terraformation.backend.tracking.event.ScheduleObservationReminderNotificationEvent
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class EmailNotificationService(
    private val automationStore: AutomationStore,
    private val config: TerrawareServerConfig,
    private val deviceStore: DeviceStore,
    private val emailService: EmailService,
    private val facilityStore: FacilityStore,
    private val organizationStore: OrganizationStore,
    private val parentStore: ParentStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val systemUser: SystemUser,
    private val userStore: UserStore,
    private val webAppUrls: WebAppUrls,
) {
  private val log = perClassLogger()
  private val pendingEmails: ThreadLocal<MutableList<EmailRequest>> =
      ThreadLocal.withInitial { mutableListOf() }

  /**
   * Sends a client-supplied alert about a facility. These alerts are typically generated by the
   * device manager.
   */
  @EventListener
  fun on(event: FacilityAlertRequestedEvent) {
    val requestedByUser = userStore.fetchOneById(event.requestedBy)
    requirePermissions(requestedByUser) { sendAlert(event.facilityId) }

    log.info("Alert for facility ${event.facilityId} requested by user ${requestedByUser.userId}")
    log.info("Alert subject: ${event.subject}")
    log.info("Alert body: ${event.body}")

    val facility = facilityStore.fetchOneById(event.facilityId)

    emailService.sendFacilityNotification(
        event.facilityId,
        FacilityAlertRequested(config, event.body, facility, requestedByUser, event.subject))
  }

  @EventListener
  fun on(event: FacilityIdleEvent) {
    val facility = facilityStore.fetchOneById(event.facilityId)

    val facilityMonitoringUrl =
        webAppUrls
            .fullFacilityMonitoring(
                parentStore.getOrganizationId(event.facilityId)!!, event.facilityId)
            .toString()
    emailService.sendFacilityNotification(
        facility.id, FacilityIdle(config, facility, facilityMonitoringUrl))
  }

  @EventListener
  fun on(event: SensorBoundsAlertTriggeredEvent) {
    val automation = automationStore.fetchOneById(event.automationId)
    val deviceId =
        automation.deviceId
            ?: throw IllegalStateException("Automation ${automation.id} has no device ID")
    val device = deviceStore.fetchOneById(deviceId)
    val facility = facilityStore.fetchOneById(automation.facilityId)
    val organizationId =
        parentStore.getOrganizationId(facility.id) ?: throw FacilityNotFoundException(facility.id)

    val facilityMonitoringUrl =
        webAppUrls.fullFacilityMonitoring(organizationId, facility.id, device).toString()

    emailService.sendFacilityNotification(
        facility.id,
        SensorBoundsAlert(config, automation, device, facility, event.value, facilityMonitoringUrl))
  }

  @EventListener
  fun on(event: UnknownAutomationTriggeredEvent) {
    val automation = automationStore.fetchOneById(event.automationId)
    val devicesRow = automation.deviceId?.let { deviceStore.fetchOneById(it) }
    val facility = facilityStore.fetchOneById(automation.facilityId)
    val organizationId =
        parentStore.getOrganizationId(facility.id) ?: throw FacilityNotFoundException(facility.id)

    val facilityMonitoringUrl =
        webAppUrls.fullFacilityMonitoring(organizationId, facility.id, devicesRow).toString()

    emailService.sendFacilityNotification(
        facility.id,
        UnknownAutomationTriggered(
            config, automation, facility, event.message, facilityMonitoringUrl))
  }

  @EventListener
  fun on(event: DeviceUnresponsiveEvent) {
    val device = deviceStore.fetchOneById(event.deviceId)
    val facilityId =
        device.facilityId ?: throw IllegalStateException("Device ${event.deviceId} has no facility")
    val facility = facilityStore.fetchOneById(facilityId)
    val organizationId =
        parentStore.getOrganizationId(facilityId) ?: throw FacilityNotFoundException(facilityId)

    val facilityMonitoringUrl =
        webAppUrls.fullFacilityMonitoring(organizationId, facilityId, device).toString()

    emailService.sendFacilityNotification(
        facilityId, DeviceUnresponsive(config, device, facility, facilityMonitoringUrl))
  }

  @EventListener
  fun on(event: UserAddedToOrganizationEvent) {
    val admin =
        userStore.fetchOneById(event.addedBy) as? IndividualUser
            ?: throw IllegalArgumentException("Admin user must be an individual user")
    val user =
        userStore.fetchOneById(event.userId) as? IndividualUser
            ?: throw IllegalArgumentException("User must be an individual user")
    val organization = systemUser.run { organizationStore.fetchOneById(event.organizationId) }

    val organizationHomeUrl = webAppUrls.fullOrganizationHome(event.organizationId).toString()

    emailService.sendUserNotification(
        user,
        UserAddedToOrganization(config, admin, organization, organizationHomeUrl),
        requireOptIn = false)
  }

  @EventListener
  fun on(event: UserAddedToTerrawareEvent) {
    val admin =
        userStore.fetchOneById(event.addedBy) as? IndividualUser
            ?: throw IllegalArgumentException("Admin user must be an individual user")
    val user =
        userStore.fetchOneById(event.userId) as? IndividualUser
            ?: throw IllegalArgumentException("User must be an individual user")
    val organization = systemUser.run { organizationStore.fetchOneById(event.organizationId) }

    val terrawareRegistrationUrl =
        webAppUrls.terrawareRegistrationUrl(event.organizationId, user.email).toString()

    emailService.sendUserNotification(
        user,
        UserAddedToTerraware(config, admin, organization, terrawareRegistrationUrl),
        requireOptIn = false)
  }

  @EventListener
  fun on(event: AccessionDryingEndEvent) {
    val organizationId =
        parentStore.getOrganizationId(event.accessionId)
            ?: throw AccessionNotFoundException(event.accessionId)
    val facilityName = parentStore.getFacilityName(event.accessionId)
    val accessionUrl = webAppUrls.fullAccession(event.accessionId, organizationId).toString()
    getRecipients(event.accessionId).forEach { user ->
      pendingEmails
          .get()
          .add(
              EmailRequest(
                  user,
                  AccessionDryingEnd(config, event.accessionNumber, facilityName, accessionUrl)))
    }
  }

  @EventListener
  fun on(event: NurserySeedlingBatchReadyEvent) {
    val facilityId = parentStore.getFacilityId(event.batchId)!!
    val organizationId = parentStore.getOrganizationId(facilityId)!!
    val batchUrl =
        webAppUrls.fullBatch(organizationId, event.batchNumber, event.speciesId).toString()

    log.info("Creating email notifications for batchId ${event.batchId.value} ready.")
    getRecipients(facilityId).forEach { user ->
      pendingEmails
          .get()
          .add(
              EmailRequest(
                  user,
                  NurserySeedlingBatchReady(
                      config, event.batchNumber, batchUrl, event.nurseryName)))
    }
  }

  @EventListener
  fun on(event: ReportCreatedEvent) {
    val reportUrl =
        webAppUrls.fullReport(event.metadata.id, event.metadata.organizationId).toString()

    emailService.sendOrganizationNotification(
        event.metadata.organizationId,
        ReportCreated(config, event.metadata.year, event.metadata.quarter, reportUrl),
        roles = setOf(Role.Owner, Role.Admin))
  }

  @EventListener
  fun on(event: ObservationStartedEvent) {
    val plantingSite =
        plantingSiteStore.fetchSiteById(event.observation.plantingSiteId, PlantingSiteDepth.Site)
    val observationsUrl =
        webAppUrls.fullObservations(plantingSite.organizationId, plantingSite.id).toString()

    emailService.sendOrganizationNotification(
        plantingSite.organizationId, ObservationStarted(config, observationsUrl))
  }

  @EventListener
  fun on(event: ObservationUpcomingNotificationDueEvent) {
    val plantingSite =
        plantingSiteStore.fetchSiteById(event.observation.plantingSiteId, PlantingSiteDepth.Site)
    val observationsUrl =
        webAppUrls.fullObservations(plantingSite.organizationId, plantingSite.id).toString()

    emailService.sendOrganizationNotification(
        plantingSite.organizationId,
        ObservationUpcoming(
            config,
            plantingSite.name,
            event.observation.startDate,
            observationsUrl,
            webAppUrls.appStore.toString(),
            webAppUrls.googlePlay.toString()))
  }

  @EventListener
  fun on(event: ObservationScheduledEvent) {
    val organizationId = parentStore.getOrganizationId(event.observation.id)!!
    val organization =
        organizationStore.fetchOneById(organizationId, OrganizationStore.FetchDepth.Organization)
    val plantingSite =
        plantingSiteStore.fetchSiteById(event.observation.plantingSiteId, PlantingSiteDepth.Site)
    emailService.sendOrganizationNotification(
        organizationId,
        ObservationScheduled(
            config,
            organization.name,
            plantingSite.name,
            event.observation.startDate,
            event.observation.endDate,
        ),
        false,
        setOf(Role.TerraformationContact))
  }

  @EventListener
  fun on(event: ObservationRescheduledEvent) {
    val organizationId = parentStore.getOrganizationId(event.originalObservation.id)!!
    val organization =
        organizationStore.fetchOneById(organizationId, OrganizationStore.FetchDepth.Organization)
    val plantingSite =
        plantingSiteStore.fetchSiteById(
            event.originalObservation.plantingSiteId,
            PlantingSiteDepth.Site,
        )
    emailService.sendOrganizationNotification(
        organizationId,
        ObservationRescheduled(
            config,
            organization.name,
            plantingSite.name,
            event.originalObservation.startDate,
            event.originalObservation.endDate,
            event.rescheduledObservation.startDate,
            event.rescheduledObservation.endDate,
        ),
        false,
        setOf(Role.TerraformationContact))
  }

  @EventListener
  fun on(event: ScheduleObservationNotificationEvent) {
    val plantingSite =
        plantingSiteStore.fetchSiteById(
            event.plantingSiteId,
            PlantingSiteDepth.Site,
        )
    emailService.sendOrganizationNotification(
        plantingSite.organizationId,
        ScheduleObservation(
            config,
            plantingSite.organizationId,
            plantingSite.id,
            plantingSite.name,
            webAppUrls.fullObservations(plantingSite.organizationId, plantingSite.id).toString(),
        ),
        roles = setOf(Role.Admin, Role.Owner))
  }

  @EventListener
  fun on(event: ScheduleObservationReminderNotificationEvent) {
    val plantingSite =
        plantingSiteStore.fetchSiteById(
            event.plantingSiteId,
            PlantingSiteDepth.Site,
        )
    emailService.sendOrganizationNotification(
        plantingSite.organizationId,
        ScheduleObservationReminder(
            config,
            plantingSite.organizationId,
            plantingSite.id,
            plantingSite.name,
            webAppUrls.fullObservations(plantingSite.organizationId, plantingSite.id).toString(),
        ),
        roles = setOf(Role.Admin, Role.Owner))
  }

  @EventListener
  fun on(event: ObservationNotScheduledNotificationEvent) {
    val plantingSite = plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)
    val organization =
        organizationStore.fetchOneById(
            plantingSite.organizationId, OrganizationStore.FetchDepth.Organization)
    val model = ObservationNotScheduled(config, organization.name, plantingSite.name)

    sendToOrganizationContact(organization, model)
  }

  @EventListener
  fun on(event: ObservationPlotReplacedEvent) {
    val plantingSite =
        plantingSiteStore.fetchSiteById(event.observation.plantingSiteId, PlantingSiteDepth.Site)
    val organization =
        organizationStore.fetchOneById(
            plantingSite.organizationId, OrganizationStore.FetchDepth.Organization)
    val model =
        ObservationPlotReplaced(
            config, organization.name, plantingSite.name, event.justification, event.duration)

    sendToOrganizationContact(organization, model)
  }

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: NotificationJobStartedEvent) {
    pendingEmails.remove()
  }

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: NotificationJobSucceededEvent) {
    pendingEmails.get().forEach { sendEmail(it) }
    pendingEmails.remove()
  }

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: NotificationJobFinishedEvent) {
    pendingEmails.remove()
  }

  private fun sendEmail(emailRequest: EmailRequest) {
    with(emailRequest) {
      try {
        emailService.sendUserNotification(user, emailTemplateModel)
      } catch (e: Exception) {
        log.error("Error sending email ${emailTemplateModel.templateDir} to user ${user.email}", e)
      }
    }
  }

  private fun getRecipients(accessionId: AccessionId): List<IndividualUser> {
    val facilityId =
        parentStore.getFacilityId(accessionId) ?: throw AccessionNotFoundException(accessionId)
    return getRecipients(facilityId)
  }

  private fun getRecipients(facilityId: FacilityId): List<IndividualUser> {
    val organizationId =
        parentStore.getOrganizationId(facilityId) ?: throw FacilityNotFoundException(facilityId)
    return userStore.fetchByOrganizationId(
        organizationId, roles = EmailService.defaultOrgRolesForNotification)
  }

  private fun getTerraformationContactUser(organizationId: OrganizationId): IndividualUser? {
    val tfContactId = organizationStore.fetchTerraformationContact(organizationId) ?: return null
    return userStore.fetchOneById(tfContactId) as? IndividualUser
        ?: throw IllegalArgumentException("Terraformation Contact user must be an individual user")
  }

  /**
   * Sends an email notification to the Terraformation Contact if there is one for the organization,
   * otherwise sends the notification to Terrformation Support and generates an additional
   * notification about the organization not having a contact.
   */
  private fun sendToOrganizationContact(
      organization: OrganizationModel,
      model: EmailTemplateModel
  ) {
    val user = getTerraformationContactUser(organization.id)
    if (user != null) {
      emailService.sendUserNotification(user, model, false)
    } else {
      emailService.sendSupportNotification(model)
      emailService.sendSupportNotification(
          MissingContact(config, organization.id, organization.name))
    }
  }

  data class EmailRequest(val user: IndividualUser, val emailTemplateModel: EmailTemplateModel)
}
