package com.terraformation.backend.customer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.auth.InMemoryKeycloakAdminClient
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.NotificationStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.event.UserAddedToTerrawareEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.ReportStatus
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.NotificationsRow
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.event.DeviceUnresponsiveEvent
import com.terraformation.backend.device.event.SensorBoundsAlertTriggeredEvent
import com.terraformation.backend.device.event.UnknownAutomationTriggeredEvent
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
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.event.ObservationStartedEvent
import com.terraformation.backend.tracking.event.ObservationUpcomingNotificationDueEvent
import com.terraformation.backend.tracking.event.PlantingSeasonNotScheduledNotificationEvent
import com.terraformation.backend.tracking.event.PlantingSeasonStartedEvent
import com.terraformation.backend.tracking.event.ScheduleObservationNotificationEvent
import com.terraformation.backend.tracking.event.ScheduleObservationReminderNotificationEvent
import com.terraformation.backend.tracking.model.ExistingObservationModel
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

internal class AppNotificationServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  @Autowired private lateinit var config: TerrawareServerConfig

  private val otherUserId = UserId(100)

  private val clock = TestClock()
  private val messages: Messages = mockk()

  private lateinit var accessionStore: AccessionStore
  private lateinit var automationStore: AutomationStore
  private lateinit var deviceStore: DeviceStore
  private lateinit var facilityStore: FacilityStore
  private lateinit var notificationStore: NotificationStore
  private lateinit var organizationStore: OrganizationStore
  private lateinit var parentStore: ParentStore
  private lateinit var plantingSiteStore: PlantingSiteStore
  private lateinit var userStore: UserStore
  private lateinit var service: AppNotificationService
  private lateinit var webAppUrls: WebAppUrls

  @BeforeEach
  fun setUp() {
    val objectMapper = jacksonObjectMapper()
    val publisher = TestEventPublisher()

    notificationStore = NotificationStore(dslContext, clock)
    organizationStore = OrganizationStore(clock, dslContext, organizationsDao, publisher)
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
            deviceStore,
            dslContext,
            facilityStore,
            notificationStore,
            organizationStore,
            parentStore,
            plantingSiteStore,
            SystemUser(usersDao),
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
    every { user.canCreateAccession(facilityId) } returns true
    every { user.canCreateAutomation(any()) } returns true
    every { user.canCreateNotification(any(), organizationId) } returns true
    every { user.canReadAccession(any()) } returns true
    every { user.canReadAutomation(any()) } returns true
    every { user.canReadDevice(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canReadOrganization(organizationId) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.locale } returns Locale.ENGLISH
    every { user.organizationRoles } returns mapOf(organizationId to Role.Admin)

    insertSiteData()
  }

  @Test
  fun `should have event listener for User Added To Organization event`() {
    assertIsEventListener<UserAddedToOrganizationEvent>(service)
  }

  @Test
  fun `should store a notification of type User Added To Organization`() {
    insertUser(otherUserId)
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
    insertUser(otherUserId)
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
    // add a second user to check for multiple notifications
    insertUser(otherUserId)
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
    // add a second user to check for multiple notifications
    insertUser(otherUserId)
    insertOrganizationUser()

    val facilityId = FacilityId(1000)
    val nurseryName = "my nursery"
    val speciesId = SpeciesId(100)
    val batchId = BatchId(100)
    val batchNumber = "22-2-001"

    insertFacility(id = facilityId, type = FacilityType.Nursery, name = nurseryName)
    insertSpecies(speciesId)
    insertBatch(
        BatchesRow(
            id = batchId,
            batchNumber = batchNumber,
            speciesId = speciesId,
            facilityId = facilityId))

    service.on(NurserySeedlingBatchReadyEvent(batchId, batchNumber, speciesId, nurseryName))

    assertNotification(
        type = NotificationType.NurserySeedlingBatchReady,
        organizationId = organizationId,
        title = "nursery title",
        body = "nursery body",
        localUrl = webAppUrls.batch(batchNumber, speciesId))
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
    val automationId = AutomationId(1)
    val deviceId = DeviceId(1)
    val timeseriesName = "test timeseries"
    val facilityName = "Facility $facilityId"
    val badValue = 5.678

    insertOrganizationUser()
    insertDevice(deviceId)
    insertAutomation(automationId, deviceId = deviceId, timeseriesName = timeseriesName)

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
    val automationId = AutomationId(1)
    val automationName = "automation name"
    val automationType = "unknown"
    val facilityName = "Facility $facilityId"
    val message = "message"

    insertOrganizationUser()
    insertAutomation(automationId, name = automationName, type = automationType, deviceId = null)

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
    val deviceId = DeviceId(1)
    val deviceName = "test device"

    insertOrganizationUser()
    insertDevice(deviceId, name = deviceName, type = "sensor")

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
    val admin = UserId(100)
    val owner = UserId(101)
    val manager = UserId(102)
    val contributor = UserId(103)

    insertUser(admin)
    insertUser(owner)
    insertUser(manager)
    insertUser(contributor)

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
  fun `should render notifications in locale of user`() {
    insertUser(otherUserId, locale = Locales.GIBBERISH)
    insertOrganizationUser(otherUserId)

    var renderedInLocale: Locale? = null

    every { messages.userAddedToOrganizationNotification(any()) } answers
        {
          renderedInLocale = currentLocale()
          NotificationMessage("x", "y")
        }

    service.on(UserAddedToOrganizationEvent(otherUserId, organizationId, user.userId))

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
