package com.terraformation.backend.customer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.config.TerrawareServerConfig
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
import com.terraformation.backend.db.NotificationId
import com.terraformation.backend.db.NotificationType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.references.NOTIFICATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.email.WebAppUrls
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.NotificationMessage
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.keycloak.admin.client.resource.RealmResource
import org.springframework.beans.factory.annotation.Autowired

internal class AppNotificationServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(ORGANIZATIONS, PROJECTS, NOTIFICATIONS)

  @Autowired private lateinit var config: TerrawareServerConfig

  private val organizationId = OrganizationId(1)
  private val projectId = ProjectId(2)
  private val otherUserId = UserId(100)

  private val clock: Clock = mockk()
  private val messages: Messages = mockk()
  private val realmResource: RealmResource = mockk()

  private lateinit var notificationStore: NotificationStore
  private lateinit var organizationStore: OrganizationStore
  private lateinit var projectStore: ProjectStore
  private lateinit var userStore: UserStore
  private lateinit var service: AppNotificationService
  private lateinit var webAppUrls: WebAppUrls

  @BeforeEach
  fun setUp() {
    every { realmResource.users() } returns mockk()

    notificationStore = NotificationStore(dslContext, clock)
    organizationStore = OrganizationStore(clock, dslContext, organizationsDao)
    projectStore = ProjectStore(clock, dslContext, projectsDao, projectTypeSelectionsDao)
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
            notificationStore, organizationStore, projectStore, userStore, messages, webAppUrls)

    every { clock.instant() } returns Instant.EPOCH
    every { messages.userAddedToOrganizationNotification(any()) } returns
        NotificationMessage("organization title", "organization body")
    every { messages.userAddedToProjectNotification(any()) } returns
        NotificationMessage("project title", "project body")
    every { user.canReadOrganization(organizationId) } returns true
    every { user.canCreateNotification(otherUserId, organizationId) } returns true
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

    assertEquals(0, notificationsDao.count())

    service.on(UserAddedToOrganizationEvent(otherUserId, organizationId, user.userId))

    assertEquals(1, notificationsDao.count())
    val notification = notificationsDao.fetchById(NotificationId(1)).firstOrNull()!!
    assertEquals(NotificationType.UserAddedtoOrganization, notification.notificationTypeId)
    assertEquals(otherUserId, notification.userId)
    assertNull(notification.organizationId)
    assertEquals("organization title", notification.title)
    assertEquals("organization body", notification.body)
    assertEquals(webAppUrls.organizationHome(organizationId), notification.localUrl)
  }

  @Test
  fun `should store a notification of type User Added To Project`() {
    insertUser(otherUserId)
    insertOrganizationUser(otherUserId, organizationId, Role.CONTRIBUTOR)
    insertProjectUser(otherUserId, projectId, user.userId)

    assertEquals(0, notificationsDao.count())

    service.on(UserAddedToProjectEvent(otherUserId, projectId, user.userId))

    assertEquals(1, notificationsDao.count())
    val notification = notificationsDao.fetchById(NotificationId(1)).firstOrNull()!!
    assertEquals(NotificationType.UserAddedToProject, notification.notificationTypeId)
    assertEquals(otherUserId, notification.userId)
    assertEquals(organizationId, notification.organizationId)
    assertEquals("project title", notification.title)
    assertEquals("project body", notification.body)
    assertEquals(webAppUrls.organizationProject(projectId), notification.localUrl)
  }
}
