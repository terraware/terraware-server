package com.terraformation.backend.customer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.AppDeviceStore
import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.NotificationStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.GerminationTestType
import com.terraformation.backend.db.NotificationId
import com.terraformation.backend.db.NotificationType
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.pojos.NotificationsRow
import com.terraformation.backend.db.tables.references.NOTIFICATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.event.DeviceUnresponsiveEvent
import com.terraformation.backend.device.event.SensorBoundsAlertTriggeredEvent
import com.terraformation.backend.device.event.UnknownAutomationTriggeredEvent
import com.terraformation.backend.email.WebAppUrls
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.NotificationMessage
import com.terraformation.backend.mockUser
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.db.BagStore
import com.terraformation.backend.seedbank.db.GeolocationStore
import com.terraformation.backend.seedbank.db.GerminationStore
import com.terraformation.backend.seedbank.db.WithdrawalStore
import com.terraformation.backend.seedbank.event.AccessionDryingEndEvent
import com.terraformation.backend.seedbank.event.AccessionGerminationTestEvent
import com.terraformation.backend.seedbank.event.AccessionMoveToDryEvent
import com.terraformation.backend.seedbank.event.AccessionWithdrawalEvent
import com.terraformation.backend.seedbank.event.AccessionsAwaitingProcessingEvent
import com.terraformation.backend.seedbank.event.AccessionsFinishedDryingEvent
import com.terraformation.backend.seedbank.event.AccessionsReadyForTestingEvent
import com.terraformation.backend.seedbank.model.AccessionModel
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.keycloak.admin.client.resource.RealmResource
import org.springframework.beans.factory.annotation.Autowired

internal class AppNotificationServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(ORGANIZATIONS, NOTIFICATIONS)

  @Autowired private lateinit var config: TerrawareServerConfig

  private val otherUserId = UserId(100)

  private val clock: Clock = mockk()
  private val messages: Messages = mockk()
  private val realmResource: RealmResource = mockk()

  private lateinit var accessionStore: AccessionStore
  private lateinit var automationStore: AutomationStore
  private lateinit var deviceStore: DeviceStore
  private lateinit var facilityStore: FacilityStore
  private lateinit var notificationStore: NotificationStore
  private lateinit var organizationStore: OrganizationStore
  private lateinit var parentStore: ParentStore
  private lateinit var userStore: UserStore
  private lateinit var service: AppNotificationService
  private lateinit var webAppUrls: WebAppUrls

  @BeforeEach
  fun setUp() {
    val objectMapper = jacksonObjectMapper()

    every { realmResource.users() } returns mockk()

    notificationStore = NotificationStore(dslContext, clock)
    organizationStore = OrganizationStore(clock, dslContext, organizationsDao)
    parentStore = ParentStore(dslContext)
    accessionStore =
        AccessionStore(
            dslContext,
            AppDeviceStore(dslContext, clock),
            BagStore(dslContext),
            GeolocationStore(dslContext, clock),
            GerminationStore(dslContext),
            parentStore,
            mockk(),
            WithdrawalStore(dslContext, clock),
            clock,
        )
    automationStore = AutomationStore(automationsDao, clock, dslContext, objectMapper, parentStore)
    deviceStore = DeviceStore(devicesDao)
    facilityStore = FacilityStore(clock, dslContext, facilitiesDao, storageLocationsDao)
    userStore =
        UserStore(
            clock,
            config,
            dslContext,
            mockk(),
            mockk(),
            objectMapper,
            organizationStore,
            ParentStore(dslContext),
            PermissionStore(dslContext),
            realmResource,
            usersDao,
        )
    webAppUrls = WebAppUrls(config)
    service =
        AppNotificationService(
            automationStore,
            deviceStore,
            dslContext,
            facilityStore,
            notificationStore,
            organizationStore,
            parentStore,
            userStore,
            messages,
            webAppUrls)

    every { clock.instant() } returns Instant.EPOCH
    every { clock.zone } returns ZoneOffset.UTC
    every { messages.userAddedToOrganizationNotification(any()) } returns
        NotificationMessage("organization title", "organization body")
    every { messages.accessionMoveToDryNotification(any()) } returns
        NotificationMessage("accession title", "accession body")
    every { messages.accessionDryingEndNotification(any()) } returns
        NotificationMessage("accession title", "accession body")
    every { messages.accessionGerminationTestNotification(any(), GerminationTestType.Lab) } returns
        NotificationMessage(
            "accession lab germination test title", "accession lab germination test body")
    every { messages.accessionsAwaitingProcessing(any()) } returns
        NotificationMessage(
            "accessions awaiting processing title", "accessions awaiting processing body")
    every { messages.accessionsReadyForTesting(any(), any()) } returns
        NotificationMessage(
            "accessions ready for testing title", "accessions ready for testing body")
    every { messages.accessionsFinishedDrying(any()) } returns
        NotificationMessage("accessions finished drying title", "accessions finished drying body")
    every {
      messages.accessionGerminationTestNotification(any(), GerminationTestType.Nursery)
    } returns
        NotificationMessage(
            "accession nursery germination test title", "accession nursery germination test body")
    every { messages.accessionWithdrawalNotification(any()) } returns
        NotificationMessage("accession title", "accession body")
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
    every { user.organizationRoles } returns mapOf(organizationId to Role.ADMIN)

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

    val expectedNotifications =
        listOf(
            NotificationsRow(
                id = NotificationId(1),
                notificationTypeId = NotificationType.UserAddedtoOrganization,
                userId = otherUserId,
                organizationId = null,
                title = "organization title",
                body = "organization body",
                localUrl = webAppUrls.organizationHome(organizationId),
                createdTime = Instant.EPOCH,
                isRead = false))

    val actualNotifications = notificationsDao.findAll()

    assertEquals(
        expectedNotifications,
        actualNotifications,
        "Notifications should match that of a single user added to an organization")
  }

  @Test
  fun `should store accession move to drying racks notification`() {
    // add a second user to check for multiple notifications
    insertUser(otherUserId)
    insertOrganizationUser()
    insertOrganizationUser(otherUserId)
    insertProjectUser()
    insertProjectUser(otherUserId)

    val accessionModel = accessionStore.create(AccessionModel(facilityId = facilityId))
    assertNotNull(accessionModel)

    service.on(AccessionMoveToDryEvent(accessionModel.accessionNumber!!, accessionModel.id!!))

    val expectedNotifications =
        listOf(
            NotificationsRow(
                id = NotificationId(1),
                notificationTypeId = NotificationType.AccessionScheduledforDrying,
                userId = user.userId,
                organizationId = organizationId,
                title = "accession title",
                body = "accession body",
                localUrl = webAppUrls.accession(accessionModel.id!!),
                createdTime = Instant.EPOCH,
                isRead = false),
            NotificationsRow(
                id = NotificationId(2),
                notificationTypeId = NotificationType.AccessionScheduledforDrying,
                userId = otherUserId,
                organizationId = organizationId,
                title = "accession title",
                body = "accession body",
                localUrl = webAppUrls.accession(accessionModel.id!!),
                createdTime = Instant.EPOCH,
                isRead = false),
        )

    val actualNotifications = notificationsDao.findAll()

    assertEquals(
        expectedNotifications,
        actualNotifications,
        "Notification should match that of an accession move from racks to drying cabinets")
  }

  @Test
  fun `should store accession drying end date notification`() {
    // add a second user to check for multiple notifications
    insertUser(otherUserId)
    insertOrganizationUser()
    insertProjectUser()

    val accessionModel = accessionStore.create(AccessionModel(facilityId = facilityId))
    assertNotNull(accessionModel)

    service.on(AccessionDryingEndEvent(accessionModel.accessionNumber!!, accessionModel.id!!))

    val expectedNotifications =
        listOf(
            NotificationsRow(
                id = NotificationId(1),
                notificationTypeId = NotificationType.AccessionScheduledtoEndDrying,
                userId = user.userId,
                organizationId = organizationId,
                title = "accession title",
                body = "accession body",
                localUrl = webAppUrls.accession(accessionModel.id!!),
                createdTime = Instant.EPOCH,
                isRead = false))

    val actualNotifications = notificationsDao.findAll()

    assertEquals(
        expectedNotifications,
        actualNotifications,
        "Notification should match that of an accession scheduled to end drying")
  }

  @Test
  fun `should store accession germination test in a lab, notification`() {
    // add a second user to check for multiple notifications
    insertUser(otherUserId)
    insertOrganizationUser()
    insertProjectUser()

    val accessionModel = accessionStore.create(AccessionModel(facilityId = facilityId))
    assertNotNull(accessionModel)

    service.on(
        AccessionGerminationTestEvent(
            accessionModel.accessionNumber!!, accessionModel.id!!, GerminationTestType.Lab))

    val expectedNotifications =
        listOf(
            NotificationsRow(
                id = NotificationId(1),
                notificationTypeId = NotificationType.AccessionScheduledforGerminationTest,
                userId = user.userId,
                organizationId = organizationId,
                title = "accession lab germination test title",
                body = "accession lab germination test body",
                localUrl =
                    webAppUrls.accessionGerminationTest(
                        accessionModel.id!!, GerminationTestType.Lab),
                createdTime = Instant.EPOCH,
                isRead = false))

    val actualNotifications = notificationsDao.findAll()

    assertEquals(
        expectedNotifications,
        actualNotifications,
        "Notification should match that of an accession scheduled for germination test in a lab")
  }

  @Test
  fun `should store accession germination test in a nursery, notification`() {
    // add a second user to check for multiple notifications
    insertUser(otherUserId)
    insertOrganizationUser()
    insertProjectUser()

    val accessionModel = accessionStore.create(AccessionModel(facilityId = facilityId))
    assertNotNull(accessionModel)

    service.on(
        AccessionGerminationTestEvent(
            accessionModel.accessionNumber!!, accessionModel.id!!, GerminationTestType.Nursery))

    val expectedNotifications =
        listOf(
            NotificationsRow(
                id = NotificationId(1),
                notificationTypeId = NotificationType.AccessionScheduledforGerminationTest,
                userId = user.userId,
                organizationId = organizationId,
                title = "accession nursery germination test title",
                body = "accession nursery germination test body",
                localUrl =
                    webAppUrls.accessionGerminationTest(
                        accessionModel.id!!, GerminationTestType.Nursery),
                createdTime = Instant.EPOCH,
                isRead = false))

    val actualNotifications = notificationsDao.findAll()

    assertEquals(
        expectedNotifications,
        actualNotifications,
        "Notification should match that of an accession scheduled for germination test in a nursery")
  }

  @Test
  fun `should store accession scheduled for withdrawal notification`() {
    // add a second user to check for multiple notifications
    insertUser(otherUserId)
    insertOrganizationUser()
    insertProjectUser()

    val accessionModel = accessionStore.create(AccessionModel(facilityId = facilityId))
    assertNotNull(accessionModel)

    service.on(AccessionWithdrawalEvent(accessionModel.accessionNumber!!, accessionModel.id!!))

    val expectedNotifications =
        listOf(
            NotificationsRow(
                id = NotificationId(1),
                notificationTypeId = NotificationType.AccessionScheduledforWithdrawal,
                userId = user.userId,
                organizationId = organizationId,
                title = "accession title",
                body = "accession body",
                localUrl = webAppUrls.accession(accessionModel.id!!),
                createdTime = Instant.EPOCH,
                isRead = false))

    val actualNotifications = notificationsDao.findAll()

    assertEquals(
        expectedNotifications,
        actualNotifications,
        "Notification should match that of an accession scheduled for withdrawal")
  }

  @Test
  fun `should store accessions awaiting processing notification`() {
    // add a second user to check for multiple notifications
    insertUser(otherUserId)
    insertOrganizationUser()
    insertProjectUser()

    service.on(AccessionsAwaitingProcessingEvent(facilityId, 5, AccessionState.Pending))

    val expectedNotifications =
        listOf(
            NotificationsRow(
                id = NotificationId(1),
                notificationTypeId = NotificationType.AccessionsAwaitingProcessing,
                userId = user.userId,
                organizationId = organizationId,
                title = "accessions awaiting processing title",
                body = "accessions awaiting processing body",
                localUrl = webAppUrls.accessions(facilityId, AccessionState.Pending),
                createdTime = Instant.EPOCH,
                isRead = false))

    val actualNotifications = notificationsDao.findAll()

    assertEquals(
        expectedNotifications,
        actualNotifications,
        "Notification should match that of accessions awaiting processing")
  }

  @Test
  fun `should store accessions ready for testing notification`() {
    insertOrganizationUser()
    insertProjectUser()

    service.on(AccessionsReadyForTestingEvent(facilityId, 5, 2, AccessionState.Processed))

    val expectedNotifications =
        listOf(
            NotificationsRow(
                id = NotificationId(1),
                notificationTypeId = NotificationType.AccessionsReadyforTesting,
                userId = user.userId,
                organizationId = organizationId,
                title = "accessions ready for testing title",
                body = "accessions ready for testing body",
                localUrl = webAppUrls.accessions(facilityId, AccessionState.Processed),
                createdTime = Instant.EPOCH,
                isRead = false))

    val actualNotifications = notificationsDao.findAll()

    assertEquals(
        expectedNotifications,
        actualNotifications,
        "Notification should match that of accessions ready for testing")
  }

  @Test
  fun `should store accessions finished drying notification`() {
    insertOrganizationUser()
    insertProjectUser()

    service.on(AccessionsFinishedDryingEvent(facilityId, 5, AccessionState.Dried))

    val expectedNotifications =
        listOf(
            NotificationsRow(
                id = NotificationId(1),
                notificationTypeId = NotificationType.AccessionsFinishedDrying,
                userId = user.userId,
                organizationId = organizationId,
                title = "accessions finished drying title",
                body = "accessions finished drying body",
                localUrl = webAppUrls.accessions(facilityId, AccessionState.Dried),
                createdTime = Instant.EPOCH,
                isRead = false))

    val actualNotifications = notificationsDao.findAll()

    assertEquals(
        expectedNotifications,
        actualNotifications,
        "Notification should match that of accessions finished drying")
  }

  @Test
  fun `should store facility idle notification`() {
    insertOrganizationUser()
    insertProjectUser()

    service.on(FacilityIdleEvent(facilityId))

    val expectedNotifications =
        listOf(
            NotificationsRow(
                id = NotificationId(1),
                notificationTypeId = NotificationType.FacilityIdle,
                userId = user.userId,
                organizationId = organizationId,
                title = "facility idle title",
                body = "facility idle body",
                localUrl = webAppUrls.facilityMonitoring(facilityId),
                createdTime = Instant.EPOCH,
                isRead = false))

    val actualNotifications = notificationsDao.findAll()

    assertEquals(
        expectedNotifications,
        actualNotifications,
        "Notification should match that of facility idle")
  }

  @Test
  fun `should store sensor bounds alert notification`() {
    val automationId = AutomationId(1)
    val deviceId = DeviceId(1)
    val timeseriesName = "test timeseries"
    val facilityName = "Facility $facilityId"
    val badValue = 5.678

    insertOrganizationUser()
    insertProjectUser()
    insertDevice(deviceId)
    insertAutomation(automationId, deviceId = deviceId, timeseriesName = timeseriesName)

    every {
      messages.sensorBoundsAlert(
          devicesDao.fetchOneById(deviceId)!!, facilityName, timeseriesName, badValue)
    } returns NotificationMessage("bounds title", "bounds body")

    service.on(SensorBoundsAlertTriggeredEvent(automationId, badValue))

    val expectedNotifications =
        listOf(
            NotificationsRow(
                id = NotificationId(1),
                notificationTypeId = NotificationType.SensorOutOfBounds,
                userId = user.userId,
                organizationId = organizationId,
                title = "bounds title",
                body = "bounds body",
                localUrl = webAppUrls.facilityMonitoring(facilityId),
                createdTime = Instant.EPOCH,
                isRead = false))

    val actualNotifications = notificationsDao.findAll()

    assertEquals(expectedNotifications, actualNotifications)
  }

  @Test
  fun `should store unknown automation triggered notification`() {
    val automationId = AutomationId(1)
    val automationName = "automation name"
    val automationType = "unknown"
    val facilityName = "Facility $facilityId"
    val message = "message"

    insertOrganizationUser()
    insertProjectUser()
    insertAutomation(automationId, name = automationName, type = automationType, deviceId = null)

    val title = "Automation $automationId triggered at $facilityName"
    every { messages.unknownAutomationTriggered(automationName, facilityName, message) } returns
        NotificationMessage(title, message)

    service.on(UnknownAutomationTriggeredEvent(automationId, automationType, message))

    val expectedNotifications =
        listOf(
            NotificationsRow(
                id = NotificationId(1),
                notificationTypeId = NotificationType.UnknownAutomationTriggered,
                userId = user.userId,
                organizationId = organizationId,
                title = title,
                body = message,
                localUrl = webAppUrls.facilityMonitoring(facilityId),
                createdTime = Instant.EPOCH,
                isRead = false))

    val actualNotifications = notificationsDao.findAll()

    assertEquals(expectedNotifications, actualNotifications)
  }

  @Test
  fun `should store device unresponsive notification`() {
    val deviceId = DeviceId(1)
    val deviceName = "test device"

    insertOrganizationUser()
    insertProjectUser()
    insertDevice(deviceId, name = deviceName, type = "sensor")

    every { messages.deviceUnresponsive(deviceName) } returns
        NotificationMessage("unresponsive title", "unresponsive body")

    service.on(DeviceUnresponsiveEvent(deviceId, Instant.EPOCH, Duration.ofSeconds(1)))

    val device = deviceStore.fetchOneById(deviceId)
    val expectedNotifications =
        listOf(
            NotificationsRow(
                id = NotificationId(1),
                notificationTypeId = NotificationType.DeviceUnresponsive,
                userId = user.userId,
                organizationId = organizationId,
                title = "unresponsive title",
                body = "unresponsive body",
                localUrl = webAppUrls.facilityMonitoring(facilityId, device),
                createdTime = Instant.EPOCH,
                isRead = false))

    val actualNotifications = notificationsDao.findAll()

    assertEquals(expectedNotifications, actualNotifications)
  }
}
