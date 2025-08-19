package com.terraformation.backend.customer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ModuleEventStore
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.db.ParticipantStore
import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.accelerator.event.AcceleratorReportPublishedEvent
import com.terraformation.backend.accelerator.event.AcceleratorReportUpcomingEvent
import com.terraformation.backend.accelerator.event.ApplicationSubmittedEvent
import com.terraformation.backend.accelerator.event.DeliverableReadyForReviewEvent
import com.terraformation.backend.accelerator.event.DeliverableStatusUpdatedEvent
import com.terraformation.backend.accelerator.event.ModuleEventStartingEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedToProjectNotificationDueEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent
import com.terraformation.backend.accelerator.event.RateLimitedAcceleratorReportSubmittedEvent
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
import com.terraformation.backend.customer.db.UserInternalInterestsStore
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
import com.terraformation.backend.db.accelerator.InternalInterest
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SeedFundReportStatus
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.pojos.NotificationsRow
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.event.DeviceUnresponsiveEvent
import com.terraformation.backend.device.event.SensorBoundsAlertTriggeredEvent
import com.terraformation.backend.device.event.UnknownAutomationTriggeredEvent
import com.terraformation.backend.documentproducer.db.DocumentStore
import com.terraformation.backend.documentproducer.db.VariableOwnerStore
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.event.CompletedSectionVariableUpdatedEvent
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableStatusUpdatedEvent
import com.terraformation.backend.dummyKeycloakInfo
import com.terraformation.backend.email.WebAppUrls
import com.terraformation.backend.funder.db.FundingEntityStore
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.event.NurserySeedlingBatchReadyEvent
import com.terraformation.backend.report.event.SeedFundReportCreatedEvent
import com.terraformation.backend.report.model.SeedFundReportMetadata
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
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
  private val messages: Messages = Messages()

  private lateinit var accessionStore: AccessionStore
  private lateinit var automationStore: AutomationStore
  private lateinit var deliverableStore: DeliverableStore
  private lateinit var deviceStore: DeviceStore
  private lateinit var documentStore: DocumentStore
  private lateinit var facilityStore: FacilityStore
  private lateinit var fundingEntityStore: FundingEntityStore
  private lateinit var moduleEventStore: ModuleEventStore
  private lateinit var moduleStore: ModuleStore
  private lateinit var notificationStore: NotificationStore
  private lateinit var organizationStore: OrganizationStore
  private lateinit var parentStore: ParentStore
  private lateinit var participantStore: ParticipantStore
  private lateinit var plantingSiteStore: PlantingSiteStore
  private lateinit var projectStore: ProjectStore
  private lateinit var reportStore: ReportStore
  private lateinit var speciesStore: SpeciesStore
  private lateinit var userInternalInterestsStore: UserInternalInterestsStore
  private lateinit var userStore: UserStore
  private lateinit var variableOwnerStore: VariableOwnerStore
  private lateinit var variableStore: VariableStore
  private lateinit var webAppUrls: WebAppUrls
  private lateinit var service: AppNotificationService

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
    documentStore =
        DocumentStore(
            clock, documentSavedVersionsDao, documentsDao, dslContext, documentTemplatesDao)
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
            TestSingletons.countryDetector,
            dslContext,
            publisher,
            IdentifierGenerator(clock, dslContext),
            monitoringPlotsDao,
            parentStore,
            plantingSeasonsDao,
            plantingSitesDao,
            plantingSubzonesDao,
            plantingZonesDao)
    projectStore =
        ProjectStore(
            clock, dslContext, publisher, parentStore, projectsDao, projectInternalUsersDao)
    reportStore = ReportStore(clock, dslContext, publisher, reportsDao, SystemUser(usersDao))
    speciesStore =
        SpeciesStore(
            clock,
            dslContext,
            speciesDao,
            speciesEcosystemTypesDao,
            speciesGrowthFormsDao,
            speciesProblemsDao)
    userInternalInterestsStore = UserInternalInterestsStore(clock, dslContext)
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
    variableOwnerStore = VariableOwnerStore(dslContext)
    variableStore =
        VariableStore(
            dslContext,
            variableNumbersDao,
            variablesDao,
            variableSectionDefaultValuesDao,
            variableSectionRecommendationsDao,
            variableSectionsDao,
            variableSelectsDao,
            variableSelectOptionsDao,
            variableTablesDao,
            variableTableColumnsDao,
            variableTextsDao)
    webAppUrls = WebAppUrls(config, dummyKeycloakInfo())
    service =
        AppNotificationService(
            automationStore,
            deliverableStore,
            deviceStore,
            documentStore,
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
            reportStore,
            speciesStore,
            SystemUser(usersDao),
            userInternalInterestsStore,
            userStore,
            variableOwnerStore,
            variableStore,
            messages,
            webAppUrls)

    every { user.canCreateAccession(facilityId) } returns true
    every { user.canCreateAutomation(any()) } returns true
    every { user.canCreateNotification(any()) } returns true
    every { user.canListOrganizationUsers(any()) } returns true
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
  fun `should store a notification of type User Added To Organization`() {
    testEventNotification(
        UserAddedToOrganizationEvent(otherUserId, organizationId, user.userId),
        type = NotificationType.UserAddedToOrganization,
        userId = otherUserId,
        organizationId = null,
        title = "You've been added to a new organization!",
        body = "You are now a member of Organization 1. Welcome!",
        localUrl = webAppUrls.organizationHome(organizationId))
  }

  @Test
  fun `should store a notification of type User Added To Organization when user is added to Terraware`() {
    testEventNotification(
        UserAddedToTerrawareEvent(otherUserId, organizationId, user.userId),
        type = NotificationType.UserAddedToOrganization,
        userId = otherUserId,
        organizationId = null,
        title = "You've been added to a new organization!",
        body = "You are now a member of Organization 1. Welcome!",
        localUrl = webAppUrls.organizationHome(organizationId))
  }

  @Test
  fun `should store accession drying end date notification`() {
    val accessionModel =
        accessionStore.create(AccessionModel(clock = clock, facilityId = facilityId))
    assertNotNull(accessionModel)

    testEventNotification(
        AccessionDryingEndEvent(accessionModel.accessionNumber!!, accessionModel.id!!),
        type = NotificationType.AccessionScheduledToEndDrying,
        title = "An accession has dried",
        body = "${accessionModel.accessionNumber} has finished drying.",
        localUrl = webAppUrls.accession(accessionModel.id!!))
  }

  @Test
  fun `should store nursery seedling batch ready notification`() {
    val nurseryName = "my nursery"
    val batchNumber = "22-2-001"

    val facilityId = insertFacility(type = FacilityType.Nursery, name = nurseryName)
    val speciesId = insertSpecies()
    val batchId =
        insertBatch(
            BatchesRow(batchNumber = batchNumber, speciesId = speciesId, facilityId = facilityId))

    testEventNotification(
        NurserySeedlingBatchReadyEvent(batchId, batchNumber, speciesId, nurseryName),
        type = NotificationType.NurserySeedlingBatchReady,
        organizationId = organizationId,
        title = "$batchNumber has reached its scheduled ready by date.",
        body =
            "$batchNumber (located in $nurseryName) has reached its scheduled ready by date. Check on your plants and update their status if needed.",
        localUrl = webAppUrls.batch(batchId, speciesId))
  }

  @Test
  fun `should store facility idle notification`() {
    testEventNotification(
        FacilityIdleEvent(facilityId),
        type = NotificationType.FacilityIdle,
        organizationId = organizationId,
        title = "Device manager cannot be detected.",
        body = "Device manager is disconnected. Please check on it.",
        localUrl = webAppUrls.facilityMonitoring(facilityId))
  }

  @Test
  fun `should store sensor bounds alert notification`() {
    val timeseriesName = "test timeseries"
    val badValue = 5.678

    insertDevice()
    val automationId = insertAutomation(timeseriesName = timeseriesName)

    testEventNotification(
        SensorBoundsAlertTriggeredEvent(automationId, badValue),
        type = NotificationType.SensorOutOfBounds,
        title = "device 1 is out of range.",
        body = "$timeseriesName on device 1 is $badValue, which is out of threshold.",
        localUrl = webAppUrls.facilityMonitoring(facilityId))
  }

  @Test
  fun `should store unknown automation triggered notification`() {
    val automationName = "automation name"
    val automationType = "unknown"
    val facilityName = "Facility 1"
    val message = "message"

    val automationId =
        insertAutomation(name = automationName, type = automationType, deviceId = null)

    testEventNotification(
        UnknownAutomationTriggeredEvent(automationId, automationType, message),
        type = NotificationType.UnknownAutomationTriggered,
        title = "$automationName triggered at $facilityName",
        body = message,
        localUrl = webAppUrls.facilityMonitoring(facilityId))
  }

  @Test
  fun `should store device unresponsive notification`() {
    val deviceName = "test device"
    val deviceId = insertDevice(name = deviceName, type = "sensor")
    val device = deviceStore.fetchOneById(deviceId)

    testEventNotification(
        DeviceUnresponsiveEvent(deviceId, Instant.EPOCH, Duration.ofSeconds(1)),
        type = NotificationType.DeviceUnresponsive,
        title = "$deviceName cannot be detected.",
        body = "$deviceName cannot be detected. Please check on it.",
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

    val commonValues =
        NotificationsRow(
            body =
                "Your 2023-Q3 Seed Fund Report is ready to be completed and submitted to Terraformation.",
            localUrl = webAppUrls.seedFundReport(SeedFundReportId(1)),
            notificationTypeId = NotificationType.SeedFundReportCreated,
            organizationId = organizationId,
            title = "Time to Complete Your 2023-Q3 Seed Fund Report",
        )

    testMultipleEventNotifications(
        SeedFundReportCreatedEvent(
            SeedFundReportMetadata(
                SeedFundReportId(1),
                organizationId = organizationId,
                quarter = 3,
                status = SeedFundReportStatus.New,
                year = 2023)),
        listOf(commonValues.copy(userId = admin), commonValues.copy(userId = owner)))
  }

  @Test
  fun `should store started observation notification`() {
    val startDate = LocalDate.of(2023, 3, 1)
    val endDate = startDate.plusDays(1)

    insertPlantingSite()
    insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

    testEventNotification(
        ObservationStartedEvent(
            ExistingObservationModel(
                endDate = endDate,
                id = inserted.observationId,
                isAdHoc = false,
                observationType = ObservationType.Monitoring,
                plantingSiteId = inserted.plantingSiteId,
                startDate = startDate,
                state = ObservationState.InProgress)),
        type = NotificationType.ObservationStarted,
        title = "It is time to monitor your plantings!",
        body = "Observations of your plantings need to be completed this month.",
        localUrl = webAppUrls.observations(organizationId, inserted.plantingSiteId))
  }

  @Test
  fun `should store upcoming observation notification`() {
    val plantingSiteName = "My Site"
    val startDate = LocalDate.of(2023, 3, 1)
    val endDate = startDate.plusDays(1)

    insertPlantingSite(name = plantingSiteName)
    insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

    testEventNotification(
        ObservationUpcomingNotificationDueEvent(
            ExistingObservationModel(
                endDate = endDate,
                id = inserted.observationId,
                isAdHoc = false,
                observationType = ObservationType.Monitoring,
                plantingSiteId = inserted.plantingSiteId,
                startDate = startDate,
                state = ObservationState.Upcoming)),
        type = NotificationType.ObservationUpcoming,
        title = "Upcoming Observation",
        body =
            "$plantingSiteName has an observation event scheduled for ${
          DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withLocale(currentLocale())
            .format(startDate)}.",
        localUrl = webAppUrls.observations(organizationId, inserted.plantingSiteId),
    )
  }

  @Test
  fun `should store schedule observation notification`() {
    val plantingSiteName = "My Site"

    insertPlantingSite(name = plantingSiteName)

    testEventNotification(
        ScheduleObservationNotificationEvent(inserted.plantingSiteId),
        type = NotificationType.ScheduleObservation,
        title = "Schedule an observation",
        body = "It's time to schedule an observation for your planting site",
        localUrl = webAppUrls.observations(organizationId, inserted.plantingSiteId),
        role = Role.Admin)
  }

  @Test
  fun `should store schedule observation reminder notification`() {
    val plantingSiteName = "My Site"

    insertPlantingSite(name = plantingSiteName)

    testEventNotification(
        ScheduleObservationReminderNotificationEvent(inserted.plantingSiteId),
        type = NotificationType.ScheduleObservationReminder,
        title = "Reminder: Schedule an observation",
        body = "Remember to schedule an observation for your planting site",
        localUrl = webAppUrls.observations(organizationId, inserted.plantingSiteId),
        role = Role.Admin)
  }

  @Test
  fun `should store planting season started notification`() {
    val plantingSiteName = "My Site"

    insertPlantingSite(name = plantingSiteName)
    insertPlantingSeason()

    testEventNotification(
        PlantingSeasonStartedEvent(inserted.plantingSiteId, inserted.plantingSeasonId),
        type = NotificationType.PlantingSeasonStarted,
        title = "It's planting season!",
        body =
            "Planting season has begun at planting site $plantingSiteName. To begin planting in the field, make sure that your nursery inventory is up-to-date and that you log your nursery withdrawals as you begin planting.",
        localUrl = webAppUrls.nurseryInventory(),
        role = Role.Manager)
  }

  @Test
  fun `should store planting season not scheduled notification`() {
    val plantingSiteName = "My Site"

    insertPlantingSite(name = plantingSiteName)
    insertPlantingSeason()

    testEventNotification(
        PlantingSeasonNotScheduledNotificationEvent(inserted.plantingSiteId, 1),
        type = NotificationType.SchedulePlantingSeason,
        title = "Add your next planting season",
        body = "It's time to schedule your next planting season",
        localUrl = webAppUrls.plantingSite(inserted.plantingSiteId),
        role = Role.Manager)
  }

  @Test
  fun `should store accelerator report upcoming notification for organization admins`() {
    val admin = insertUser()
    val owner = insertUser()

    insertOrganizationUser(admin, role = Role.Admin)
    insertOrganizationUser(owner, role = Role.Owner)

    val projectId = insertProject()
    insertProjectAcceleratorDetails(dealName = "DEAL_name")
    insertProjectReportConfig()
    val reportId =
        insertReport(
            projectId = projectId,
            status = ReportStatus.Submitted,
            startDate = LocalDate.of(2025, Month.JANUARY, 1),
            endDate = LocalDate.of(2025, Month.MARCH, 31),
        )

    val commonValues =
        NotificationsRow(
            notificationTypeId = NotificationType.AcceleratorReportUpcoming,
            title = "2025 Q1 Report Due",
            body = "Your 2025 Q1 Report is due.",
            localUrl = webAppUrls.acceleratorReport(reportId),
            organizationId = organizationId,
            userId = admin)

    testMultipleEventNotifications(
        AcceleratorReportUpcomingEvent(reportId),
        listOf(commonValues.copy(userId = admin), commonValues.copy(userId = owner)))
  }

  @Test
  fun `should store accelerator report submitted notification for global users`() {
    insertUserGlobalRole(user.userId, GlobalRole.TFExpert)
    val projectId = insertProject()
    insertProjectAcceleratorDetails(dealName = "DEAL_name")
    insertProjectReportConfig()
    val reportId =
        insertReport(
            projectId = projectId,
            status = ReportStatus.Submitted,
            startDate = LocalDate.of(2025, Month.JANUARY, 1),
            endDate = LocalDate.of(2025, Month.MARCH, 31),
        )

    testEventNotification(
        RateLimitedAcceleratorReportSubmittedEvent(reportId),
        type = NotificationType.AcceleratorReportSubmitted,
        title = "2025 Q1 Report Submitted for DEAL_name",
        body = "DEAL_name has submitted their 2025 Q1 Report.",
        localUrl = webAppUrls.acceleratorConsoleReport(reportId, projectId),
        organizationId = null)
  }

  @Test
  fun `should store accelerator report published notification for funders`() {
    val fundingEntityIdA = insertFundingEntity()
    val fundingEntityIdB = insertFundingEntity()

    val userIdA1 = insertUser(type = UserType.Funder)
    val userIdA2 = insertUser(type = UserType.Funder)
    val userIdB1 = insertUser(type = UserType.Funder)

    insertFundingEntityUser(fundingEntityIdA, userIdA1)
    insertFundingEntityUser(fundingEntityIdA, userIdA2)
    insertFundingEntityUser(fundingEntityIdB, userIdB1)

    // Not notified
    insertFundingEntity()
    insertUser(type = UserType.Funder)
    insertFundingEntityUser()

    val projectId = insertProject()
    insertProjectAcceleratorDetails(dealName = "DEAL_name")
    insertProjectReportConfig()

    insertFundingEntityProject(fundingEntityIdA, projectId)
    insertFundingEntityProject(fundingEntityIdB, projectId)

    val reportId =
        insertReport(
            projectId = projectId,
            status = ReportStatus.Approved,
            startDate = LocalDate.of(2025, Month.JANUARY, 1),
            endDate = LocalDate.of(2025, Month.MARCH, 31),
        )

    val commonValues =
        NotificationsRow(
            notificationTypeId = NotificationType.AcceleratorReportPublished,
            title = "New Report Available for DEAL_name",
            body = "DEAL_name's 2025 Q1 report is now available in Terraware.",
            localUrl = webAppUrls.funderReport(reportId),
            organizationId = null)

    testMultipleEventNotifications(
        AcceleratorReportPublishedEvent(reportId),
        listOf(
            commonValues.copy(userId = userIdA1),
            commonValues.copy(userId = userIdA2),
            commonValues.copy(userId = userIdB1)))
  }

  @Test
  fun `should store application submitted notification for global users with sourcing`() {
    insertUserGlobalRole(user.userId, GlobalRole.TFExpert)
    val projectId = insertProject()
    val applicationId = insertApplication(projectId = projectId)
    insertUserInternalInterest(InternalInterest.Sourcing, user.userId)

    testEventNotification(
        ApplicationSubmittedEvent(applicationId),
        type = NotificationType.ApplicationSubmitted,
        title = "Application Submitted",
        body = "An Application has been submitted for Organization 1",
        localUrl = webAppUrls.acceleratorConsoleApplication(applicationId),
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

    insertUserInternalInterest(InternalInterest.GIS, user.userId)
    val deliverableId = insertDeliverable(deliverableCategoryId = DeliverableCategory.GIS)

    testEventNotification(
        DeliverableReadyForReviewEvent(deliverableId, projectId),
        type = NotificationType.DeliverableReadyForReview,
        title = "Review a submitted deliverable",
        body = "A deliverable from participant1 is ready for review for approval.",
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

    insertUserInternalInterest(InternalInterest.Compliance, user.userId)
    val deliverableId = insertDeliverable(deliverableCategoryId = DeliverableCategory.GIS)

    testMultipleEventNotifications(
        DeliverableReadyForReviewEvent(deliverableId, projectId), emptyList())
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
    insertUserInternalInterest(InternalInterest.Compliance, tfContact)
    insertUserInternalInterest(InternalInterest.GIS, user.userId)
    val deliverableId = insertDeliverable(deliverableCategoryId = DeliverableCategory.GIS)

    val commonValues =
        NotificationsRow(
            notificationTypeId = NotificationType.DeliverableReadyForReview,
            title = "Review a submitted deliverable",
            body = "A deliverable from participant1 is ready for review for approval.",
            localUrl = webAppUrls.acceleratorConsoleDeliverable(deliverableId, projectId),
            organizationId = null)

    testMultipleEventNotifications(
        DeliverableReadyForReviewEvent(deliverableId, projectId),
        listOf(commonValues.copy(userId = user.userId), commonValues.copy(userId = tfContact)))
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
    val deliverableId =
        insertDeliverable(deliverableCategoryId = DeliverableCategory.CarbonEligibility)
    insertUserInternalInterest(InternalInterest.CarbonEligibility, user.userId)

    val commonValues =
        NotificationsRow(
            notificationTypeId = NotificationType.DeliverableReadyForReview,
            title = "Review a submitted deliverable",
            body = "A deliverable from participant1 is ready for review for approval.",
            localUrl = webAppUrls.acceleratorConsoleDeliverable(deliverableId, projectId),
            organizationId = null)

    testMultipleEventNotifications(
        DeliverableReadyForReviewEvent(deliverableId, projectId),
        listOf(commonValues.copy(userId = user.userId), commonValues.copy(userId = tfContact)))
  }

  @Test
  fun `should store deliverable status updated notification`() {
    val projectId = insertProject()
    val deliverableId = DeliverableId(1)
    val submissionId = SubmissionId(1)

    testEventNotification(
        DeliverableStatusUpdatedEvent(
            deliverableId,
            projectId,
            SubmissionStatus.NotSubmitted,
            SubmissionStatus.InReview,
            submissionId),
        type = NotificationType.DeliverableStatusUpdated,
        title = "View a deliverable's status",
        body = "A submitted deliverable was reviewed and its status was updated.",
        localUrl = webAppUrls.deliverable(deliverableId, projectId),
        role = Role.Admin)
  }

  @Test
  fun `should not store deliverable status updated notification for internal-only statuses`() {
    val projectId = insertProject()
    val deliverableId = DeliverableId(1)
    val submissionId = SubmissionId(1)

    insertOrganizationUser(role = Role.Admin)
    testMultipleEventNotifications(
        DeliverableStatusUpdatedEvent(
            deliverableId,
            projectId,
            SubmissionStatus.NeedsTranslation,
            SubmissionStatus.InReview,
            submissionId),
        emptyList())
  }

  @Test
  fun `should store questions deliverable status updated notification`() {
    val projectId = insertProject()
    val deliverableId = DeliverableId(1)

    testEventNotification(
        QuestionsDeliverableStatusUpdatedEvent(deliverableId, projectId),
        type = NotificationType.DeliverableStatusUpdated,
        title = "View a deliverable's status",
        body = "A submitted deliverable was reviewed and its status was updated.",
        localUrl = webAppUrls.deliverable(deliverableId, projectId),
        role = Role.Admin)
  }

  @Test
  fun `should store species added to project notification for global users with interest`() {
    insertUserGlobalRole(user.userId, GlobalRole.TFExpert)
    val participantName = "Participant ${UUID.randomUUID()}"
    val participantId = insertParticipant(name = participantName)
    val projectId = insertProject(participantId = participantId)
    val speciesId = insertSpecies()
    insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId)
    insertModule()
    val deliverableId =
        insertDeliverable(deliverableCategoryId = DeliverableCategory.CarbonEligibility)
    insertUserInternalInterest(InternalInterest.CarbonEligibility, user.userId)

    testEventNotification(
        ParticipantProjectSpeciesAddedToProjectNotificationDueEvent(
            deliverableId, projectId, speciesId),
        type = NotificationType.ParticipantProjectSpeciesAddedToProject,
        title = "A species has been added to a project for $participantName.",
        body = "Species 1 has been submitted for use for Project 1.",
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
    val deliverableId =
        insertDeliverable(deliverableCategoryId = DeliverableCategory.CarbonEligibility)
    insertUserInternalInterest(InternalInterest.CarbonEligibility, user.userId)

    testEventNotification(
        ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent(
            deliverableId, projectId, speciesId),
        type = NotificationType.ParticipantProjectSpeciesApprovedSpeciesEdited,
        title = "An approved species has been edited for $participantName.",
        body = "Species 1 has been edited and is ready for approval.",
        localUrl = webAppUrls.acceleratorConsoleDeliverable(deliverableId, projectId),
        userId = user.userId,
        organizationId = null)
  }

  @Test
  fun `should store completed section variable updated notification`() {
    insertDocumentTemplate()
    insertVariableManifest()
    val projectId = insertProject()
    val documentId = insertDocument(name = "My Document")
    val sectionVariableId =
        insertVariableManifestEntry(
            insertSectionVariable(insertVariable(type = VariableType.Section, name = "Overview")))
    insertVariableOwner(ownedBy = user.userId)

    testEventNotification(
        CompletedSectionVariableUpdatedEvent(
            documentId, projectId, sectionVariableId, sectionVariableId),
        type = NotificationType.CompletedSectionVariableUpdated,
        title = "Variable edited in a \"Completed\" document section",
        body = "A variable has been edited in the document My Document in section Overview",
        localUrl = webAppUrls.document(documentId, sectionVariableId),
        userId = user.userId,
        organizationId = null)
  }

  @Test
  fun `is a listener for Module Event Starting event`() {
    assertIsEventListener<ModuleEventStartingEvent>(service)
  }

  @Test
  fun `should store module event starting notification for all projects`() {
    val moduleId = insertModule()
    val eventId = insertEvent(moduleId = moduleId)
    val projectId = insertProject()

    insertOrganizationUser(role = Role.Admin)
    // Other user in same org
    insertOrganizationUser(otherUserId, role = Role.Manager)
    // Contributor in same org; shouldn't be notified
    insertOrganizationUser(insertUser(), role = Role.Contributor)

    // Other project in different org
    val thirdUserId = insertUser()
    val otherOrgId = insertOrganization()
    insertOrganizationUser(thirdUserId, otherOrgId, Role.Manager)
    val otherProjectId = insertProject(organizationId = otherOrgId)

    insertEventProject(eventId, projectId)
    insertEventProject(eventId, otherProjectId)

    val commonValues =
        NotificationsRow(
            body = "Click the join Workshop button to join the video call for Module 1",
            localUrl = webAppUrls.moduleEvent(moduleId, eventId, organizationId, projectId),
            notificationTypeId = NotificationType.EventReminder,
            organizationId = organizationId,
            title = "Your Workshop will start in 15 minutes",
        )

    testMultipleEventNotifications(
        ModuleEventStartingEvent(eventId),
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
    val moduleId = insertModule()
    val eventId = insertEvent(moduleId = moduleId, eventType = EventType.RecordedSession)
    val projectId = insertProject()
    insertEventProject(eventId, projectId)

    testEventNotification(
        ModuleEventStartingEvent(eventId),
        type = NotificationType.EventReminder,
        title = "Your Recorded Session is ready to view",
        body = "Click the View button to view the recorded session for Module 1",
        localUrl = webAppUrls.moduleEvent(moduleId, eventId, organizationId, projectId),
        role = Role.Admin)
  }

  @Test
  fun `should render notifications in locale of user`() {
    val gibberishUserId = insertUser(locale = Locale.forLanguageTag("es"))

    testEventNotification(
        UserAddedToOrganizationEvent(gibberishUserId, organizationId, user.userId),
        type = NotificationType.UserAddedToOrganization,
        title = "¡Has sido añadido a una nueva organización!",
        body = "Ahora eres un miembro de Organization 1. ¡Bienvenido!",
        webAppUrls.organizationHome(organizationId),
        organizationId = null,
        userId = gibberishUserId)
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

    assertEquals(expectedWithDefaults.toSet(), actual.toSet())
    assertEquals(expected.size, actual.size, "Number of notifications")
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

  private inline fun <reified T> testEventNotification(
      event: T,
      type: NotificationType,
      title: String,
      body: String,
      localUrl: URI,
      organizationId: OrganizationId? = this.organizationId,
      userId: UserId = user.userId,
      role: Role = Role.Contributor,
  ) {
    insertOrganizationUser(
        userId = userId,
        role = role,
    )

    val method =
        service::class.members.find {
          it.name == "on" && it.parameters.size == 2 && it.parameters[1].type.classifier == T::class
        } ?: throw IllegalArgumentException("No matching on() method found for ${T::class}")

    method.call(service, event)

    assertNotification(type, title, body, localUrl, organizationId, userId)

    assertIsEventListener<T>(service)
  }

  private inline fun <reified T> testMultipleEventNotifications(
      event: T,
      expected: Collection<NotificationsRow>
  ) {
    val method =
        service::class.members.find {
          it.name == "on" && it.parameters.size == 2 && it.parameters[1].type.classifier == T::class
        } ?: throw IllegalArgumentException("No matching on() method found for ${T::class}")

    method.call(service, event)

    assertNotifications(expected)

    assertIsEventListener<T>(service)
  }
}
