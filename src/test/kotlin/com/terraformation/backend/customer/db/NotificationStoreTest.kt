package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.CreateNotificationModel
import com.terraformation.backend.customer.model.NotificationModel
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.NotificationId
import com.terraformation.backend.db.NotificationType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectStatus
import com.terraformation.backend.db.ProjectType
import com.terraformation.backend.db.tables.references.NOTIFICATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class NotificationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(NOTIFICATIONS, ORGANIZATIONS)

  private val clock: Clock = mockk()
  private lateinit var permissionStore: PermissionStore
  private lateinit var store: NotificationStore

  private val organizationId = OrganizationId(1)
  private val projectId = ProjectId(10)

  private val projectModel =
      ProjectModel(
          createdTime = Instant.EPOCH,
          description = "Project description $projectId",
          hidden = false,
          id = projectId,
          organizationId = organizationId,
          organizationWide = false,
          name = "Project $projectId",
          sites = emptyList(),
          startDate = LocalDate.EPOCH.plusDays(projectId.value),
          status = ProjectStatus.Planting,
          types = setOf(ProjectType.Agroforestry, ProjectType.SustainableTimber))

  private val organizationModel =
      OrganizationModel(
          createdTime = Instant.EPOCH,
          countryCode = "US",
          countrySubdivisionCode = "US-HI",
          id = organizationId,
          name = "Organization $organizationId",
          projects = listOf(projectModel),
          totalUsers = 0)

  private fun notificationModel(globalNotification: Boolean = false): CreateNotificationModel {
    val orgId: OrganizationId? = if (globalNotification) null else organizationId
    return CreateNotificationModel(
        NotificationType.UserAddedtoOrganization,
        user.userId,
        orgId,
        "the quick brown fox",
        "jumped over the silly lazy goat",
        URI.create("/"))
  }

  private fun toModel(
      created: CreateNotificationModel,
      notificationId: NotificationId
  ): NotificationModel =
      NotificationModel(
          notificationId,
          created.notificationType,
          created.organizationId,
          created.title,
          created.body,
          created.localUrl,
          Instant.EPOCH,
          false)

  @BeforeEach
  fun setUp() {
    permissionStore = PermissionStore(dslContext)
    store = NotificationStore(dslContext, clock)
    every { clock.instant() } returns Instant.EPOCH

    every { user.canReadOrganization(any()) } returns true
    every { user.canCreateNotification(any(), any()) } returns true
    every { user.canReadNotification(any()) } returns true
    every { user.canListNotifications(any()) } returns true
    every { user.canUpdateNotification(any()) } returns true
    every { user.canUpdateNotifications(any()) } returns true
    every { user.canCountNotifications() } returns true

    every { user.organizationRoles } returns mapOf(organizationId to Role.OWNER)
    every { user.projectRoles } returns mapOf(projectId to Role.OWNER)

    insertUser()
    Assertions.assertEquals(
        organizationId,
        insertOrganization(
            name = organizationModel.name,
            countryCode = organizationModel.countryCode,
            countrySubdivisionCode = organizationModel.countrySubdivisionCode))
    insertProject(
        projectId,
        description = projectModel.description,
        startDate = projectModel.startDate,
        status = projectModel.status,
        types = projectModel.types)
  }

  @Test
  fun `should create a notification`() {
    assert(dslContext.fetchCount(NOTIFICATIONS) == 0)
    store.create(notificationModel(), organizationId)
    assert(dslContext.fetchCount(NOTIFICATIONS) == 1)
  }

  @Test
  fun `should fetch a notification by id`() {
    val toCreate = notificationModel()
    val id = NotificationId(1)
    val expected = toModel(toCreate, id)
    store.create(toCreate, organizationId)
    assertEquals(store.fetchById(id), expected)
  }

  @Test
  fun `should list notifications scoped to an organization for user`() {
    // create a few notification model representations
    val toCreate = Array(5, { notificationModel() })
    val expected =
        toCreate.mapIndexed { index, model -> toModel(model, NotificationId(index + 1L)) }

    // persist the notificatios
    toCreate.forEach { notification -> store.create(notification, organizationId) }

    assertEquals(store.fetchByOrganization(organizationId), expected)
  }

  @Test
  fun `should list notifications scoped globally for user`() {
    // create a few notification model representations, set global notification option to true
    val toCreate = Array(5, { notificationModel(true) })
    val expected =
        toCreate.mapIndexed { index, model -> toModel(model, NotificationId(index + 1L)) }

    // persist the notificatios
    toCreate.forEach { notification -> store.create(notification, organizationId) }

    assertEquals(store.fetchByOrganization(null), expected)
  }

  @Test
  fun `should mark a notification as read`() {
    val id = NotificationId(1)

    // create a notification
    store.create(notificationModel(), organizationId)

    // confirm it is unread
    assertFalse(store.fetchById(id).isRead)

    // mark as read
    store.markRead(true, id)

    // confirm it is read
    assertTrue(store.fetchById(id).isRead)

    // mark as unread
    store.markRead(false, id)

    // confirm it is unread
    assertFalse(store.fetchById(id).isRead)
  }

  @Test
  fun `should mark all notifications, scoped to an organization for user, as read`() {
    val id1 = NotificationId(1)
    val id2 = NotificationId(2)

    // create 2 notifications
    store.create(notificationModel(), organizationId)
    store.create(notificationModel(), organizationId)

    // confirm they are unread
    assertFalse(store.fetchById(id1).isRead)
    assertFalse(store.fetchById(id2).isRead)

    // mark all as read
    store.markAllRead(true, organizationId)

    // confirm they are read
    assertTrue(store.fetchById(id1).isRead)
    assertTrue(store.fetchById(id2).isRead)

    // mark all as unread
    store.markAllRead(false, organizationId)

    // confirm they are unread
    assertFalse(store.fetchById(id1).isRead)
    assertFalse(store.fetchById(id2).isRead)
  }

  @Test
  fun `should mark all notifications, scoped globally for user, as read`() {
    val id1 = NotificationId(1)
    val id2 = NotificationId(2)

    // create 2 notifications
    store.create(notificationModel(true), organizationId)
    store.create(notificationModel(true), organizationId)

    // confirm they are unread
    assertFalse(store.fetchById(id1).isRead)
    assertFalse(store.fetchById(id2).isRead)

    // mark all as read
    store.markAllRead(true, null)

    // confirm they are read
    assertTrue(store.fetchById(id1).isRead)
    assertTrue(store.fetchById(id2).isRead)

    // mark all as unread
    store.markAllRead(false, null)

    // confirm they are unread
    assertFalse(store.fetchById(id1).isRead)
    assertFalse(store.fetchById(id2).isRead)
  }

  @Test
  fun `should return count information on unread notifications`() {
    val id = NotificationId(1)

    // create 2 notifications
    store.create(notificationModel(), organizationId)
    store.create(notificationModel(), organizationId)
    store.create(notificationModel(true), organizationId)
    store.create(notificationModel(true), organizationId)

    // confirm unread count (2 for org, 2 for global)
    var result = store.count()

    // get unread count for org
    var forOrg = result.filter { r -> r.organizationId == organizationId }[0]
    assertNotNull(forOrg)
    assertEquals(forOrg.unread, 2)

    // get unread count for global
    var forGlobal = result.filter { r -> r.organizationId == null }[0]
    assertNotNull(forGlobal)
    assertEquals(forGlobal.unread, 2)

    // mark first notification as read
    store.markRead(true, id)

    // confirm new unread count (1 for org, 2 for global)
    result = store.count()

    // get unread count for org
    forOrg = result.filter { r -> r.organizationId == organizationId }[0]
    assertNotNull(forOrg)
    assertEquals(forOrg.unread, 1)

    // get unread count for global
    forGlobal = result.filter { r -> r.organizationId == null }[0]
    assertNotNull(forGlobal)
    assertEquals(forGlobal.unread, 2)
  }
}
