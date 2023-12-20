package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.event.UserDeletionStartedEvent
import com.terraformation.backend.customer.model.CreateNotificationModel
import com.terraformation.backend.customer.model.NotificationModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.NotificationNotFoundException
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.NOTIFICATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.mockUser
import io.mockk.every
import java.net.URI
import java.time.Instant
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class NotificationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(NOTIFICATIONS, ORGANIZATIONS)

  private val clock = TestClock()
  private lateinit var permissionStore: PermissionStore
  private lateinit var store: NotificationStore

  private fun notificationModel(
      globalNotification: Boolean = false,
      userId: UserId = user.userId
  ): CreateNotificationModel {
    val orgId: OrganizationId? = if (globalNotification) null else organizationId
    return CreateNotificationModel(
        NotificationType.UserAddedToOrganization,
        userId,
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

    every { user.canReadOrganization(any()) } returns true
    every { user.canCreateNotification(any(), any()) } returns true
    every { user.canReadNotification(any()) } returns true
    every { user.canListNotifications(any()) } returns true
    every { user.canUpdateNotification(any()) } returns true
    every { user.canUpdateNotifications(any()) } returns true
    every { user.canCountNotifications() } returns true

    every { user.organizationRoles } returns mapOf(organizationId to Role.Owner)

    insertSiteData()
  }

  @Test
  fun `should create a notification`() {
    assertEquals(
        0,
        dslContext.fetchCount(NOTIFICATIONS),
        "Expected count of 0 notifications when none were created")
    store.create(notificationModel(), organizationId)
    assertEquals(
        1,
        dslContext.fetchCount(NOTIFICATIONS),
        "Expected count of 1 notification after one was created ")
  }

  @Test
  fun `should fetch a notification by id`() {
    val toCreate = notificationModel()
    val id = NotificationId(1)
    val expected = toModel(toCreate, id)
    store.create(toCreate, organizationId)
    assertEquals(
        expected, store.fetchById(id), "Notification fetched by id does not match what was created")
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `fetchByOrganization should return notifications with the correct scope`(
      globalNotifications: Boolean
  ) {
    val orgId = if (globalNotifications) null else organizationId
    val model = notificationModel(globalNotifications)
    val expected = (1..5).map { id -> toModel(model, NotificationId(id.toLong())) }

    repeat(5) { store.create(model, organizationId) }

    assertEquals(
        expected,
        store.fetchByOrganization(orgId),
        "Listed organizations do not match what was created")
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `markAllRead by organization should affect notifications with the correct scope`(
      globalNotifications: Boolean
  ) {
    val orgId = if (globalNotifications) null else organizationId
    val id1 = NotificationId(1)
    val id2 = NotificationId(2)

    // create 2 notifications of each type
    store.create(notificationModel(globalNotifications), organizationId)
    store.create(notificationModel(globalNotifications), organizationId)
    store.create(notificationModel(!globalNotifications), organizationId)
    store.create(notificationModel(!globalNotifications), organizationId)

    assertFalse(store.fetchById(id1).isRead, "Notification-1 is read when it should be unread")
    assertFalse(store.fetchById(id2).isRead, "Notification-2 is read when it should be unread")

    // mark all as read
    store.markAllRead(true, orgId)

    assertTrue(store.fetchById(id1).isRead, "Notification id-1 is unread after marking as read")
    assertTrue(store.fetchById(id2).isRead, "Notification id-2 is unread after marking as read")

    // mark all as unread
    store.markAllRead(false, orgId)

    assertFalse(
        store.fetchById(id1).isRead, "Notification id-1 is still read after marking as unread")
    assertFalse(
        store.fetchById(id2).isRead, "Notification id-2 is still read after marking as unread")
  }

  @Test
  fun `should mark a notification as read`() {
    val id = NotificationId(1)

    // create a notification
    store.create(notificationModel(), organizationId)

    assertFalse(store.fetchById(id).isRead, "Expected notification to be unread upon create")

    // mark as read
    store.markRead(true, id)

    assertTrue(store.fetchById(id).isRead, "Expected notification to be read after marking as read")

    // mark as unread
    store.markRead(false, id)

    assertFalse(
        store.fetchById(id).isRead, "Expected notification to be unread after marking as unread")
  }

  @Test
  fun `should return count information on unread notifications`() {
    val id = NotificationId(1)

    // create 2 notifications of each type
    store.create(notificationModel(), organizationId)
    store.create(notificationModel(), organizationId)
    store.create(notificationModel(true), organizationId)
    store.create(notificationModel(true), organizationId)

    var result = store.count()

    var forOrg = result.firstOrNull { r -> r.organizationId == organizationId }
    assertNotNull(forOrg, "Did not find unread count for organization notifications")
    assertEquals(2, forOrg!!.unread, "Unread count mismatch for organization notifications")

    var forGlobal = result.firstOrNull { r -> r.organizationId == null }
    assertNotNull(forGlobal, "Did not find unread count for global notifications")
    assertEquals(2, forGlobal!!.unread, "Unread count mismatch for global notifications")

    // mark first notification as read
    store.markRead(true, id)

    result = store.count()

    forOrg = result.firstOrNull { r -> r.organizationId == organizationId }
    assertNotNull(
        forOrg,
        "Did not find unread count for organization notifications after marking some as read")
    assertEquals(
        1,
        forOrg!!.unread,
        "Unread count mismatch for organization notifications after marking some as read")

    forGlobal = result.firstOrNull { r -> r.organizationId == null }
    assertNotNull(
        forGlobal, "Did not find unread count for global notifications after marking some as read")
    assertEquals(
        2,
        forGlobal!!.unread,
        "Unread count mismatch for global notifications after marking some as read")
  }

  @Test
  fun `should throw an error when reading notifications belonging to another user`() {
    val otherUserId = insertUser(3)
    assertThrows<NotificationNotFoundException> {
      val notification = notificationModel(userId = otherUserId)
      store.create(notification, organizationId)
      store.fetchById(NotificationId(1))
    }
  }

  @Test
  fun `should delete user notifications when user is deleted`() {
    val otherUserId = insertUser(3)

    store.create(notificationModel(userId = otherUserId), organizationId)

    val notificationsForOtherUser = notificationsDao.findAll()

    store.create(notificationModel(), organizationId)
    store.create(notificationModel(true), organizationId)

    store.on(UserDeletionStartedEvent(user.userId))

    assertEquals(notificationsForOtherUser, notificationsDao.findAll())
  }
}
