package com.terraformation.backend.customer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ModuleEventStore
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.db.ParticipantStore
import com.terraformation.backend.accelerator.db.UserDeliverableCategoriesStore
import com.terraformation.backend.accelerator.event.ApplicationStatusUpdatedEvent
import com.terraformation.backend.accelerator.event.DeliverableReadyForReviewEvent
import com.terraformation.backend.accelerator.event.DeliverableStatusUpdatedEvent
import com.terraformation.backend.accelerator.event.ModuleEventStartingEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedToProjectNotificationDueEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent
import com.terraformation.backend.accelerator.model.ExternalApplicationStatus
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.auth.InMemoryKeycloakAdminClient
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.NotificationStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.event.UserAddedToTerrawareEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.ReportStatus
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.NotificationsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.event.DeviceUnresponsiveEvent
import com.terraformation.backend.device.event.SensorBoundsAlertTriggeredEvent
import com.terraformation.backend.device.event.UnknownAutomationTriggeredEvent
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableStatusUpdatedEvent
import com.terraformation.backend.dummyKeycloakInfo
import com.terraformation.backend.email.WebAppUrls
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.NotificationMessage
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.event.NurserySeedlingBatchReadyEvent
import com.terraformation.backend.report.event.ReportCreatedEvent
import com.terraformation.backend.report.model.ReportMetadata
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.db.BagStore
import com.terraformation.backend.seedbank.db.GeolocationStore
import com.terraformation.backend.seedbank.db.ViabilityTestStore
import com.terraformation.backend.seedbank.db.WithdrawalStore
import com.terraformation.backend.seedbank.event.AccessionDryingEndEvent
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.event.ObservationStartedEvent
import com.terraformation.backend.tracking.event.ObservationUpcomingNotificationDueEvent
import com.terraformation.backend.tracking.event.PlantingSeasonNotScheduledNotificationEvent
import com.terraformation.backend.tracking.event.PlantingSeasonStartedEvent
import com.terraformation.backend.tracking.event.ScheduleObservationNotificationEvent
import com.terraformation.backend.tracking.event.ScheduleObservationReminderNotificationEvent
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.util.mockDeliverable
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

internal class AppNotificationServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  val deliverable = mockDeliverable()

  @Autowired private lateinit var config: TerrawareServerConfig

  private lateinit var facilityId: FacilityId
  private lateinit var organizationId: OrganizationId
  private lateinit var otherUserId: UserId

  private val clock = TestClock()
  private val messages: Messages = mockk()

  private lateinit var accessionStore: AccessionStore
  private lateinit var automationStore: AutomationStore
  private lateinit var deliverableStore: DeliverableStore
  private lateinit var deviceStore: DeviceStore
  private lateinit var facilityStore: FacilityStore
  private lateinit var moduleEventStore: ModuleEventStore
  private lateinit var moduleStore: ModuleStore
  private lateinit var notificationStore: NotificationStore
  private lateinit var organizationStore: OrganizationStore
  private lateinit var parentStore: ParentStore
  private lateinit var participantStore: ParticipantStore
  private lateinit var plantingSiteStore: PlantingSiteStore
  private lateinit var projectStore: ProjectStore
  private lateinit var speciesStore: SpeciesStore
  private lateinit var userDeliverableCategoriesStore: UserDeliverableCategoriesStore
  private lateinit var userStore: UserStore
  private lateinit var service: AppNotificationService
  private lateinit var webAppUrls: WebAppUrls

  @BeforeEach
  fun setUp() {
    val objectMapper = jacksonObjectMapper()
    val publisher = TestEventPublisher()

    organizationId = insertOrganization()
    facilityId = insertFacility()

    parentStore = ParentStore(dslContext)

    accessionStore =
        AccessionStore(
            dslContext,
            BagStore(dslContext),
            facilitiesDao,
            GeolocationStore(dslContext, clock),
            ViabilityTestStore(dslContext),
            parentStore,
            WithdrawalStore(dslContext, clock, mockk(), parentStore),
            clock,
            publisher,
            mockk(),
            IdentifierGenerator(clock, dslContext),
        )
    automationStore = AutomationStore(automationsDao, clock, dslContext, objectMapper, parentStore)
    deliverableStore = DeliverableStore(dslContext)
    deviceStore = DeviceStore(devicesDao)
    facilityStore =
        FacilityStore(
            clock,
            mockk(),
            dslContext,
            publisher,
            facilitiesDao,
            messages,
            organizationsDao,
            subLocationsDao)
    moduleEventStore = ModuleEventStore(clock, dslContext, publisher, eventsDao)
    moduleStore = ModuleStore(dslContext)
    notificationStore = NotificationStore(dslContext, clock)
    organizationStore = OrganizationStore(clock, dslContext, organizationsDao, publisher)
    participantStore = ParticipantStore(clock, dslContext, publisher, participantsDao)
    plantingSiteStore =
        PlantingSiteStore(
            clock,
            dslContext,
            publisher,
            monitoringPlotsDao,
            parentStore,
            plantingSeasonsDao,
            plantingSitesDao,
            plantingSubzonesDao,
            plantingZonesDao)
    projectStore = ProjectStore(clock, dslContext, publisher, projectsDao)
    speciesStore =
        SpeciesStore(
            clock,
            dslContext,
            speciesDao,
            speciesEcosystemTypesDao,
            speciesGrowthFormsDao,
            speciesProblemsDao)
    userDeliverableCategoriesStore = UserDeliverableCategoriesStore(clock, dslContext)
    userStore =
        UserStore(
            clock,
            config,
            dslContext,
            mockk(),
            InMemoryKeycloakAdminClient(),
            dummyKeycloakInfo(),
            organizationStore,
            ParentStore(dslContext),
            PermissionStore(dslContext),
            publisher,
            usersDao,
        )
    webAppUrls = WebAppUrls(config, dummyKeycloakInfo())
    service =
        AppNotificationService(
            automationStore,
            deliverableStore,
            deviceStore,
            dslContext,
            facilityStore,
            moduleEventStore,
            moduleStore,
            notificationStore,
            organizationStore,
            parentStore,
            participantStore,
            plantingSiteStore,
            projectStore,
            speciesStore,
            SystemUser(usersDao),
            userDeliverableCategoriesStore,
            userStore,
            messages,
            webAppUrls)

    every { messages.userAddedToOrganizationNotification(any()) } returns
        NotificationMessage("organization title", "organization body")
    every { messages.accessionDryingEndNotification(any()) } returns
        NotificationMessage("accession title", "accession body")
    every { messages.nurserySeedlingBatchReadyNotification(any(), any()) } returns
        NotificationMessage("nursery title", "nursery body")
    every { messages.facilityIdle() } returns
        NotificationMessage("facility idle title", "facility idle body")
    every { messages.moduleEventStartingNotification(any(), any()) } returns
        NotificationMessage("module event starting title", "module event starting body")
    every { messages.moduleRecordedSessionNotification(any()) } returns
        NotificationMessage("module recorded title", "module recorded body")
    every { user.canCreateAccession(facilityId) } returns true
    every { user.canCreateAutomation(any()) } returns true
    every { user.canCreateNotification(any(), organizationId) } returns true
    every { user.canReadAccession(any()) } returns true
    every { user.canReadAllDeliverables() } returns true
    every { user.canReadAutomation(any()) } returns true
    every { user.canReadDevice(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canReadGlobalRoles() } returns true
    every { user.canReadModule(any()) } returns true
    every { user.canReadModuleEvent(any()) } returns true
    every { user.canReadModuleEventParticipants() } returns true
    every { user.canReadOrganization(organizationId) } returns true
    every { user.canReadParticipant(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadProjectModules(any()) } returns true
    every { user.canReadSpecies(any()) } returns true
    every { user.locale } returns Locale.ENGLISH
    every { user.organizationRoles } returns mapOf(organizationId to Role.Admin)

    otherUserId = insertUser()
  }

  @Test
  fun `should have event listener for User Added To Organization event`() {
    assertIsEventListener<UserAddedToOrganizationEvent>(service)
  }

  @Test
  fun `should store a notification of type User Added To Organization`() {
    insertOrganizationUser(otherUserId)

    service.on(UserAddedToOrganizationEvent(otherUserId, organizationId, user.userId))

    assertNotification(
        type = NotificationType.UserAddedToOrganization,
        userId = otherUserId,
        organizationId = null,
        title = "organization title",
        body = "organization body",
        localUrl = webAppUrls.organizationHome(organizationId))
  }

  @Test
  fun `should store a notification of type User Added To Organization when user is added to Terraware`() {
    insertOrganizationUser(otherUserId)

    service.on(UserAddedToTerrawareEvent(otherUserId, organizationId, user.userId))

    assertNotification(
        type = NotificationType.UserAddedToOrganization,
        userId = otherUserId,
        organizationId = null,
        title = "organization title",
        body = "organization body",
        localUrl = webAppUrls.organizationHome(organizationId))
  }

  @Test
  fun `should store accession drying end date notification`() {
    insertOrganizationUser()

    val accessionModel =
        accessionStore.create(AccessionModel(clock = clock, facilityId = facilityId))
    assertNotNull(accessionModel)

    service.on(AccessionDryingEndEvent(accessionModel.accessionNumber!!, accessionModel.id!!))

    assertNotification(
        type = NotificationType.AccessionScheduledToEndDrying,
        title = "accession title",
        body = "accession body",
        localUrl = webAppUrls.accession(accessionModel.id!!))
  }

  @Test
  fun `should store nursery seedling batch ready notification`() {
    insertOrganizationUser()

    val nurseryName = "my nursery"
    val batchNumber = "22-2-001"

    val facilityId = insertFacility(type = FacilityType.Nursery, name = nurseryName)
    val speciesId = insertSpecies()
    val batchId =
        insertBatch(
            BatchesRow(batchNumber = batchNumber, speciesId = speciesId, facilityId = facilityId))

    service.on(NurserySeedlingBatchReadyEvent(batchId, batchNumber, speciesId, nurseryName))

    assertNotification(
        type = NotificationType.NurserySeedlingBatchReady,
        organizationId = organizationId,
        title = "nursery title",
        body = "nursery body",
        localUrl = webAppUrls.batch(batchId, speciesId))
  }

  @Test
  fun `should store facility idle notification`() {
    insertOrganizationUser()

    service.on(FacilityIdleEvent(facilityId))

    assertNotification(
        type = NotificationType.FacilityIdle,
        organizationId = organizationId,
        title = "facility idle title",
        body = "facility idle body",
        localUrl = webAppUrls.facilityMonitoring(facilityId))
  }

  @Test
  fun `should store sensor bounds alert notification`() {
    val timeseriesName = "test timeseries"
    val facilityName = "Facility 1"
    val badValue = 5.678

    insertOrganizationUser()
    val deviceId = insertDevice()
    val automationId = insertAutomation(timeseriesName = timeseriesName)

    every {
      messages.sensorBoundsAlert(
          devicesDao.fetchOneById(deviceId)!!, facilityName, timeseriesName, badValue)
    } returns NotificationMessage("bounds title", "bounds body")

    service.on(SensorBoundsAlertTriggeredEvent(automationId, badValue))

    assertNotification(
        type = NotificationType.SensorOutOfBounds,
        title = "bounds title",
        body = "bounds body",
        localUrl = webAppUrls.facilityMonitoring(facilityId))
  }

  @Test
  fun `should store unknown automation triggered notification`() {
    val automationName = "automation name"
    val automationType = "unknown"
    val facilityName = "Facility 1"
    val message = "message"

    insertOrganizationUser()
    val automationId =
        insertAutomation(name = automationName, type = automationType, deviceId = null)

    val title = "Automation $automationId triggered at $facilityName"
    every { messages.unknownAutomationTriggered(automationName, facilityName, message) } returns
        NotificationMessage(title, message)

    service.on(UnknownAutomationTriggeredEvent(automationId, automationType, message))

    assertNotification(
        type = NotificationType.UnknownAutomationTriggered,
        title = title,
        body = message,
        localUrl = webAppUrls.facilityMonitoring(facilityId))
  }

  @Test
  fun `should store device unresponsive notification`() {
    val deviceName = "test device"
    val deviceId = insertDevice(name = deviceName, type = "sensor")

    insertOrganizationUser()

    every { messages.deviceUnresponsive(deviceName) } returns
        NotificationMessage("unresponsive title", "unresponsive body")

    service.on(DeviceUnresponsiveEvent(deviceId, Instant.EPOCH, Duration.ofSeconds(1)))

    val device = deviceStore.fetchOneById(deviceId)

    assertNotification(
        type = NotificationType.DeviceUnresponsive,
        title = "unresponsive title",
        body = "unresponsive body",
        localUrl = webAppUrls.facilityMonitoring(facilityId, device))
  }

  @Test
  fun `should store report created notification for admins and owners`() {
    val admin = insertUser()
    val owner = insertUser()
    val manager = insertUser()
    val contributor = insertUser()

    insertOrganizationUser(admin, role = Role.Admin)
    insertOrganizationUser(owner, role = Role.Owner)
    insertOrganizationUser(manager, role = Role.Manager)
    insertOrganizationUser(contributor, role = Role.Contributor)

    every { messages.reportCreated(2023, 3) } returns
        NotificationMessage("report title", "report body")

    service.on(
        ReportCreatedEvent(
            ReportMetadata(
                ReportId(1),
                organizationId = organizationId,
                quarter = 3,
                status = ReportStatus.New,
                year = 2023)))

    val commonValues =
        NotificationsRow(
            body = "report body",
            localUrl = webAppUrls.report(ReportId(1)),
            notificationTypeId = NotificationType.ReportCreated,
            organizationId = organizationId,
            title = "report title",
        )

    assertNotifications(
        listOf(commonValues.copy(userId = admin), commonValues.copy(userId = owner)))
  }

  @Test
  fun `should store started observation notification`() {
    val startDate = LocalDate.of(2023, 3, 1)
    val endDate = startDate.plusDays(1)

    insertOrganizationUser()
    insertPlantingSite()
    insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

    every { messages.observationStarted() } returns
        NotificationMessage("started title", "started body")

    service.on(
        ObservationStartedEvent(
            ExistingObservationModel(
                endDate = endDate,
                id = inserted.observationId,
                plantingSiteId = inserted.plantingSiteId,
                startDate = startDate,
                state = ObservationState.InProgress)))

    assertNotification(
        type = NotificationType.ObservationStarted,
        title = "started title",
        body = "started body",
        localUrl = webAppUrls.observations(organizationId, inserted.plantingSiteId))

    assertIsEventListener<ObservationStartedEvent>(service)
  }

  @Test
  fun `should store upcoming observation notification`() {
    val plantingSiteName = "My Site"
    val startDate = LocalDate.of(2023, 3, 1)
    val endDate = startDate.plusDays(1)

    insertOrganizationUser()
    insertPlantingSite(name = plantingSiteName)
    insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

    every { messages.observationUpcoming(plantingSiteName, startDate) } returns
        NotificationMessage("upcoming title", "upcoming body")

    service.on(
        ObservationUpcomingNotificationDueEvent(
            ExistingObservationModel(
                endDate = endDate,
                id = inserted.observationId,
                plantingSiteId = inserted.plantingSiteId,
                startDate = startDate,
                state = ObservationState.Upcoming)))

    assertNotification(
        type = NotificationType.ObservationUpcoming,
        title = "upcoming title",
        body = "upcoming body",
        localUrl = webAppUrls.observations(organizationId, inserted.plantingSiteId))
  }

  @Test
  fun `should store schedule observation notification`() {
    val plantingSiteName = "My Site"

    insertOrganizationUser(role = Role.Admin)
    insertPlantingSite(name = plantingSiteName)

    every { messages.observationSchedule() } returns
        NotificationMessage("schedule title", "schedule body")

    service.on(ScheduleObservationNotificationEvent(inserted.plantingSiteId))

    assertNotification(
        type = NotificationType.ScheduleObservation,
        title = "schedule title",
        body = "schedule body",
        localUrl = webAppUrls.observations(organizationId, inserted.plantingSiteId))
  }

  @Test
  fun `should store schedule observation reminder notification`() {
    val plantingSiteName = "My Site"

    insertOrganizationUser(role = Role.Admin)
    insertPlantingSite(name = plantingSiteName)

    every { messages.observationScheduleReminder() } returns
        NotificationMessage("reminder title", "reminder body")

    service.on(ScheduleObservationReminderNotificationEvent(inserted.plantingSiteId))

    assertNotification(
        type = NotificationType.ScheduleObservationReminder,
        title = "reminder title",
        body = "reminder body",
        localUrl = webAppUrls.observations(organizationId, inserted.plantingSiteId))
  }

  @Test
  fun `should store planting season started notification`() {
    val plantingSiteName = "My Site"

    insertOrganizationUser(role = Role.Manager)
    insertPlantingSite(name = plantingSiteName)
    insertPlantingSeason()

    every { messages.plantingSeasonStarted(any()) } returns
        NotificationMessage("season title", "season body")

    service.on(PlantingSeasonStartedEvent(inserted.plantingSiteId, inserted.plantingSeasonId))

    assertNotification(
        type = NotificationType.PlantingSeasonStarted,
        title = "season title",
        body = "season body",
        localUrl = webAppUrls.nurseryInventory())
  }

  @Test
  fun `should store planting season not scheduled notification`() {
    val plantingSiteName = "My Site"

    insertOrganizationUser(role = Role.Manager)
    insertPlantingSite(name = plantingSiteName)
    insertPlantingSeason()

    every { messages.plantingSeasonNotScheduled(any()) } returns
        NotificationMessage("season title", "season body")

    service.on(PlantingSeasonNotScheduledNotificationEvent(inserted.plantingSiteId, 1))

    assertNotification(
        type = NotificationType.SchedulePlantingSeason,
        title = "season title",
        body = "season body",
        localUrl = webAppUrls.plantingSite(inserted.plantingSiteId))
  }

  @Test
  fun `should store deliverable ready for review notification for admin with no categories`() {
    insertUserGlobalRole(user.userId, GlobalRole.TFExpert)
    val cohortId = insertCohort()
    val participantId = insertParticipant(name = "participant1", cohortId = cohortId)
    val projectId = insertProject(participantId = participantId)
    insertModule()
    insertCohortModule()
    val deliverableId = insertDeliverable()
    val deliverable =
        deliverableStore.fetchDeliverableSubmissions(deliverableId = deliverableId).first()

    every { messages.deliverableReadyForReview("participant1") } returns
        NotificationMessage("ready for review title", "ready for review body")

    service.on(DeliverableReadyForReviewEvent(deliverable, projectId))

    assertNotification(
        type = NotificationType.DeliverableReadyForReview,
        title = "ready for review title",
        body = "ready for review body",
        localUrl = webAppUrls.acceleratorConsoleDeliverable(deliverableId, projectId),
        organizationId = null)
  }

  @Test
  fun `should store deliverable ready for review notification for admin with correct category`() {
    insertModule()
    insertUserGlobalRole(user.userId, GlobalRole.TFExpert)
    val cohortId = insertCohort()
    insertCohortModule()
    val participantId = insertParticipant(name = "participant1", cohortId = cohortId)
    val projectId = insertProject(participantId = participantId)

    insertUserDeliverableCategory(DeliverableCategory.GIS, user.userId)
    val deliverableId = insertDeliverable(deliverableCategoryId = DeliverableCategory.GIS)
    val deliverable =
        deliverableStore.fetchDeliverableSubmissions(deliverableId = deliverableId).first()

    every { messages.deliverableReadyForReview("participant1") } returns
        NotificationMessage("ready for review title", "ready for review body")

    service.on(DeliverableReadyForReviewEvent(deliverable, projectId))

    assertNotification(
        type = NotificationType.DeliverableReadyForReview,
        title = "ready for review title",
        body = "ready for review body",
        localUrl = webAppUrls.acceleratorConsoleDeliverable(deliverableId, projectId),
        organizationId = null)
  }

  @Test
  fun `should not store deliverable ready for review notification for admin with wrong category`() {
    insertModule()
    insertUserGlobalRole(user.userId, GlobalRole.TFExpert)

    val cohortId = insertCohort()
    insertCohortModule()
    val participantId = insertParticipant(name = "participant1", cohortId = cohortId)
    val projectId = insertProject(participantId = participantId)

    insertUserDeliverableCategory(DeliverableCategory.Compliance, user.userId)
    val deliverableId = insertDeliverable(deliverableCategoryId = DeliverableCategory.GIS)
    val deliverable =
        deliverableStore.fetchDeliverableSubmissions(deliverableId = deliverableId).first()

    every { messages.deliverableReadyForReview("participant1") } returns
        NotificationMessage("ready for review title", "ready for review body")

    service.on(DeliverableReadyForReviewEvent(deliverable, projectId))

    assertNotifications(emptyList())
  }

  @Test
  fun `should store deliverable ready for review notification with TF contact`() {
    insertModule()
    insertUserGlobalRole(user.userId, GlobalRole.TFExpert)
    val tfContact = insertUser(email = "tfcontact@terraformation.com")
    insertOrganizationUser(tfContact, role = Role.TerraformationContact)

    val cohortId = insertCohort()
    insertCohortModule()
    val participantId = insertParticipant(name = "participant1", cohortId = cohortId)
    val projectId = insertProject(participantId = participantId)

    // TF contact has the wrong deliverable category but we should notify them anyway because
    // being the contact overrides the category filtering.
    insertUserDeliverableCategory(DeliverableCategory.Compliance, tfContact)
    val deliverableId = insertDeliverable(deliverableCategoryId = DeliverableCategory.GIS)
    val deliverable =
        deliverableStore.fetchDeliverableSubmissions(deliverableId = deliverableId).first()

    every { messages.deliverableReadyForReview("participant1") } returns
        NotificationMessage("ready for review title", "ready for review body")

    service.on(DeliverableReadyForReviewEvent(deliverable, projectId))

    assertNotifications(
        listOf(
            NotificationsRow(
                notificationTypeId = NotificationType.DeliverableReadyForReview,
                title = "ready for review title",
                body = "ready for review body",
                localUrl = webAppUrls.acceleratorConsoleDeliverable(deliverableId, projectId),
                userId = user.userId,
                organizationId = null),
            NotificationsRow(
                notificationTypeId = NotificationType.DeliverableReadyForReview,
                title = "ready for review title",
                body = "ready for review body",
                localUrl = webAppUrls.acceleratorConsoleDeliverable(deliverableId, projectId),
                userId = tfContact,
                organizationId = null)))
  }

  @Test
  fun `should not over-notify deliverable ready for review notification with TF contact that is also an accelerator admin`() {
    insertUserGlobalRole(user.userId, GlobalRole.TFExpert)
    val tfContact = insertUser(email = "tfcontact@terraformation.com")
    insertOrganizationUser(tfContact, role = Role.TerraformationContact)
    insertUserGlobalRole(tfContact, role = GlobalRole.TFExpert)

    val cohortId = insertCohort()
    val participantId = insertParticipant(name = "participant1", cohortId = cohortId)
    val projectId = insertProject(participantId = participantId)
    insertModule()
    insertCohortModule()
    val deliverableId = insertDeliverable()
    val deliverable =
        deliverableStore.fetchDeliverableSubmissions(deliverableId = deliverableId).first()

    every { messages.deliverableReadyForReview("participant1") } returns
        NotificationMessage("ready for review title", "ready for review body")

    service.on(DeliverableReadyForReviewEvent(deliverable, projectId))

    assertNotifications(
        listOf(
            NotificationsRow(
                notificationTypeId = NotificationType.DeliverableReadyForReview,
                title = "ready for review title",
                body = "ready for review body",
                localUrl = webAppUrls.acceleratorConsoleDeliverable(deliverableId, projectId),
                userId = user.userId,
                organizationId = null),
            NotificationsRow(
                notificationTypeId = NotificationType.DeliverableReadyForReview,
                title = "ready for review title",
                body = "ready for review body",
                localUrl = webAppUrls.acceleratorConsoleDeliverable(deliverableId, projectId),
                userId = tfContact,
                organizationId = null)))
  }

  @Test
  fun `should store deliverable status updated notification`() {
    insertOrganizationUser(role = Role.Admin)
    val projectId = insertProject()
    val deliverableId = DeliverableId(1)
    val submissionId = SubmissionId(1)

    every { messages.deliverableStatusUpdated() } returns
        NotificationMessage("status updated title", "status updated body")

    service.on(
        DeliverableStatusUpdatedEvent(
            deliverableId,
            projectId,
            SubmissionStatus.NotSubmitted,
            SubmissionStatus.InReview,
            submissionId))

    assertNotification(
        type = NotificationType.DeliverableStatusUpdated,
        title = "status updated title",
        body = "status updated body",
        localUrl = webAppUrls.deliverable(deliverableId, projectId))
  }

  @Test
  fun `should not store deliverable status updated notification for internal-only statuses`() {
    insertOrganizationUser(role = Role.Admin)
    val projectId = insertProject()
    val deliverableId = DeliverableId(1)
    val submissionId = SubmissionId(1)

    service.on(
        DeliverableStatusUpdatedEvent(
            deliverableId,
            projectId,
            SubmissionStatus.NeedsTranslation,
            SubmissionStatus.InReview,
            submissionId))

    assertNotifications(emptyList())
  }

  @Test
  fun `should store questions deliverable status updated notification`() {
    insertOrganizationUser(role = Role.Admin)
    val projectId = insertProject()
    val deliverableId = DeliverableId(1)

    every { messages.deliverableStatusUpdated() } returns
        NotificationMessage("status updated title", "status updated body")

    service.on(QuestionsDeliverableStatusUpdatedEvent(deliverableId, projectId))

    assertNotification(
        type = NotificationType.DeliverableStatusUpdated,
        title = "status updated title",
        body = "status updated body",
        localUrl = webAppUrls.deliverable(deliverableId, projectId))
  }

  @Test
  fun `should store species added to project notification`() {
    insertUserGlobalRole(user.userId, GlobalRole.TFExpert)
    val participantName = "Participant ${UUID.randomUUID()}"
    val participantId = insertParticipant(name = participantName)
    val projectId = insertProject(participantId = participantId)
    val speciesId = insertSpecies()
    insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId)
    insertModule()
    val deliverableId = insertDeliverable()

    every {
      messages.participantProjectSpeciesAddedToProject(participantName, "Project 1", "Species 1")
    } returns NotificationMessage("species added title", "species added body")

    service.on(
        ParticipantProjectSpeciesAddedToProjectNotificationDueEvent(
            deliverableId, projectId, speciesId))

    assertNotification(
        type = NotificationType.ParticipantProjectSpeciesAddedToProject,
        title = "species added title",
        body = "species added body",
        localUrl = webAppUrls.acceleratorConsoleDeliverable(deliverableId, projectId),
        userId = user.userId,
        organizationId = null)
  }

  @Test
  fun `should store species approved species edited notification`() {
    insertUserGlobalRole(user.userId, GlobalRole.TFExpert)
    val participantName = "Participant ${UUID.randomUUID()}"
    val participantId = insertParticipant(name = participantName)
    val projectId = insertProject(participantId = participantId)
    val speciesId = insertSpecies()
    insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId)
    insertModule()
    val deliverableId = insertDeliverable()

    every {
      messages.participantProjectSpeciesApprovedSpeciesEdited(participantName, "Species 1")
    } returns NotificationMessage("species edited title", "species edited body")

    service.on(
        ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent(
            deliverableId, projectId, speciesId))

    assertNotification(
        type = NotificationType.ParticipantProjectSpeciesApprovedSpeciesEdited,
        title = "species edited title",
        body = "species edited body",
        localUrl = webAppUrls.acceleratorConsoleDeliverable(deliverableId, projectId),
        userId = user.userId,
        organizationId = null)
  }

  @Test
  fun `should store application status update notification`() {
    insertOrganizationUser(role = Role.Manager)
    insertProject()
    insertApplication()

    every { messages.applicationStatusUpdatedNotifcation(any()) } returns
        NotificationMessage("application status title", "application status body")

    service.on(
        ApplicationStatusUpdatedEvent(inserted.applicationId, ExternalApplicationStatus.Accepted))

    assertNotification(
        type = NotificationType.ApplicationStatusUpdate,
        title = "application status title",
        body = "application status body",
        localUrl = webAppUrls.applicationReview(inserted.applicationId))
  }

  @Test
  fun `is a listener for Module Event Starting event`() {
    assertIsEventListener<ModuleEventStartingEvent>(service)
  }

  @Test
  fun `should store module event starting notification for all projects`() {
    val moduleId = insertModule()
    val eventId = insertEvent(moduleId = moduleId)

    insertOrganizationUser(role = Role.Admin)
    // Other user in same project
    insertOrganizationUser(otherUserId)
    val projectId = insertProject()

    // Other project in different org
    val thirdUserId = insertUser()
    val otherOrgId = insertOrganization()
    insertOrganizationUser(thirdUserId, otherOrgId)
    val otherProjectId = insertProject(organizationId = otherOrgId)

    insertEventProject(eventId, projectId)
    insertEventProject(eventId, otherProjectId)

    val commonValues =
        NotificationsRow(
            body = "module event starting body",
            localUrl = webAppUrls.moduleEvent(moduleId, eventId, organizationId, projectId),
            notificationTypeId = NotificationType.EventReminder,
            organizationId = organizationId,
            title = "module event starting title",
        )

    service.on(ModuleEventStartingEvent(eventId))

    assertNotifications(
        listOf(
            commonValues.copy(userId = currentUser().userId),
            commonValues.copy(userId = otherUserId),
            commonValues.copy(
                userId = thirdUserId,
                localUrl = webAppUrls.moduleEvent(moduleId, eventId, otherOrgId, otherProjectId),
                organizationId = otherOrgId),
        ))
  }

  @Test
  fun `should use alternate notification text for recorded session events`() {
    insertOrganizationUser(role = Role.Admin)
    val moduleId = insertModule()
    val eventId = insertEvent(moduleId = moduleId, eventType = EventType.RecordedSession)
    val projectId = insertProject()
    insertEventProject(eventId, projectId)

    service.on(ModuleEventStartingEvent(eventId))

    assertNotification(
        NotificationType.EventReminder,
        "module recorded title",
        "module recorded body",
        webAppUrls.moduleEvent(moduleId, eventId, organizationId, projectId))
  }

  @Test
  fun `should render notifications in locale of user`() {
    val gibberishUserId = insertUser(locale = Locales.GIBBERISH)
    insertOrganizationUser(gibberishUserId)

    var renderedInLocale: Locale? = null

    every { messages.userAddedToOrganizationNotification(any()) } answers
        {
          renderedInLocale = currentLocale()
          NotificationMessage("x", "y")
        }

    service.on(UserAddedToOrganizationEvent(gibberishUserId, organizationId, user.userId))

    assertEquals(Locales.GIBBERISH, renderedInLocale)
  }

  /**
   * Asserts that only a specific set of notifications exists. Supplies defaults for createdTime,
   * isRead, and userId, and ignores notification IDs.
   */
  private fun assertNotifications(expected: Collection<NotificationsRow>) {
    val expectedWithDefaults =
        expected.map { notification ->
          notification.copy(
              createdTime = notification.createdTime ?: Instant.EPOCH,
              isRead = notification.isRead ?: false,
              userId = notification.userId ?: user.userId,
          )
        }

    val actual = notificationsDao.findAll().map { it.copy(id = null) }

    assertEquals(expected.size, actual.size)
    assertEquals(expectedWithDefaults.toSet(), actual.toSet())
  }

  /** Asserts that only a single notification exists. */
  private fun assertNotification(
      type: NotificationType,
      title: String,
      body: String,
      localUrl: URI,
      organizationId: OrganizationId? = this.organizationId,
      userId: UserId = user.userId,
  ) {
    assertNotifications(
        setOf(
            NotificationsRow(
                body = body,
                notificationTypeId = type,
                localUrl = localUrl,
                organizationId = organizationId,
                title = title,
                userId = userId)))
  }
}
