package com.terraformation.backend.notification

import com.terraformation.backend.accelerator.ProjectAcceleratorDetailsService
import com.terraformation.backend.accelerator.db.ActivityStore
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ModuleEventStore
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.accelerator.event.AcceleratorReportPublishedEvent
import com.terraformation.backend.accelerator.event.AcceleratorReportUpcomingEvent
import com.terraformation.backend.accelerator.event.ActivityCreatedEvent
import com.terraformation.backend.accelerator.event.ApplicationSubmittedEvent
import com.terraformation.backend.accelerator.event.DeliverableReadyForReviewEvent
import com.terraformation.backend.accelerator.event.DeliverableStatusUpdatedEvent
import com.terraformation.backend.accelerator.event.ModuleEventStartingEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedToProjectNotificationDueEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent
import com.terraformation.backend.accelerator.event.RateLimitedAcceleratorReportSubmittedEvent
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.NotificationStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserInternalInterestsStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.FacilityAlertRequestedEvent
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.event.UserAddedToTerrawareEvent
import com.terraformation.backend.customer.model.CreateNotificationModel
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.daily.NotificationJobFinishedEvent
import com.terraformation.backend.daily.NotificationJobStartedEvent
import com.terraformation.backend.daily.NotificationJobSucceededEvent
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.InternalInterest
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ProjectInternalRole
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.event.DeviceUnresponsiveEvent
import com.terraformation.backend.device.event.SensorBoundsAlertTriggeredEvent
import com.terraformation.backend.device.event.UnknownAutomationTriggeredEvent
import com.terraformation.backend.documentproducer.db.DocumentStore
import com.terraformation.backend.documentproducer.db.VariableOwnerStore
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.event.CompletedSectionVariableUpdatedEvent
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableStatusUpdatedEvent
import com.terraformation.backend.email.EmailService
import com.terraformation.backend.email.WebAppUrls
import com.terraformation.backend.email.model.AcceleratorReportPublished
import com.terraformation.backend.email.model.AcceleratorReportSubmitted
import com.terraformation.backend.email.model.AcceleratorReportUpcoming
import com.terraformation.backend.email.model.AccessionDryingEnd
import com.terraformation.backend.email.model.ActivityCreated
import com.terraformation.backend.email.model.ApplicationSubmitted
import com.terraformation.backend.email.model.CompletedSectionVariableUpdated
import com.terraformation.backend.email.model.DeliverableReadyForReview
import com.terraformation.backend.email.model.DeliverableStatusUpdated
import com.terraformation.backend.email.model.DeviceUnresponsive
import com.terraformation.backend.email.model.EmailTemplateModel
import com.terraformation.backend.email.model.FacilityAlertRequested
import com.terraformation.backend.email.model.FacilityIdle
import com.terraformation.backend.email.model.FunderAddedToFundingEntity
import com.terraformation.backend.email.model.MissingContact
import com.terraformation.backend.email.model.MonitoringSpeciesTotalsEdited
import com.terraformation.backend.email.model.NurserySeedlingBatchReady
import com.terraformation.backend.email.model.ObservationNotScheduled
import com.terraformation.backend.email.model.ObservationNotStarted
import com.terraformation.backend.email.model.ObservationPlotReplaced
import com.terraformation.backend.email.model.ObservationRescheduled
import com.terraformation.backend.email.model.ObservationScheduled
import com.terraformation.backend.email.model.ObservationStarted
import com.terraformation.backend.email.model.ObservationUpcoming
import com.terraformation.backend.email.model.ParticipantProjectSpeciesAdded
import com.terraformation.backend.email.model.ParticipantProjectSpeciesEdited
import com.terraformation.backend.email.model.PlantingSeasonNotScheduled
import com.terraformation.backend.email.model.PlantingSeasonNotScheduledSupport
import com.terraformation.backend.email.model.PlantingSeasonRescheduled
import com.terraformation.backend.email.model.PlantingSeasonScheduled
import com.terraformation.backend.email.model.PlantingSeasonStarted
import com.terraformation.backend.email.model.PlantingSiteMapEdited
import com.terraformation.backend.email.model.ScheduleObservation
import com.terraformation.backend.email.model.ScheduleObservationReminder
import com.terraformation.backend.email.model.SeedFundReportCreated
import com.terraformation.backend.email.model.SensorBoundsAlert
import com.terraformation.backend.email.model.SplatGenerationCompleted
import com.terraformation.backend.email.model.SplatGenerationFailed
import com.terraformation.backend.email.model.SplatMarkedNeedsAttention
import com.terraformation.backend.email.model.T0DataSet
import com.terraformation.backend.email.model.UnknownAutomationTriggered
import com.terraformation.backend.email.model.UserAddedToOrganization
import com.terraformation.backend.email.model.UserAddedToTerraware
import com.terraformation.backend.funder.db.FundingEntityStore
import com.terraformation.backend.funder.event.FunderInvitedToFundingEntityEvent
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.NotificationMessage
import com.terraformation.backend.i18n.use
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.event.NurserySeedlingBatchReadyEvent
import com.terraformation.backend.report.event.SeedFundReportCreatedEvent
import com.terraformation.backend.seedbank.event.AccessionDryingEndEvent
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.splat.event.SplatGenerationCompletedEvent
import com.terraformation.backend.splat.event.SplatGenerationFailedEvent
import com.terraformation.backend.splat.event.SplatMarkedNeedsAttentionEvent
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.event.ObservationNotScheduledNotificationEvent
import com.terraformation.backend.tracking.event.ObservationNotStartedEvent
import com.terraformation.backend.tracking.event.ObservationPlotReplacedEvent
import com.terraformation.backend.tracking.event.ObservationRescheduledEvent
import com.terraformation.backend.tracking.event.ObservationScheduledEvent
import com.terraformation.backend.tracking.event.ObservationStartedEvent
import com.terraformation.backend.tracking.event.ObservationUpcomingNotificationDueEvent
import com.terraformation.backend.tracking.event.PlantingSeasonNotScheduledNotificationEvent
import com.terraformation.backend.tracking.event.PlantingSeasonNotScheduledSupportNotificationEvent
import com.terraformation.backend.tracking.event.PlantingSeasonRescheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonScheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteMapEditedEvent
import com.terraformation.backend.tracking.event.RateLimitedMonitoringSpeciesTotalsEditedEvent
import com.terraformation.backend.tracking.event.RateLimitedT0DataAssignedEvent
import com.terraformation.backend.tracking.event.ScheduleObservationNotificationEvent
import com.terraformation.backend.tracking.event.ScheduleObservationReminderNotificationEvent
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import jakarta.inject.Named
import java.net.URI
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
import java.util.Locale
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

/**
 * Creates app notifications and sends emails in response to domain events.
 *
 * Each listener builds an [AppContent] (if the event has a corresponding [NotificationType]) and/or
 * an [EmailTemplateModel] (if an email template exists) and hands them to a `sendTo*` helper that
 * fans out to both channels.
 */
@Named
class NotificationService(
    private val activityStore: ActivityStore,
    private val automationStore: AutomationStore,
    private val clock: InstantSource,
    private val config: TerrawareServerConfig,
    private val deliverableStore: DeliverableStore,
    private val deviceStore: DeviceStore,
    private val documentStore: DocumentStore,
    private val dslContext: DSLContext,
    private val emailService: EmailService,
    private val facilityStore: FacilityStore,
    private val fundingEntityStore: FundingEntityStore,
    private val messages: Messages,
    private val moduleEventStore: ModuleEventStore,
    private val moduleStore: ModuleStore,
    private val notificationStore: NotificationStore,
    private val observationStore: ObservationStore,
    private val organizationStore: OrganizationStore,
    private val parentStore: ParentStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val projectAcceleratorDetailsService: ProjectAcceleratorDetailsService,
    private val projectStore: ProjectStore,
    private val reportStore: ReportStore,
    private val speciesStore: SpeciesStore,
    private val systemUser: SystemUser,
    private val userInternalInterestsStore: UserInternalInterestsStore,
    private val userStore: UserStore,
    private val variableOwnerStore: VariableOwnerStore,
    private val variableStore: VariableStore,
    private val webAppUrls: WebAppUrls,
) {
  private val log = perClassLogger()

  private val pendingEmails: ThreadLocal<MutableList<EmailRequest>> = ThreadLocal.withInitial {
    mutableListOf()
  }

  private data class AppContent(
      val notificationType: NotificationType,
      val localUrl: URI,
      val renderMessage: () -> NotificationMessage,
  )

  private data class EmailRequest(
      val user: TerrawareUser,
      val emailTemplateModel: EmailTemplateModel,
  )

  @EventListener
  fun on(event: FacilityIdleEvent) {
    log.info("Creating notifications for facility \"${event.facilityId}\" idle event.")
    val facility = facilityStore.fetchOneById(event.facilityId)
    val organizationId = parentStore.getOrganizationId(event.facilityId)!!

    val appContent =
        AppContent(
            notificationType = NotificationType.FacilityIdle,
            localUrl = webAppUrls.facilityMonitoring(event.facilityId),
            renderMessage = { messages.facilityIdle() },
        )

    val emailContent =
        FacilityIdle(
            config,
            facility,
            webAppUrls.fullFacilityMonitoring(organizationId, event.facilityId).toString(),
        )

    sendToOrganization(organizationId, appContent, emailContent)
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
    val organizationId =
        parentStore.getOrganizationId(facility.id) ?: throw FacilityNotFoundException(facility.id)

    val appContent =
        AppContent(
            notificationType = NotificationType.SensorOutOfBounds,
            localUrl = webAppUrls.facilityMonitoring(facility.id, device),
            renderMessage = {
              messages.sensorBoundsAlert(device, facility.name, timeseriesName, event.value)
            },
        )

    val emailContent =
        SensorBoundsAlert(
            config,
            automation,
            device,
            facility,
            event.value,
            webAppUrls.fullFacilityMonitoring(organizationId, facility.id, device).toString(),
        )

    sendToOrganization(organizationId, appContent, emailContent)
  }

  @EventListener
  fun on(event: UnknownAutomationTriggeredEvent) {
    val automation = automationStore.fetchOneById(event.automationId)
    val devicesRow = automation.deviceId?.let { deviceStore.fetchOneById(it) }
    val facility = facilityStore.fetchOneById(automation.facilityId)
    val organizationId =
        parentStore.getOrganizationId(facility.id) ?: throw FacilityNotFoundException(facility.id)

    val appContent =
        AppContent(
            notificationType = NotificationType.UnknownAutomationTriggered,
            localUrl = webAppUrls.facilityMonitoring(facility.id),
            renderMessage = {
              messages.unknownAutomationTriggered(automation.name, facility.name, event.message)
            },
        )

    val emailContent =
        UnknownAutomationTriggered(
            config,
            automation,
            facility,
            event.message,
            webAppUrls.fullFacilityMonitoring(organizationId, facility.id, devicesRow).toString(),
        )

    sendToOrganization(organizationId, appContent, emailContent)
  }

  @EventListener
  fun on(event: DeviceUnresponsiveEvent) {
    val device = deviceStore.fetchOneById(event.deviceId)
    val deviceName =
        device.name ?: throw IllegalStateException("Device ${event.deviceId} has no name")
    val facilityId =
        device.facilityId ?: throw IllegalStateException("Device ${event.deviceId} has no facility")
    val facility = facilityStore.fetchOneById(facilityId)
    val organizationId =
        parentStore.getOrganizationId(facilityId) ?: throw FacilityNotFoundException(facilityId)

    val appContent =
        AppContent(
            notificationType = NotificationType.DeviceUnresponsive,
            localUrl = webAppUrls.facilityMonitoring(facilityId, device),
            renderMessage = { messages.deviceUnresponsive(deviceName) },
        )

    val emailContent =
        DeviceUnresponsive(
            config,
            device,
            facility,
            webAppUrls.fullFacilityMonitoring(organizationId, facilityId, device).toString(),
        )

    sendToOrganization(organizationId, appContent, emailContent)
  }

  /** Email-only: client-supplied alert, typically from the device manager. */
  @EventListener
  fun on(event: FacilityAlertRequestedEvent) {
    val requestedByUser = userStore.fetchOneById(event.requestedBy)
    requirePermissions(requestedByUser) { sendAlert(event.facilityId) }

    log.info("Alert for facility ${event.facilityId} requested by user ${requestedByUser.userId}")
    log.info("Alert subject: ${event.subject}")
    log.info("Alert body: ${event.body}")

    val facility = facilityStore.fetchOneById(event.facilityId)

    sendToFacility(
        event.facilityId,
        emailContent =
            FacilityAlertRequested(config, event.body, facility, requestedByUser, event.subject),
    )
  }

  @EventListener
  fun on(event: UserAddedToOrganizationEvent) {
    handleUserAddedToOrganization(
        event.addedBy,
        event.userId,
        event.organizationId,
        toTerraware = false,
    )
  }

  @EventListener
  fun on(event: UserAddedToTerrawareEvent) {
    handleUserAddedToOrganization(
        event.addedBy,
        event.userId,
        event.organizationId,
        toTerraware = true,
    )
  }

  private fun handleUserAddedToOrganization(
      addedBy: UserId,
      userId: UserId,
      organizationId: OrganizationId,
      toTerraware: Boolean,
  ) {
    // Users can be added to organizations by super-admins who don't otherwise have access to the
    // organizations, so this needs to run as the system user.
    systemUser.run {
      val admin =
          userStore.fetchOneById(addedBy) as? IndividualUser
              ?: throw IllegalArgumentException("Admin user must be an individual user")
      val user =
          userStore.fetchOneById(userId) as? IndividualUser
              ?: throw IllegalArgumentException("User must be an individual user")
      val organization = organizationStore.fetchOneById(organizationId)

      log.info(
          "Creating notification for user $userId being added to organization $organizationId."
      )

      val appContent =
          AppContent(
              notificationType = NotificationType.UserAddedToOrganization,
              localUrl = webAppUrls.organizationHome(organizationId),
              renderMessage = { messages.userAddedToOrganizationNotification(organization.name) },
          )

      val emailContent =
          if (toTerraware) {
            UserAddedToTerraware(
                config,
                admin,
                organization,
                webAppUrls.terrawareRegistrationUrl(organizationId, user.email).toString(),
            )
          } else {
            UserAddedToOrganization(
                config,
                admin,
                organization,
                webAppUrls.fullOrganizationHome(organizationId).toString(),
                user,
            )
          }

      sendToUser(user, appContent, emailContent, requireEmailOptIn = false)
    }
  }

  @EventListener
  fun on(event: AccessionDryingEndEvent) {
    log.info("Creating notifications for accession ${event.accessionNumber} ends drying.")
    val facilityId = parentStore.getFacilityId(event.accessionId)!!
    val organizationId =
        parentStore.getOrganizationId(event.accessionId)
            ?: throw AccessionNotFoundException(event.accessionId)
    val facilityName = parentStore.getFacilityName(event.accessionId)

    val appContent =
        AppContent(
            notificationType = NotificationType.AccessionScheduledToEndDrying,
            localUrl = webAppUrls.accession(event.accessionId),
            renderMessage = { messages.accessionDryingEndNotification(event.accessionNumber) },
        )

    val emailContent =
        AccessionDryingEnd(
            config,
            event.accessionNumber,
            facilityName,
            webAppUrls.fullAccession(event.accessionId, organizationId).toString(),
        )

    sendToFacility(facilityId, appContent, emailContent, batchEmail = true)
  }

  @EventListener
  fun on(event: NurserySeedlingBatchReadyEvent) {
    log.info("Creating notifications for batchId ${event.batchId.value} ready.")
    val facilityId = parentStore.getFacilityId(event.batchId)!!
    val organizationId = parentStore.getOrganizationId(facilityId)!!

    val appContent =
        AppContent(
            notificationType = NotificationType.NurserySeedlingBatchReady,
            localUrl = webAppUrls.batch(event.batchId, event.speciesId),
            renderMessage = {
              messages.nurserySeedlingBatchReadyNotification(event.batchNumber, event.nurseryName)
            },
        )

    val emailContent =
        NurserySeedlingBatchReady(
            config,
            event.batchNumber,
            webAppUrls.fullBatch(organizationId, event.batchId, event.speciesId).toString(),
            event.nurseryName,
        )

    sendToFacility(facilityId, appContent, emailContent, batchEmail = true)
  }

  @EventListener
  fun on(event: SeedFundReportCreatedEvent) {
    log.info("Creating notifications for report ${event.metadata.id} created.")

    val appContent =
        AppContent(
            notificationType = NotificationType.SeedFundReportCreated,
            localUrl = webAppUrls.seedFundReport(event.metadata.id),
            renderMessage = {
              messages.seedFundReportCreated(event.metadata.year, event.metadata.quarter)
            },
        )

    val emailContent =
        SeedFundReportCreated(
            config,
            event.metadata.year,
            event.metadata.quarter,
            webAppUrls
                .fullSeedFundReport(event.metadata.id, event.metadata.organizationId)
                .toString(),
        )

    sendToOrganization(
        event.metadata.organizationId,
        appContent,
        emailContent,
        roles = setOf(Role.Owner, Role.Admin),
    )
  }

  @EventListener
  fun on(event: ObservationStartedEvent) {
    log.info("Creating notifications for observation ${event.observation.id} started.")
    val plantingSite =
        plantingSiteStore.fetchSiteById(event.observation.plantingSiteId, PlantingSiteDepth.Site)

    val appContent =
        AppContent(
            notificationType = NotificationType.ObservationStarted,
            localUrl = webAppUrls.observations(plantingSite.organizationId, plantingSite.id),
            renderMessage = { messages.observationStarted() },
        )

    val emailContent =
        ObservationStarted(
            config,
            webAppUrls.fullObservations(plantingSite.organizationId, plantingSite.id).toString(),
        )

    sendToOrganization(plantingSite.organizationId, appContent, emailContent)
  }

  @EventListener
  fun on(event: ObservationUpcomingNotificationDueEvent) {
    log.info("Creating notifications for observation ${event.observation.id} upcoming.")
    val plantingSite =
        plantingSiteStore.fetchSiteById(event.observation.plantingSiteId, PlantingSiteDepth.Site)

    val appContent =
        AppContent(
            notificationType = NotificationType.ObservationUpcoming,
            localUrl = webAppUrls.observations(plantingSite.organizationId, plantingSite.id),
            renderMessage = {
              messages.observationUpcoming(plantingSite.name, event.observation.startDate)
            },
        )

    val emailContent =
        ObservationUpcoming(
            config,
            plantingSite.name,
            event.observation.startDate,
            webAppUrls.fullObservations(plantingSite.organizationId, plantingSite.id).toString(),
            webAppUrls.appStore.toString(),
            webAppUrls.googlePlay.toString(),
        )

    sendToOrganization(plantingSite.organizationId, appContent, emailContent)
  }

  @EventListener
  fun on(event: ScheduleObservationNotificationEvent) {
    log.info(
        "Creating notifications for scheduling observations in planting site ${event.plantingSiteId}"
    )
    val plantingSite = plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)
    val observationsFullUrl =
        webAppUrls.fullObservations(plantingSite.organizationId, plantingSite.id).toString()

    val appContent =
        AppContent(
            notificationType = NotificationType.ScheduleObservation,
            localUrl = webAppUrls.observations(plantingSite.organizationId, plantingSite.id),
            renderMessage = { messages.observationSchedule() },
        )

    val emailContent =
        ScheduleObservation(
            config,
            plantingSite.organizationId,
            plantingSite.id,
            plantingSite.name,
            observationsFullUrl,
        )

    sendToOrganization(
        plantingSite.organizationId,
        appContent,
        emailContent,
        roles = setOf(Role.Owner, Role.Admin),
    )
  }

  @EventListener
  fun on(event: ScheduleObservationReminderNotificationEvent) {
    log.info(
        "Creating notifications reminding to schedule observations in planting site ${event.plantingSiteId}"
    )
    val plantingSite = plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)
    val observationsFullUrl =
        webAppUrls.fullObservations(plantingSite.organizationId, plantingSite.id).toString()

    val appContent =
        AppContent(
            notificationType = NotificationType.ScheduleObservationReminder,
            localUrl = webAppUrls.observations(plantingSite.organizationId, plantingSite.id),
            renderMessage = { messages.observationScheduleReminder() },
        )

    val emailContent =
        ScheduleObservationReminder(
            config,
            plantingSite.organizationId,
            plantingSite.id,
            plantingSite.name,
            observationsFullUrl,
        )

    sendToOrganization(
        plantingSite.organizationId,
        appContent,
        emailContent,
        roles = setOf(Role.Owner, Role.Admin),
    )
  }

  @EventListener
  fun on(event: ObservationScheduledEvent) {
    systemUser.run {
      val (plantingSite, organization) = fetchSiteAndOrg(event.observation.plantingSiteId)
      sendToOrganization(
          plantingSite.organizationId,
          emailContent =
              ObservationScheduled(
                  config,
                  organization.name,
                  plantingSite.name,
                  event.observation.startDate,
                  event.observation.endDate,
              ),
          roles = setOf(Role.TerraformationContact),
          requireEmailOptIn = false,
      )
    }
  }

  @EventListener
  fun on(event: ObservationRescheduledEvent) {
    systemUser.run {
      val (plantingSite, organization) = fetchSiteAndOrg(event.originalObservation.plantingSiteId)
      sendToOrganization(
          plantingSite.organizationId,
          emailContent =
              ObservationRescheduled(
                  config,
                  organization.name,
                  plantingSite.name,
                  event.originalObservation.startDate,
                  event.originalObservation.endDate,
                  event.rescheduledObservation.startDate,
                  event.rescheduledObservation.endDate,
              ),
          roles = setOf(Role.TerraformationContact),
          requireEmailOptIn = false,
      )
    }
  }

  @EventListener
  fun on(event: ObservationNotScheduledNotificationEvent) {
    val (plantingSite, organization) = fetchSiteAndOrg(event.plantingSiteId)
    sendToOrganizationContact(
        organization,
        ObservationNotScheduled(config, organization.name, plantingSite.name),
    )
  }

  @EventListener
  fun on(event: ObservationNotStartedEvent) {
    val plantingSite = plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)
    sendToOrganization(
        plantingSite.organizationId,
        emailContent =
            ObservationNotStarted(config, plantingSite.name, webAppUrls.fullContactUs().toString()),
        roles = setOf(Role.Admin, Role.Owner),
    )
  }

  @EventListener
  fun on(event: ObservationPlotReplacedEvent) {
    val (plantingSite, organization) = fetchSiteAndOrg(event.observation.plantingSiteId)
    sendToOrganizationContact(
        organization,
        ObservationPlotReplaced(
            config,
            organization.name,
            plantingSite.name,
            event.justification,
            event.duration,
        ),
    )
  }

  @EventListener
  fun on(event: PlantingSeasonStartedEvent) {
    log.info(
        "Creating notifications for start of planting season ${event.plantingSeasonId} at site ${event.plantingSiteId}"
    )
    val plantingSite = plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)

    val appContent =
        AppContent(
            notificationType = NotificationType.PlantingSeasonStarted,
            localUrl = webAppUrls.nurseryInventory(),
            renderMessage = { messages.plantingSeasonStarted(plantingSite.name) },
        )

    val emailContent =
        PlantingSeasonStarted(
            config,
            plantingSite.name,
            webAppUrls.fullNurseryInventory(plantingSite.organizationId).toString(),
        )

    sendToOrganization(
        plantingSite.organizationId,
        appContent,
        emailContent,
        roles = setOf(Role.Owner, Role.Admin, Role.Manager),
    )
  }

  @EventListener
  fun on(event: PlantingSeasonNotScheduledNotificationEvent) {
    log.info(
        "Creating notifications for planting season not scheduled at site ${event.plantingSiteId}"
    )
    val plantingSite = plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)

    val appContent =
        AppContent(
            notificationType = NotificationType.SchedulePlantingSeason,
            localUrl = webAppUrls.plantingSite(event.plantingSiteId),
            renderMessage = { messages.plantingSeasonNotScheduled(event.notificationNumber) },
        )

    val emailContent =
        PlantingSeasonNotScheduled(
            config,
            plantingSite.name,
            webAppUrls.fullPlantingSite(plantingSite.organizationId, plantingSite.id).toString(),
            event.notificationNumber,
        )

    sendToOrganization(
        plantingSite.organizationId,
        appContent,
        emailContent,
        roles = setOf(Role.Owner, Role.Admin, Role.Manager),
    )
  }

  @EventListener
  fun on(event: PlantingSeasonRescheduledEvent) {
    val (plantingSite, organization) = fetchSiteAndOrg(event.plantingSiteId)
    sendToOrganizationContact(
        organization,
        PlantingSeasonRescheduled(
            config,
            organization.name,
            plantingSite.name,
            event.oldStartDate,
            event.oldEndDate,
            event.newStartDate,
            event.newEndDate,
        ),
        fallBackToSupport = false,
    )
  }

  @EventListener
  fun on(event: PlantingSeasonScheduledEvent) {
    val (plantingSite, organization) = fetchSiteAndOrg(event.plantingSiteId)
    sendToOrganizationContact(
        organization,
        PlantingSeasonScheduled(
            config,
            organization.name,
            plantingSite.name,
            event.startDate,
            event.endDate,
        ),
        fallBackToSupport = false,
    )
  }

  @EventListener
  fun on(event: PlantingSeasonNotScheduledSupportNotificationEvent) {
    val (plantingSite, organization) = fetchSiteAndOrg(event.plantingSiteId)
    sendToOrganizationContact(
        organization,
        PlantingSeasonNotScheduledSupport(config, organization.name, plantingSite.name),
    )
  }

  @EventListener
  fun on(event: PlantingSiteMapEditedEvent) {
    val organization =
        organizationStore.fetchOneById(event.plantingSiteEdit.existingModel.organizationId)

    sendToOrganizationContact(
        organization,
        PlantingSiteMapEdited(
            config = config,
            addedToOrRemovedFrom =
                if (event.plantingSiteEdit.areaHaDifference.signum() < 0) "removed from"
                else "added to",
            areaHaDifference = event.plantingSiteEdit.areaHaDifference.abs().toPlainString(),
            organizationName = organization.name,
            plantingSiteName = event.plantingSiteEdit.existingModel.name,
        ),
    )
  }

  @EventListener
  fun on(event: DeliverableReadyForReviewEvent) {
    // Runs as the system user because the recipient (TF contact) may not be a member of the
    // participant organization.
    systemUser.run {
      val (project, projectName) = fetchProjectWithDealName(event.projectId)
      if (project.phase == null) {
        // Individual deliverables in applications don't generate notifications.
        return@run
      }

      val deliverableCategory = deliverableStore.fetchDeliverableCategory(event.deliverableId)

      val appContent =
          AppContent(
              notificationType = NotificationType.DeliverableReadyForReview,
              localUrl =
                  webAppUrls.acceleratorConsoleDeliverable(event.deliverableId, event.projectId),
              renderMessage = { messages.deliverableReadyForReview(projectName) },
          )

      // Email additionally requires the deliverable submission row; skip email if missing.
      val submission =
          deliverableStore
              .fetchDeliverableSubmissions(
                  deliverableId = event.deliverableId,
                  projectId = event.projectId,
              )
              .firstOrNull()
      val emailContent =
          if (submission != null) {
            DeliverableReadyForReview(
                config = config,
                deliverableUrl =
                    webAppUrls
                        .fullAcceleratorConsoleDeliverable(event.deliverableId, event.projectId)
                        .toString(),
                deliverable = submission,
                projectName = projectName,
            )
          } else {
            log.error(
                "Got deliverable ready notification for deliverable ${event.deliverableId} in " +
                    "project ${event.projectId} but it has no submission"
            )
            null
          }

      log.info(
          "Creating notifications for project ${event.projectId} deliverable ${event.deliverableId} ready for review"
      )

      sendToAccelerator(
          project.organizationId,
          appContent,
          emailContent,
          deliverableCategory.internalInterestId,
      )
    }
  }

  @EventListener
  fun on(event: DeliverableStatusUpdatedEvent) {
    if (event.isUserVisible()) {
      notifyDeliverableStatusUpdated(event.projectId, event.deliverableId, includeEmail = true)
    }
  }

  @EventListener
  fun on(event: QuestionsDeliverableStatusUpdatedEvent) {
    notifyDeliverableStatusUpdated(event.projectId, event.deliverableId, includeEmail = false)
  }

  private fun notifyDeliverableStatusUpdated(
      projectId: ProjectId,
      deliverableId: DeliverableId,
      includeEmail: Boolean,
  ) {
    systemUser.run {
      val organizationId = parentStore.getOrganizationId(projectId)!!

      val appContent =
          AppContent(
              notificationType = NotificationType.DeliverableStatusUpdated,
              localUrl = webAppUrls.deliverable(deliverableId, projectId),
              renderMessage = { messages.deliverableStatusUpdated() },
          )

      val emailContent =
          if (includeEmail) {
            DeliverableStatusUpdated(
                config,
                webAppUrls.fullDeliverable(deliverableId, organizationId, projectId).toString(),
            )
          } else null

      log.info("Creating notifications for deliverable $deliverableId status updated")

      sendToOrganization(
          organizationId,
          appContent,
          emailContent,
          roles = setOf(Role.Owner, Role.Admin, Role.Manager),
      )
    }
  }

  @EventListener
  fun on(event: ParticipantProjectSpeciesAddedToProjectNotificationDueEvent) {
    handleParticipantProjectSpeciesEvent(
        projectId = event.projectId,
        deliverableId = event.deliverableId,
        speciesId = event.speciesId,
        appNotificationType = NotificationType.ParticipantProjectSpeciesAddedToProject,
        appMessageBuilder = { projectName, speciesName ->
          messages.participantProjectSpeciesAddedToProject(projectName, speciesName)
        },
        emailBuilder = { deliverableUrl, projectName, speciesName ->
          ParticipantProjectSpeciesAdded(
              config = config,
              deliverableUrl = deliverableUrl,
              projectName = projectName,
              speciesName = speciesName,
          )
        },
        logReason = "being added to project",
    )
  }

  @EventListener
  fun on(event: ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent) {
    handleParticipantProjectSpeciesEvent(
        projectId = event.projectId,
        deliverableId = event.deliverableId,
        speciesId = event.speciesId,
        appNotificationType = NotificationType.ParticipantProjectSpeciesApprovedSpeciesEdited,
        appMessageBuilder = { projectName, speciesName ->
          messages.participantProjectSpeciesApprovedSpeciesEdited(
              projectName = projectName,
              speciesName = speciesName,
          )
        },
        emailBuilder = { deliverableUrl, projectName, speciesName ->
          ParticipantProjectSpeciesEdited(
              config = config,
              deliverableUrl = deliverableUrl,
              projectName = projectName,
              speciesName = speciesName,
          )
        },
        logReason = "an approved participant project species being edited",
    )
  }

  private fun handleParticipantProjectSpeciesEvent(
      projectId: ProjectId,
      deliverableId: DeliverableId,
      speciesId: SpeciesId,
      appNotificationType: NotificationType,
      appMessageBuilder: (String, String) -> NotificationMessage,
      emailBuilder: (String, String, String) -> EmailTemplateModel,
      logReason: String,
  ) {
    systemUser.run {
      log.info(
          "Creating notifications for $logReason in deliverable $deliverableId in project $projectId"
      )

      val (project, projectName) = fetchProjectWithDealName(projectId)
      if (project.phase == null) {
        log.error(
            "Got participant project species notification for non-accelerator project $projectId"
        )
        return@run
      }

      val species = speciesStore.fetchSpeciesById(speciesId)
      val deliverableCategory = deliverableStore.fetchDeliverableCategory(deliverableId)

      val appContent =
          AppContent(
              notificationType = appNotificationType,
              localUrl = webAppUrls.acceleratorConsoleDeliverable(deliverableId, projectId),
              renderMessage = { appMessageBuilder(projectName, species.scientificName) },
          )

      val emailContent =
          emailBuilder(
              webAppUrls.fullAcceleratorConsoleDeliverable(deliverableId, projectId).toString(),
              projectName,
              species.scientificName,
          )

      sendToAccelerator(
          project.organizationId,
          appContent,
          emailContent,
          deliverableCategory.internalInterestId,
      )
    }
  }

  @EventListener
  fun on(event: ApplicationSubmittedEvent) {
    systemUser.run {
      val organizationId = parentStore.getOrganizationId(event.applicationId)
      if (organizationId == null) {
        log.error("Organization for application ${event.applicationId} not found")
        return@run
      }
      val organization = organizationStore.fetchOneById(organizationId)

      val appContent =
          AppContent(
              notificationType = NotificationType.ApplicationSubmitted,
              localUrl = webAppUrls.acceleratorConsoleApplication(event.applicationId),
              renderMessage = { messages.applicationSubmittedNotification(organization.name) },
          )

      val date = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).format(ISO_LOCAL_DATE)
      val emailContent =
          ApplicationSubmitted(
              config,
              webAppUrls.fullAcceleratorConsoleApplication(event.applicationId).toString(),
              organization.name,
              date,
          )

      sendToAccelerator(organizationId, appContent, emailContent, InternalInterest.Sourcing)
    }
  }

  @EventListener
  fun on(event: ModuleEventStartingEvent) {
    systemUser.run {
      val moduleEvent = moduleEventStore.fetchOneById(event.eventId)
      val module = moduleStore.fetchOneById(moduleEvent.moduleId)
      val renderMessage = {
        if (moduleEvent.eventType == EventType.RecordedSession) {
          messages.moduleRecordedSessionNotification(module.name)
        } else {
          messages.moduleEventStartingNotification(moduleEvent.eventType, module.name)
        }
      }
      moduleEvent.projects.forEach { projectId ->
        val organizationId = parentStore.getOrganizationId(projectId)!!
        val appContent =
            AppContent(
                notificationType = NotificationType.EventReminder,
                localUrl =
                    webAppUrls.moduleEvent(
                        moduleEvent.moduleId,
                        moduleEvent.id,
                        organizationId,
                        projectId,
                    ),
                renderMessage = renderMessage,
            )
        sendToOrganization(
            organizationId,
            appContent,
            roles = setOf(Role.Owner, Role.Admin, Role.Manager),
        )
      }
    }
  }

  @EventListener
  fun on(event: AcceleratorReportUpcomingEvent) {
    systemUser.run {
      val report = reportStore.fetchOne(event.reportId)
      val project = projectStore.fetchOneById(report.projectId)

      val appContent =
          AppContent(
              notificationType = NotificationType.AcceleratorReportUpcoming,
              localUrl = webAppUrls.acceleratorReport(event.reportId),
              renderMessage = { messages.acceleratorReportUpcoming(report.prefix) },
          )

      val emailContent =
          AcceleratorReportUpcoming(
              config,
              report.prefix,
              webAppUrls.fullAcceleratorReport(event.reportId, project.organizationId).toString(),
          )

      sendToOrganization(
          project.organizationId,
          appContent,
          emailContent,
          roles = setOf(Role.Owner, Role.Admin),
      )
    }
  }

  @EventListener
  fun on(event: RateLimitedAcceleratorReportSubmittedEvent) {
    systemUser.run {
      val report = reportStore.fetchOne(event.reportId)
      val project = projectStore.fetchOneById(report.projectId)
      val projectName = report.projectDealName ?: project.name

      val appContent =
          AppContent(
              notificationType = NotificationType.AcceleratorReportSubmitted,
              localUrl = webAppUrls.acceleratorConsoleReport(event.reportId, report.projectId),
              renderMessage = {
                messages.acceleratorReportSubmitted(
                    projectDealName = projectName,
                    reportPrefix = report.prefix,
                )
              },
          )

      val emailContent =
          AcceleratorReportSubmitted(
              config,
              projectName,
              report.prefix,
              webAppUrls.fullAcceleratorConsoleReport(event.reportId, report.projectId).toString(),
          )

      sendToAccelerator(project.organizationId, appContent, emailContent)
    }
  }

  @EventListener
  fun on(event: AcceleratorReportPublishedEvent) {
    systemUser.run {
      val report = reportStore.fetchOne(event.reportId)
      val project = projectStore.fetchOneById(report.projectId)
      val fundingEntityIds = parentStore.getFundingEntityIds(report.projectId)
      val projectName = report.projectDealName ?: project.name

      val appContent =
          AppContent(
              notificationType = NotificationType.AcceleratorReportPublished,
              localUrl = webAppUrls.funderReport(event.reportId),
              renderMessage = {
                messages.acceleratorReportPublished(
                    projectDealName = projectName,
                    reportPrefix = report.prefix,
                )
              },
          )

      val emailContent =
          AcceleratorReportPublished(
              config,
              projectName,
              report.prefix,
              webAppUrls.fullFunderReport(event.reportId).toString(),
          )

      fundingEntityIds.forEach { fundingEntityId ->
        sendToFundingEntity(fundingEntityId, appContent, emailContent)
      }
    }
  }

  @EventListener
  fun on(event: ActivityCreatedEvent) {
    systemUser.run {
      val activity = activityStore.fetchOneById(event.activityId)
      val (_, projectDealName) = fetchProjectWithDealName(activity.projectId)
      val createdByName = userStore.fetchFullNameById(activity.createdBy) ?: "?"

      val appContent =
          AppContent(
              notificationType = NotificationType.ActivityCreated,
              localUrl = webAppUrls.acceleratorConsoleActivity(event.activityId),
              renderMessage = {
                messages.activityCreated(
                    activityDate = activity.activityDate,
                    activityType = activity.activityType,
                    projectDealName = projectDealName,
                )
              },
          )

      val emailContent =
          ActivityCreated(
              config,
              activityDate = activity.activityDate,
              activityType = activity.activityType,
              createdByName = createdByName,
              detailsUrl = webAppUrls.fullAcceleratorConsoleActivity(event.activityId).toString(),
              projectDealName = projectDealName,
          )

      sendToProjectInternalUsers(
          activity.projectId,
          appContent,
          emailContent,
          role = ProjectInternalRole.ProjectLead,
      )
    }
  }

  @EventListener
  fun on(event: CompletedSectionVariableUpdatedEvent) {
    systemUser.run {
      val sectionOwnerUserId =
          variableOwnerStore.fetchOwner(event.projectId, event.sectionVariableId)
      if (sectionOwnerUserId != null) {
        val document = documentStore.fetchOneById(event.documentId)
        val (_, projectName) = fetchProjectWithDealName(event.projectId)
        val sectionVariable =
            variableStore.fetchOneVariable(event.sectionVariableId, document.variableManifestId)

        val owner =
            userStore.fetchOneById(sectionOwnerUserId) as? IndividualUser
                ?: throw IllegalArgumentException("Section owner must be an individual user")

        val appContent =
            AppContent(
                notificationType = NotificationType.CompletedSectionVariableUpdated,
                localUrl =
                    webAppUrls.document(event.documentId, event.referencingSectionVariableId),
                renderMessage = {
                  messages.completedSectionVariableUpdated(document.name, sectionVariable.name)
                },
            )

        val emailContent =
            CompletedSectionVariableUpdated(
                config = config,
                documentName = document.name,
                documentUrl =
                    webAppUrls
                        .fullDocument(event.documentId, event.referencingSectionVariableId)
                        .toString(),
                projectName = projectName,
                sectionName = sectionVariable.name,
            )

        sendToUser(owner, appContent, emailContent, requireEmailOptIn = false)
      }
    }
  }

  @EventListener
  fun on(event: SplatGenerationCompletedEvent) {
    log.info("Creating notifications for splat generation completed for file ${event.fileId}")

    val appContent =
        AppContent(
            notificationType = NotificationType.SplatGenerationCompleted,
            localUrl = webAppUrls.virtualWalkthroughs(),
            renderMessage = { messages.splatGenerationCompleted() },
        )

    val emailContent =
        SplatGenerationCompleted(
            config,
            webAppUrls.fullVirtualWalkthroughs(event.organizationId).toString(),
        )

    sendToOrganization(
        event.organizationId,
        appContent,
        emailContent,
        roles = setOf(Role.Admin, Role.Owner),
        additionalUserIds = setOf(event.uploadedByUserId),
    )
  }

  @EventListener
  fun on(event: SplatGenerationFailedEvent) {
    log.info("Creating notifications for splat generation failed for file ${event.fileId}")
    val uploadDate =
        splatUploadDate(event.organizationId, event.uploadedByUserId, event.videoUploadedTime)

    val appContent =
        AppContent(
            notificationType = NotificationType.SplatGenerationFailed,
            localUrl = webAppUrls.virtualWalkthroughs(),
            renderMessage = { messages.splatGenerationFailed(uploadDate.toString()) },
        )

    val emailContent =
        SplatGenerationFailed(
            config,
            uploadDate,
            webAppUrls.fullVirtualWalkthroughs(event.organizationId).toString(),
        )

    sendToOrganization(
        event.organizationId,
        appContent,
        emailContent,
        roles = setOf(Role.Admin, Role.Owner),
        additionalUserIds = setOf(event.uploadedByUserId),
    )
  }

  @EventListener
  fun on(event: SplatMarkedNeedsAttentionEvent) {
    log.info("Creating notifications for splat marked needs attention for file ${event.fileId}")
    val uploadDate =
        splatUploadDate(event.organizationId, event.uploadedByUserId, event.videoUploadedTime)
    val markedByEmail = systemUser.run { userStore.fetchOneById(event.markedByUserId).email }

    val appContent =
        AppContent(
            notificationType = NotificationType.SplatMarkedNeedsAttention,
            localUrl = webAppUrls.virtualWalkthroughs(),
            renderMessage = {
              messages.splatMarkedNeedsAttention(uploadDate.toString(), markedByEmail)
            },
        )

    val emailContent =
        SplatMarkedNeedsAttention(
            config,
            uploadDate,
            markedByEmail,
            webAppUrls.fullVirtualWalkthroughs(event.organizationId).toString(),
        )

    sendToOrganization(
        event.organizationId,
        appContent,
        emailContent,
        roles = setOf(Role.Admin, Role.Owner),
        additionalUserIds = setOf(event.uploadedByUserId),
    )
  }

  @EventListener
  fun on(event: FunderInvitedToFundingEntityEvent) {
    val funderPortalRegistrationUrl = webAppUrls.funderPortalRegistrationUrl(event.email).toString()
    val fundingEntity = systemUser.run { fundingEntityStore.fetchOneById(event.fundingEntityId) }

    emailService.sendLocaleEmails(
        FunderAddedToFundingEntity(
            config = config,
            fundingEntityName = fundingEntity.name,
            funderPortalRegistrationUrl = funderPortalRegistrationUrl,
        ),
        listOf(event.email),
    )
  }

  @EventListener
  fun on(event: RateLimitedMonitoringSpeciesTotalsEditedEvent) {
    systemUser.run {
      val organization = organizationStore.fetchOneById(event.organizationId)
      val plantingSite =
          plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Plot)
      val observations =
          event.changes
              .map { it.observationId }
              .distinct()
              .associateWith { observationStore.fetchObservationById(it) }

      val speciesNames =
          event.changes
              .mapNotNull { it.speciesId }
              .distinct()
              .associateWith { speciesStore.fetchSpeciesById(it).scientificName }
      val monitoringPlots =
          plantingSite.strata.flatMap { stratum ->
            stratum.substrata.flatMap { substratum -> substratum.monitoringPlots }
          }
      val plotNumbers = monitoringPlots.associate { it.id to it.plotNumber }

      val timeZone = plantingSite.timeZone ?: organization.timeZone ?: ZoneOffset.UTC

      val changes =
          event.changes.map { change ->
            val observation = observations[change.observationId]
            val observationDate =
                observation?.completedTime?.atZone(timeZone)?.toLocalDate() ?: observation?.endDate
            val observationName = observationDate?.format(ISO_LOCAL_DATE) ?: "Unknown"
            val speciesName =
                change.speciesName ?: change.speciesId?.let { speciesNames[it] } ?: "Unknown"

            MonitoringSpeciesTotalsEdited.Change(
                changedFrom = change.changedFrom,
                changedTo = change.changedTo,
                monitoringPlotId = change.monitoringPlotId,
                monitoringPlotNumber = plotNumbers[change.monitoringPlotId] ?: -1,
                observationId = change.observationId,
                observationName = observationName,
                plantStatus = change.plantStatus,
                speciesName = speciesName,
            )
          }

      val emailContent =
          MonitoringSpeciesTotalsEdited(
              config = config,
              changes = changes,
              observationsUrl =
                  webAppUrls
                      .fullObservations(event.organizationId, event.plantingSiteId)
                      .toString(),
              organizationName = organization.name,
              plantingSiteId = event.plantingSiteId,
              plantingSiteName = plantingSite.name,
          )

      sendToOrganization(
          event.organizationId,
          emailContent = emailContent,
          roles = setOf(Role.TerraformationContact),
          requireEmailOptIn = false,
      )
    }
  }

  @EventListener
  fun on(event: RateLimitedT0DataAssignedEvent) {
    if (
        (event.monitoringPlots == null ||
            event.monitoringPlots.all { it.speciesDensityChanges.isEmpty() }) &&
            (event.strata == null || event.strata.all { it.speciesDensityChanges.isEmpty() }) &&
            event.previousSiteTempSetting == event.newSiteTempSetting
    ) {
      // Changes were reversed before the event was eventually refired.
      return
    }

    systemUser.run {
      val organization = organizationStore.fetchOneById(event.organizationId)
      val plantingSite =
          plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)

      val emailContent =
          T0DataSet(
              config,
              organizationName = organization.name,
              monitoringPlots = event.monitoringPlots ?: emptyList(),
              newSiteTempSetting = event.newSiteTempSetting,
              plantingSiteId = event.plantingSiteId,
              plantingSiteName = plantingSite.name,
              strata = event.strata ?: emptyList(),
              previousSiteTempSetting = event.previousSiteTempSetting,
          )

      sendToOrganization(
          event.organizationId,
          emailContent = emailContent,
          roles = setOf(Role.TerraformationContact),
          requireEmailOptIn = false,
      )
    }
  }

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: NotificationJobStartedEvent) {
    pendingEmails.remove()
  }

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: NotificationJobSucceededEvent) {
    pendingEmails.get().forEach { request ->
      try {
        emailService.sendUserNotification(request.user, request.emailTemplateModel)
      } catch (e: Exception) {
        log.error(
            "Error sending email ${request.emailTemplateModel.templateDir} to user ${request.user.email}",
            e,
        )
      }
    }
    pendingEmails.remove()
  }

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: NotificationJobFinishedEvent) {
    pendingEmails.remove()
  }

  private fun sendToFacility(
      facilityId: FacilityId,
      appContent: AppContent? = null,
      emailContent: EmailTemplateModel? = null,
      batchEmail: Boolean = false,
  ) {
    val organizationId = parentStore.getOrganizationId(facilityId)!!
    sendToOrganization(organizationId, appContent, emailContent, batchEmail = batchEmail)
  }

  /**
   * App recipients include everyone in the org with matching roles (or all members if roles is
   * null), ignoring email opt-in. Email recipients exclude the Terraformation Contact when roles is
   * null — they use [EmailService.defaultOrgRolesForNotification] instead — and respect
   * [requireEmailOptIn] both at fetch time and at send time.
   */
  private fun sendToOrganization(
      organizationId: OrganizationId,
      appContent: AppContent? = null,
      emailContent: EmailTemplateModel? = null,
      roles: Set<Role>? = null,
      additionalUserIds: Set<UserId> = emptySet(),
      requireEmailOptIn: Boolean = true,
      batchEmail: Boolean = false,
  ) {
    systemUser.run {
      if (appContent != null) {
        val recipients =
            fetchOrgRecipients(organizationId, roles, additionalUserIds, requireOptIn = false)
        dispatchApp(recipients, appContent, appOrgId = organizationId)
      }
      if (emailContent != null) {
        val emailRoles = roles ?: EmailService.defaultOrgRolesForNotification
        val recipients =
            fetchOrgRecipients(
                organizationId,
                emailRoles,
                additionalUserIds,
                requireOptIn = requireEmailOptIn,
            )
        dispatchEmail(recipients, emailContent, requireEmailOptIn, batchEmail)
      }
    }
  }

  private fun fetchOrgRecipients(
      organizationId: OrganizationId,
      roles: Set<Role>?,
      additionalUserIds: Set<UserId>,
      requireOptIn: Boolean,
  ): List<IndividualUser> {
    val roleRecipients = userStore.fetchByOrganizationId(organizationId, requireOptIn, roles)

    val additionalRecipients =
        if (additionalUserIds.isEmpty()) {
          emptyList()
        } else {
          val roleUserIds = roleRecipients.map { it.userId }.toSet()
          if (additionalUserIds.all { it in roleUserIds }) {
            emptyList()
          } else {
            userStore
                .fetchManyById(additionalUserIds - roleUserIds)
                .filterIsInstance<IndividualUser>()
          }
        }

    return roleRecipients + additionalRecipients
  }

  private fun sendToAccelerator(
      organizationId: OrganizationId,
      appContent: AppContent? = null,
      emailContent: EmailTemplateModel? = null,
      internalInterest: InternalInterest? = null,
  ) {
    systemUser.run {
      val internalInterestCondition = internalInterest?.let {
        userInternalInterestsStore.conditionForUsers(it)
      }

      val recipients =
          userStore
              .fetchWithGlobalRoles(setOf(GlobalRole.TFExpert), internalInterestCondition)
              .toMutableSet()

      // The TF contacts will not have access to the accelerator console, so this notification
      // gives them an opportunity to acquire global roles. Included regardless of interests.
      val tfContacts = userStore.getTerraformationContactUsers(organizationId)
      tfContacts.forEach { recipients.add(it) }

      // Accelerator notifications are not scoped to a specific org permission.
      appContent?.let { dispatchApp(recipients, it, appOrgId = null) }
      emailContent?.let { dispatchEmail(recipients, it, requireOptIn = false) }
    }
  }

  private fun sendToProjectInternalUsers(
      projectId: ProjectId,
      appContent: AppContent? = null,
      emailContent: EmailTemplateModel? = null,
      role: ProjectInternalRole,
  ) {
    systemUser.run {
      val internalUserIds =
          projectStore.fetchInternalUsers(projectId, role).mapNotNull { it.userId }
      val recipients = userStore.fetchManyById(internalUserIds)

      appContent?.let { dispatchApp(recipients, it, appOrgId = null) }
      emailContent?.let { dispatchEmail(recipients, it, requireOptIn = false) }
    }
  }

  private fun sendToFundingEntity(
      fundingEntityId: FundingEntityId,
      appContent: AppContent? = null,
      emailContent: EmailTemplateModel? = null,
  ) {
    systemUser.run {
      val recipients = userStore.fetchByFundingEntityId(fundingEntityId)

      appContent?.let { dispatchApp(recipients, it, appOrgId = null) }
      emailContent?.let { dispatchEmail(recipients, it, requireOptIn = true) }
    }
  }

  /**
   * Inserts one app notification per recipient, inside a DB transaction so a partial failure rolls
   * back the rest.
   */
  private fun dispatchApp(
      recipients: Collection<TerrawareUser>,
      appContent: AppContent,
      appOrgId: OrganizationId?,
  ) {
    dslContext.transaction { _ ->
      recipients.forEach { user -> insertAppNotification(appContent, user, appOrgId) }
    }
  }

  /** Sends or (if [batchEmail]) queues an email per recipient. */
  private fun dispatchEmail(
      recipients: Collection<TerrawareUser>,
      emailContent: EmailTemplateModel,
      requireOptIn: Boolean,
      batchEmail: Boolean = false,
  ) {
    recipients.forEach { user ->
      if (batchEmail) {
        pendingEmails.get().add(EmailRequest(user, emailContent))
      } else {
        emailService.sendUserNotification(user, emailContent, requireOptIn)
      }
    }
  }

  private fun fetchSiteAndOrg(
      plantingSiteId: PlantingSiteId,
  ): Pair<ExistingPlantingSiteModel, OrganizationModel> {
    val plantingSite = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site)
    val organization =
        organizationStore.fetchOneById(
            plantingSite.organizationId,
            OrganizationStore.FetchDepth.Organization,
        )
    return plantingSite to organization
  }

  /**
   * Fetches a project and its display name (the accelerator deal name, falling back to the plain
   * project name).
   */
  private fun fetchProjectWithDealName(
      projectId: ProjectId,
  ): Pair<ExistingProjectModel, String> {
    val project = projectStore.fetchOneById(projectId)
    val dealName = projectAcceleratorDetailsService.fetchOneById(projectId).dealName ?: project.name
    return project to dealName
  }

  /**
   * Resolves the "upload date" used in splat notifications, interpreting the video upload timestamp
   * in the organization's time zone (falling back to the uploader's, then UTC).
   */
  private fun splatUploadDate(
      organizationId: OrganizationId,
      uploadedByUserId: UserId,
      videoUploadedTime: Instant,
  ): LocalDate {
    val organization = systemUser.run {
      organizationStore.fetchOneById(organizationId, OrganizationStore.FetchDepth.Organization)
    }
    val uploaderTimeZone = systemUser.run {
      (userStore.fetchOneById(uploadedByUserId) as? IndividualUser)?.timeZone
    }
    val timeZone = organization.timeZone ?: uploaderTimeZone ?: ZoneOffset.UTC
    return videoUploadedTime.atZone(timeZone).toLocalDate()
  }

  private fun sendToUser(
      user: TerrawareUser,
      appContent: AppContent? = null,
      emailContent: EmailTemplateModel? = null,
      organizationId: OrganizationId? = null,
      requireEmailOptIn: Boolean = true,
  ) {
    if (appContent != null) {
      insertAppNotification(appContent, user, organizationId)
    }
    if (emailContent != null) {
      emailService.sendUserNotification(user, emailContent, requireEmailOptIn)
    }
  }

  /**
   * Sends an email to the organization's Terraformation Contact. If there is none and the
   * organization has any accelerator or application projects, falls back to the support address and
   * also sends a [MissingContact] alert. Email-only.
   */
  private fun sendToOrganizationContact(
      organization: OrganizationModel,
      emailContent: EmailTemplateModel,
      fallBackToSupport: Boolean = true,
  ) {
    val users = userStore.getTerraformationContactUsers(organization.id)
    if (users.isNotEmpty()) {
      users.forEach { emailService.sendUserNotification(it, emailContent, requireOptIn = false) }
    } else if (
        fallBackToSupport && parentStore.hasAcceleratorOrApplicationProjects(organization.id)
    ) {
      emailService.sendSupportNotification(emailContent)
      emailService.sendSupportNotification(
          MissingContact(config, organization.id, organization.name)
      )
    } else {
      log.info("Organization ${organization.id} has no contact, so not sending notification")
    }
  }

  private fun insertAppNotification(
      appContent: AppContent,
      user: TerrawareUser,
      organizationId: OrganizationId?,
  ) {
    val locale = user.locale ?: Locale.ENGLISH
    val message = locale.use { appContent.renderMessage() }
    val notification =
        CreateNotificationModel(
            appContent.notificationType,
            user.userId,
            organizationId,
            message.title,
            message.body,
            appContent.localUrl,
        )
    notificationStore.create(notification)
  }
}
