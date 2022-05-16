package com.terraformation.backend.customer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.AppDeviceStore
import com.terraformation.backend.customer.db.NotificationStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.event.UserAddedToProjectEvent
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.GerminationTestType
import com.terraformation.backend.db.NotificationId
import com.terraformation.backend.db.NotificationType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.pojos.NotificationsRow
import com.terraformation.backend.db.tables.references.NOTIFICATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.PROJECTS
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
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.species.db.SpeciesStore
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
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
    get() = listOf(ORGANIZATIONS, PROJECTS, NOTIFICATIONS)

  @Autowired private lateinit var config: TerrawareServerConfig

  private val facilityId = FacilityId(100)
  private val organizationId = OrganizationId(1)
  private val otherUserId = UserId(100)
  private val projectId = ProjectId(2)

  private val clock: Clock = mockk()
  private val messages: Messages = mockk()
  private val realmResource: RealmResource = mockk()

  private lateinit var accessionStore: AccessionStore
  private lateinit var notificationStore: NotificationStore
  private lateinit var organizationStore: OrganizationStore
  private lateinit var parentStore: ParentStore
  private lateinit var projectStore: ProjectStore
  private lateinit var userStore: UserStore
  private lateinit var service: AppNotificationService
  private lateinit var speciesStore: SpeciesStore
  private lateinit var webAppUrls: WebAppUrls

  @BeforeEach
  fun setUp() {
    every { realmResource.users() } returns mockk()

    notificationStore = NotificationStore(dslContext, clock)
    organizationStore = OrganizationStore(clock, dslContext, organizationsDao)
    parentStore = ParentStore(dslContext)
    projectStore = ProjectStore(clock, dslContext, projectsDao, projectTypeSelectionsDao)
    speciesStore = SpeciesStore(clock, dslContext, speciesDao)
    accessionStore =
        AccessionStore(
            dslContext,
            AppDeviceStore(dslContext, clock),
            BagStore(dslContext),
            GeolocationStore(dslContext, clock),
            GerminationStore(dslContext),
            parentStore,
            speciesStore,
            WithdrawalStore(dslContext, clock),
            clock,
        )
    userStore =
        UserStore(
            clock,
            config,
            dslContext,
            mockk(),
            mockk(),
            jacksonObjectMapper(),
            organizationStore,
            ParentStore(dslContext),
            PermissionStore(dslContext),
            realmResource,
            usersDao,
            notificationStore,
        )
    webAppUrls = WebAppUrls(config)
    service =
        AppNotificationService(
            dslContext,
            notificationStore,
            organizationStore,
            parentStore,
            projectStore,
            userStore,
            messages,
            webAppUrls)

    every { clock.instant() } returns Instant.EPOCH
    every { clock.zone } returns ZoneOffset.UTC
    every { messages.userAddedToOrganizationNotification(any()) } returns
        NotificationMessage("organization title", "organization body")
    every { messages.userAddedToProjectNotification(any()) } returns
        NotificationMessage("project title", "project body")
    every { messages.accessionMoveToDryNotification(any()) } returns
        NotificationMessage("accession title", "accession body")
    every { messages.accessionDryingEndNotification(any()) } returns
        NotificationMessage("accession title", "accession body")
    every { messages.accessionGerminationTestNotification(any(), GerminationTestType.Lab) } returns
        NotificationMessage(
            "accession lab germination test title", "accession lab germination test body")
    every {
      messages.accessionGerminationTestNotification(any(), GerminationTestType.Nursery)
    } returns
        NotificationMessage(
            "accession nursery germination test title", "accession nursery germination test body")
    every { messages.accessionWithdrawalNotification(any()) } returns
        NotificationMessage("accession title", "accession body")
    every { user.canCreateAccession(facilityId) } returns true
    every { user.canCreateNotification(any(), organizationId) } returns true
    every { user.canReadOrganization(organizationId) } returns true
    every { user.projectRoles } returns mapOf(projectId to Role.OWNER)
    every { user.organizationRoles } returns mapOf(organizationId to Role.ADMIN)

    insertSiteData()
  }

  @Test
  fun `should have event listener for User Added To Organization event`() {
    assertIsEventListener<UserAddedToOrganizationEvent>(service)
  }

  @Test
  fun `should have event listener for User Added To Project event`() {
    assertIsEventListener<UserAddedToProjectEvent>(service)
  }

  @Test
  fun `should store a notification of type User Added To Organization`() {
    insertUser(otherUserId)
    insertOrganizationUser(otherUserId, organizationId, Role.CONTRIBUTOR)

    assertEquals(
        0,
        notificationsDao.count(),
        "There should be no notifications before any notification event")

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
  fun `should store a notification of type User Added To Project`() {
    insertUser(otherUserId)
    insertOrganizationUser(otherUserId, organizationId, Role.CONTRIBUTOR)
    insertProjectUser(otherUserId, projectId, user.userId)

    assertEquals(
        0,
        notificationsDao.count(),
        "There should be no notifications before any notification event")

    service.on(UserAddedToProjectEvent(otherUserId, projectId, user.userId))

    val expectedNotifications =
        listOf(
            NotificationsRow(
                id = NotificationId(1),
                notificationTypeId = NotificationType.UserAddedtoProject,
                userId = otherUserId,
                organizationId = organizationId,
                title = "project title",
                body = "project body",
                localUrl = webAppUrls.organizationProject(projectId),
                createdTime = Instant.EPOCH,
                isRead = false))

    val actualNotifications = notificationsDao.findAll()

    assertEquals(
        expectedNotifications,
        actualNotifications,
        "Notification should match that of a single user added to a project")
  }

  @Test
  fun `should store accession move to drying racks notification`() {
    // add a second user to check for multiple notifications
    insertUser(otherUserId)
    insertOrganizationUser(user.userId, organizationId, Role.CONTRIBUTOR)
    insertOrganizationUser(otherUserId, organizationId, Role.CONTRIBUTOR)
    insertProjectUser(user.userId, projectId, user.userId)
    insertProjectUser(otherUserId, projectId, user.userId)

    val accessionModel = accessionStore.create(AccessionModel(facilityId = facilityId))
    assertNotNull(accessionModel)

    assertEquals(
        0,
        notificationsDao.count(),
        "There should be no notifications before any notification event")

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
    insertOrganizationUser(user.userId, organizationId, Role.CONTRIBUTOR)
    insertProjectUser(user.userId, projectId, user.userId)

    val accessionModel = accessionStore.create(AccessionModel(facilityId = facilityId))
    assertNotNull(accessionModel)

    assertEquals(
        0,
        notificationsDao.count(),
        "There should be no notifications before any notification event")

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
    insertOrganizationUser(user.userId, organizationId, Role.CONTRIBUTOR)
    insertProjectUser(user.userId, projectId, user.userId)

    val accessionModel = accessionStore.create(AccessionModel(facilityId = facilityId))
    assertNotNull(accessionModel)

    assertEquals(
        0,
        notificationsDao.count(),
        "There should be no notifications before any notification event")

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
    insertOrganizationUser(user.userId, organizationId, Role.CONTRIBUTOR)
    insertProjectUser(user.userId, projectId, user.userId)

    val accessionModel = accessionStore.create(AccessionModel(facilityId = facilityId))
    assertNotNull(accessionModel)

    assertEquals(
        0,
        notificationsDao.count(),
        "There should be no notifications before any notification event")

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
    insertOrganizationUser(user.userId, organizationId, Role.CONTRIBUTOR)
    insertProjectUser(user.userId, projectId, user.userId)

    val accessionModel = accessionStore.create(AccessionModel(facilityId = facilityId))
    assertNotNull(accessionModel)

    assertEquals(
        0,
        notificationsDao.count(),
        "There should be no notifications before any notification event")

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
}
