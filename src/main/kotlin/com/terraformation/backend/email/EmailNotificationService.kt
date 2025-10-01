package com.terraformation.backend.email

import com.terraformation.backend.accelerator.ProjectAcceleratorDetailsService
import com.terraformation.backend.accelerator.db.ActivityStore
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ParticipantStore
import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.accelerator.event.AcceleratorReportPublishedEvent
import com.terraformation.backend.accelerator.event.AcceleratorReportUpcomingEvent
import com.terraformation.backend.accelerator.event.ActivityCreatedEvent
import com.terraformation.backend.accelerator.event.ApplicationSubmittedEvent
import com.terraformation.backend.accelerator.event.DeliverableReadyForReviewEvent
import com.terraformation.backend.accelerator.event.DeliverableStatusUpdatedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectAddedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectRemovedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedToProjectNotificationDueEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent
import com.terraformation.backend.accelerator.event.RateLimitedAcceleratorReportSubmittedEvent
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserInternalInterestsStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.FacilityAlertRequestedEvent
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.event.UserAddedToTerrawareEvent
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.daily.NotificationJobFinishedEvent
import com.terraformation.backend.daily.NotificationJobStartedEvent
import com.terraformation.backend.daily.NotificationJobSucceededEvent
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.accelerator.InternalInterest
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ProjectInternalRole
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.event.DeviceUnresponsiveEvent
import com.terraformation.backend.device.event.SensorBoundsAlertTriggeredEvent
import com.terraformation.backend.device.event.UnknownAutomationTriggeredEvent
import com.terraformation.backend.documentproducer.db.DocumentStore
import com.terraformation.backend.documentproducer.db.VariableOwnerStore
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.event.CompletedSectionVariableUpdatedEvent
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
import com.terraformation.backend.email.model.NurserySeedlingBatchReady
import com.terraformation.backend.email.model.ObservationNotScheduled
import com.terraformation.backend.email.model.ObservationNotStarted
import com.terraformation.backend.email.model.ObservationPlotReplaced
import com.terraformation.backend.email.model.ObservationRescheduled
import com.terraformation.backend.email.model.ObservationScheduled
import com.terraformation.backend.email.model.ObservationStarted
import com.terraformation.backend.email.model.ObservationUpcoming
import com.terraformation.backend.email.model.ParticipantProjectAdded
import com.terraformation.backend.email.model.ParticipantProjectRemoved
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
import com.terraformation.backend.email.model.T0DataSet
import com.terraformation.backend.email.model.UnknownAutomationTriggered
import com.terraformation.backend.email.model.UserAddedToOrganization
import com.terraformation.backend.email.model.UserAddedToTerraware
import com.terraformation.backend.funder.db.FundingEntityStore
import com.terraformation.backend.funder.event.FunderInvitedToFundingEntityEvent
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.event.NurserySeedlingBatchReadyEvent
import com.terraformation.backend.report.event.SeedFundReportCreatedEvent
import com.terraformation.backend.seedbank.event.AccessionDryingEndEvent
import com.terraformation.backend.species.db.SpeciesStore
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
import com.terraformation.backend.tracking.event.RateLimitedT0DataAssignedEvent
import com.terraformation.backend.tracking.event.ScheduleObservationNotificationEvent
import com.terraformation.backend.tracking.event.ScheduleObservationReminderNotificationEvent
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import jakarta.inject.Named
import java.time.InstantSource
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
import org.springframework.context.event.EventListener

@Named
class EmailNotificationService(
    private val activityStore: ActivityStore,
    private val automationStore: AutomationStore,
    private val clock: InstantSource,
    private val config: TerrawareServerConfig,
    private val deliverableStore: DeliverableStore,
    private val deviceStore: DeviceStore,
    private val documentStore: DocumentStore,
    private val emailService: EmailService,
    private val facilityStore: FacilityStore,
    private val fundingEntityStore: FundingEntityStore,
    private val organizationStore: OrganizationStore,
    private val parentStore: ParentStore,
    private val participantStore: ParticipantStore,
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
        FacilityAlertRequested(config, event.body, facility, requestedByUser, event.subject),
    )
  }

  @EventListener
  fun on(event: FacilityIdleEvent) {
    val facility = facilityStore.fetchOneById(event.facilityId)

    val facilityMonitoringUrl =
        webAppUrls
            .fullFacilityMonitoring(
                parentStore.getOrganizationId(event.facilityId)!!,
                event.facilityId,
            )
            .toString()
    emailService.sendFacilityNotification(
        facility.id,
        FacilityIdle(config, facility, facilityMonitoringUrl),
    )
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
        SensorBoundsAlert(config, automation, device, facility, event.value, facilityMonitoringUrl),
    )
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
            config,
            automation,
            facility,
            event.message,
            facilityMonitoringUrl,
        ),
    )
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
        facilityId,
        DeviceUnresponsive(config, device, facility, facilityMonitoringUrl),
    )
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
        UserAddedToOrganization(config, admin, organization, organizationHomeUrl, user),
        requireOptIn = false,
    )
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
        requireOptIn = false,
    )
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
                  AccessionDryingEnd(config, event.accessionNumber, facilityName, accessionUrl),
              )
          )
    }
  }

  @EventListener
  fun on(event: NurserySeedlingBatchReadyEvent) {
    val facilityId = parentStore.getFacilityId(event.batchId)!!
    val organizationId = parentStore.getOrganizationId(facilityId)!!
    val batchUrl = webAppUrls.fullBatch(organizationId, event.batchId, event.speciesId).toString()

    log.info("Creating email notifications for batchId ${event.batchId.value} ready.")
    getRecipients(facilityId).forEach { user ->
      pendingEmails
          .get()
          .add(
              EmailRequest(
                  user,
                  NurserySeedlingBatchReady(config, event.batchNumber, batchUrl, event.nurseryName),
              )
          )
    }
  }

  @EventListener
  fun on(event: SeedFundReportCreatedEvent) {
    val reportUrl =
        webAppUrls.fullSeedFundReport(event.metadata.id, event.metadata.organizationId).toString()

    emailService.sendOrganizationNotification(
        event.metadata.organizationId,
        SeedFundReportCreated(config, event.metadata.year, event.metadata.quarter, reportUrl),
        roles = setOf(Role.Owner, Role.Admin),
    )
  }

  @EventListener
  fun on(event: ObservationStartedEvent) {
    val plantingSite =
        plantingSiteStore.fetchSiteById(event.observation.plantingSiteId, PlantingSiteDepth.Site)
    val observationsUrl =
        webAppUrls.fullObservations(plantingSite.organizationId, plantingSite.id).toString()

    emailService.sendOrganizationNotification(
        plantingSite.organizationId,
        ObservationStarted(config, observationsUrl),
    )
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
            webAppUrls.googlePlay.toString(),
        ),
    )
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
        setOf(Role.TerraformationContact),
    )
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
        setOf(Role.TerraformationContact),
    )
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
        roles = setOf(Role.Admin, Role.Owner),
    )
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
        roles = setOf(Role.Admin, Role.Owner),
    )
  }

  @EventListener
  fun on(event: ObservationNotScheduledNotificationEvent) {
    val plantingSite = plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)
    val organization =
        organizationStore.fetchOneById(
            plantingSite.organizationId,
            OrganizationStore.FetchDepth.Organization,
        )
    val model = ObservationNotScheduled(config, organization.name, plantingSite.name)

    sendToOrganizationContact(organization, model)
  }

  @EventListener
  fun on(event: ObservationNotStartedEvent) {
    val plantingSite = plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)

    emailService.sendOrganizationNotification(
        plantingSite.organizationId,
        ObservationNotStarted(config, plantingSite.name, webAppUrls.fullContactUs().toString()),
        roles = setOf(Role.Admin, Role.Owner),
    )
  }

  @EventListener
  fun on(event: ObservationPlotReplacedEvent) {
    val plantingSite =
        plantingSiteStore.fetchSiteById(event.observation.plantingSiteId, PlantingSiteDepth.Site)
    val organization =
        organizationStore.fetchOneById(
            plantingSite.organizationId,
            OrganizationStore.FetchDepth.Organization,
        )
    val model =
        ObservationPlotReplaced(
            config,
            organization.name,
            plantingSite.name,
            event.justification,
            event.duration,
        )

    sendToOrganizationContact(organization, model)
  }

  @EventListener
  fun on(event: PlantingSeasonRescheduledEvent) {
    val plantingSite = plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)
    val organization =
        organizationStore.fetchOneById(
            plantingSite.organizationId,
            OrganizationStore.FetchDepth.Organization,
        )
    val model =
        PlantingSeasonRescheduled(
            config,
            organization.name,
            plantingSite.name,
            event.oldStartDate,
            event.oldEndDate,
            event.newStartDate,
            event.newEndDate,
        )

    sendToOrganizationContact(organization, model, fallBackToSupport = false)
  }

  @EventListener
  fun on(event: PlantingSeasonScheduledEvent) {
    val plantingSite = plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)
    val organization =
        organizationStore.fetchOneById(
            plantingSite.organizationId,
            OrganizationStore.FetchDepth.Organization,
        )
    val model =
        PlantingSeasonScheduled(
            config,
            organization.name,
            plantingSite.name,
            event.startDate,
            event.endDate,
        )

    sendToOrganizationContact(organization, model, fallBackToSupport = false)
  }

  @EventListener
  fun on(event: PlantingSeasonStartedEvent) {
    val plantingSite = plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)
    val model =
        PlantingSeasonStarted(
            config,
            plantingSite.name,
            webAppUrls.fullNurseryInventory(plantingSite.organizationId).toString(),
        )

    emailService.sendOrganizationNotification(
        plantingSite.organizationId,
        model,
        roles = setOf(Role.Owner, Role.Admin, Role.Manager),
    )
  }

  @EventListener
  fun on(event: PlantingSeasonNotScheduledNotificationEvent) {
    val plantingSite = plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)
    val model =
        PlantingSeasonNotScheduled(
            config,
            plantingSite.name,
            webAppUrls.fullPlantingSite(plantingSite.organizationId, plantingSite.id).toString(),
            event.notificationNumber,
        )

    emailService.sendOrganizationNotification(
        plantingSite.organizationId,
        model,
        roles = setOf(Role.Owner, Role.Admin, Role.Manager),
    )
  }

  @EventListener
  fun on(event: PlantingSeasonNotScheduledSupportNotificationEvent) {
    val plantingSite = plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)
    val organization =
        organizationStore.fetchOneById(
            plantingSite.organizationId,
            OrganizationStore.FetchDepth.Organization,
        )
    val model = PlantingSeasonNotScheduledSupport(config, organization.name, plantingSite.name)

    sendToOrganizationContact(organization, model)
  }

  @EventListener
  fun on(event: ParticipantProjectAddedEvent) {
    val participant = participantStore.fetchOneById(event.participantId)
    val project = projectStore.fetchOneById(event.projectId)
    val organization = organizationStore.fetchOneById(project.organizationId)
    val admin =
        userStore.fetchOneById(event.addedBy) as? IndividualUser
            ?: throw IllegalArgumentException("Admin user must be an individual user")
    val model =
        ParticipantProjectAdded(
            config,
            admin.fullName ?: admin.email,
            organization.name,
            participant.name,
            project.name,
        )

    sendToOrganizationContact(organization, model)
  }

  @EventListener
  fun on(event: ParticipantProjectRemovedEvent) {
    val participant = participantStore.fetchOneById(event.participantId)
    val project = projectStore.fetchOneById(event.projectId)
    val organization = organizationStore.fetchOneById(project.organizationId)
    val admin =
        userStore.fetchOneById(event.removedBy) as? IndividualUser
            ?: throw IllegalArgumentException("Admin user must be an individual user")
    val model =
        ParticipantProjectRemoved(
            config,
            admin.fullName ?: admin.email,
            organization.name,
            participant.name,
            project.name,
        )

    sendToOrganizationContact(organization, model)
  }

  @EventListener
  fun on(event: ParticipantProjectSpeciesAddedToProjectNotificationDueEvent) {
    val project = projectStore.fetchOneById(event.projectId)
    val participant = participantStore.fetchOneById(project.participantId!!)
    val species = speciesStore.fetchSpeciesById(event.speciesId)
    val deliverableCategory = deliverableStore.fetchDeliverableCategory(event.deliverableId)

    sendToAccelerator(
        project.organizationId,
        ParticipantProjectSpeciesAdded(
            config,
            webAppUrls
                .fullAcceleratorConsoleDeliverable(event.deliverableId, event.projectId)
                .toString(),
            participant.name,
            project.name,
            species.scientificName,
        ),
        deliverableCategory.internalInterestId,
    )
  }

  @EventListener
  fun on(event: ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent) {
    val project = projectStore.fetchOneById(event.projectId)
    val participant = participantStore.fetchOneById(project.participantId!!)
    val species = speciesStore.fetchSpeciesById(event.speciesId)
    val deliverableCategory = deliverableStore.fetchDeliverableCategory(event.deliverableId)

    sendToAccelerator(
        project.organizationId,
        ParticipantProjectSpeciesEdited(
            config,
            webAppUrls
                .fullAcceleratorConsoleDeliverable(event.deliverableId, event.projectId)
                .toString(),
            participant.name,
            species.scientificName,
        ),
        deliverableCategory.internalInterestId,
    )
  }

  @EventListener
  fun on(event: ApplicationSubmittedEvent) {
    val organizationId = parentStore.getOrganizationId(event.applicationId)
    if (organizationId == null) {
      log.error("Organization for application ${event.applicationId} not found")
      return
    }
    val organization = organizationStore.fetchOneById(organizationId)
    val date = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).format(ISO_LOCAL_DATE)
    sendToAccelerator(
        organizationId,
        ApplicationSubmitted(
            config,
            webAppUrls.fullAcceleratorConsoleApplication(event.applicationId).toString(),
            organization.name,
            date,
        ),
        InternalInterest.Sourcing,
    )
  }

  @EventListener
  fun on(event: DeliverableReadyForReviewEvent) {
    systemUser.run {
      val project = projectStore.fetchOneById(event.projectId)
      if (project.participantId == null) {
        // We don't send notifications about individual deliverables in applications.
        return@run
      }

      val deliverableSubmission =
          deliverableStore
              .fetchDeliverableSubmissions(
                  deliverableId = event.deliverableId,
                  projectId = event.projectId,
              )
              .firstOrNull()
      if (deliverableSubmission == null) {
        log.error(
            "Got deliverable ready notification for deliverable ${event.deliverableId} in " +
                "project ${event.projectId} but it has no submission"
        )
        return@run
      }

      val deliverableCategory = deliverableStore.fetchDeliverableCategory(event.deliverableId)
      val participant = participantStore.fetchOneById(project.participantId)

      sendToAccelerator(
          project.organizationId,
          DeliverableReadyForReview(
              config,
              webAppUrls
                  .fullAcceleratorConsoleDeliverable(event.deliverableId, event.projectId)
                  .toString(),
              deliverableSubmission,
              participant.name,
          ),
          deliverableCategory.internalInterestId,
      )
    }
  }

  @EventListener
  fun on(event: DeliverableStatusUpdatedEvent) {
    if (event.isUserVisible()) {
      val organizationId = parentStore.getOrganizationId(event.projectId)!!
      emailService.sendOrganizationNotification(
          organizationId,
          DeliverableStatusUpdated(
              config,
              webAppUrls
                  .fullDeliverable(event.deliverableId, organizationId, event.projectId)
                  .toString(),
          ),
          roles = setOf(Role.Admin, Role.Manager, Role.Owner),
      )
    }
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
  fun on(event: CompletedSectionVariableUpdatedEvent) {
    systemUser.run {
      val sectionOwnerUserId =
          variableOwnerStore.fetchOwner(event.projectId, event.sectionVariableId)

      if (sectionOwnerUserId != null) {
        val document = documentStore.fetchOneById(event.documentId)
        val project = projectStore.fetchOneById(event.projectId)
        val sectionVariable =
            variableStore.fetchOneVariable(event.sectionVariableId, document.variableManifestId)

        val user =
            userStore.fetchOneById(sectionOwnerUserId) as? IndividualUser
                ?: throw IllegalArgumentException("Section owner must be an individual user")
        emailService.sendUserNotification(
            user,
            CompletedSectionVariableUpdated(
                config = config,
                documentName = document.name,
                documentUrl =
                    webAppUrls
                        .fullDocument(event.documentId, event.referencingSectionVariableId)
                        .toString(),
                projectName = project.name,
                sectionName = sectionVariable.name,
            ),
            false,
        )
      }
    }
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
  fun on(event: RateLimitedAcceleratorReportSubmittedEvent) {
    systemUser.run {
      val report = reportStore.fetchOne(event.reportId)
      val project = projectStore.fetchOneById(report.projectId)

      sendToAccelerator(
          project.organizationId,
          AcceleratorReportSubmitted(
              config,
              report.projectDealName ?: project.name,
              report.prefix,
              webAppUrls.fullAcceleratorConsoleReport(event.reportId, report.projectId).toString(),
          ),
      )
    }
  }

  @EventListener
  fun on(event: AcceleratorReportUpcomingEvent) {
    systemUser.run {
      val report = reportStore.fetchOne(event.reportId)
      val project = projectStore.fetchOneById(report.projectId)

      emailService.sendOrganizationNotification(
          project.organizationId,
          AcceleratorReportUpcoming(
              config,
              report.prefix,
              webAppUrls.fullAcceleratorReport(event.reportId, project.organizationId).toString(),
          ),
          roles = setOf(Role.Owner, Role.Admin),
      )
    }
  }

  @EventListener
  fun on(event: AcceleratorReportPublishedEvent) {
    systemUser.run {
      val report = reportStore.fetchOne(event.reportId)
      val project = projectStore.fetchOneById(report.projectId)
      val fundingEntityIds = parentStore.getFundingEntityIds(report.projectId)

      fundingEntityIds.forEach { fundingEntityId ->
        emailService.sendFundingEntityNotification(
            fundingEntityId,
            AcceleratorReportPublished(
                config,
                report.projectDealName ?: project.name,
                report.prefix,
                webAppUrls.fullFunderReport(event.reportId).toString(),
            ),
        )
      }
    }
  }

  @EventListener
  fun on(event: RateLimitedT0DataAssignedEvent) {
    if (
        (event.monitoringPlots == null ||
            event.monitoringPlots.all { it.speciesDensityChanges.isEmpty() }) &&
            (event.plantingZones == null ||
                event.plantingZones.all { it.speciesDensityChanges.isEmpty() }) &&
            event.previousSiteTempSetting == event.newSiteTempSetting
    ) {
      // changes were reversed before the event was eventually refired
      return
    }

    systemUser.run {
      val organization = organizationStore.fetchOneById(event.organizationId)
      val plantingSite =
          plantingSiteStore.fetchSiteById(event.plantingSiteId, PlantingSiteDepth.Site)

      val model =
          T0DataSet(
              config,
              organizationName = organization.name,
              monitoringPlots = event.monitoringPlots ?: emptyList(),
              newSiteTempSetting = event.newSiteTempSetting,
              plantingSiteId = event.plantingSiteId,
              plantingSiteName = plantingSite.name,
              plantingZones = event.plantingZones ?: emptyList(),
              previousSiteTempSetting = event.previousSiteTempSetting,
          )
      emailService.sendOrganizationNotification(
          event.organizationId,
          model,
          false,
          setOf(Role.TerraformationContact),
      )
    }
  }

  @EventListener
  fun on(event: ActivityCreatedEvent) {
    systemUser.run {
      val activity = activityStore.fetchOneById(event.activityId)
      val acceleratorDetails = projectAcceleratorDetailsService.fetchOneById(activity.projectId)
      val project = projectStore.fetchOneById(activity.projectId)
      val createdByName = userStore.fetchFullNameById(activity.createdBy) ?: "?"

      val model =
          ActivityCreated(
              config,
              activityDate = activity.activityDate,
              activityType = activity.activityType,
              createdByName = createdByName,
              detailsUrl =
                  webAppUrls
                      .fullAcceleratorConsoleActivity(event.activityId, activity.projectId)
                      .toString(),
              projectDealName = acceleratorDetails.dealName ?: project.name,
          )

      sendToProjectInternalUsers(activity.projectId, model, ProjectInternalRole.ProjectLead)
    }
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
        organizationId,
        roles = EmailService.defaultOrgRolesForNotification,
    )
  }

  /**
   * Sends an email notification to the Terraformation Contact if there is one for the organization,
   * otherwise sends the notification to Terrformation Support and generates an additional
   * notification about the organization not having a contact.
   */
  private fun sendToOrganizationContact(
      organization: OrganizationModel,
      model: EmailTemplateModel,
      fallBackToSupport: Boolean = true,
  ) {
    val users = userStore.getTerraformationContactUsers(organization.id)
    if (users.isNotEmpty()) {
      users.forEach { emailService.sendUserNotification(it, model, false) }
    } else if (fallBackToSupport && InternalTagIds.Accelerator in organization.internalTags) {
      emailService.sendSupportNotification(model)
      emailService.sendSupportNotification(
          MissingContact(config, organization.id, organization.name)
      )
    } else {
      log.info("Organization ${organization.id} has no contact, so not sending notification")
    }
  }

  /** Sends an email notification to project internal users with a specific role. */
  private fun sendToProjectInternalUsers(
      projectId: ProjectId,
      model: EmailTemplateModel,
      role: ProjectInternalRole,
  ) {
    systemUser.run {
      val internalUserIds =
          projectStore.fetchInternalUsers(projectId, role).mapNotNull { it.userId }
      val recipients = userStore.fetchManyById(internalUserIds)

      recipients.forEach { user -> emailService.sendUserNotification(user, model, false) }
    }
  }

  /**
   * Sends an email notification to the accelerator team and an organization's Contact. Does not
   * require user opt-in. If the notification relates to a deliverable category, only sends email to
   * TF Expert users who either have no deliverable categories assigned to them or have the category
   * in question. The organization's Contact is always included regardless of their deliverable
   * categories.
   */
  private fun sendToAccelerator(
      organizationId: OrganizationId,
      model: EmailTemplateModel,
      internalInterest: InternalInterest? = null,
  ) {
    systemUser.run {
      val internalInterestCondition =
          internalInterest?.let { userInternalInterestsStore.conditionForUsers(it) }
      val recipients =
          userStore
              .fetchWithGlobalRoles(setOf(GlobalRole.TFExpert), internalInterestCondition)
              .toMutableSet()

      val tfContacts = userStore.getTerraformationContactUsers(organizationId)

      // The TF contacts will not have access to the accelerator console, this email notification
      // gives the contacts an opportunity to acquire global roles. Ideally we won't be sending
      // these emails.
      tfContacts.forEach { recipients.add(it) }

      recipients.forEach { user -> emailService.sendUserNotification(user, model, false) }
    }
  }

  data class EmailRequest(val user: IndividualUser, val emailTemplateModel: EmailTemplateModel)
}
