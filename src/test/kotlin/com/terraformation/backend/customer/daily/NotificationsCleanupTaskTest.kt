package com.terraformation.backend.customer.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.NotificationId
import com.terraformation.backend.db.UserId
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class NotificationsCleanupTaskTest : DatabaseTest() {
  private val clock: Clock = mockk()
  private val config: TerrawareServerConfig = mockk()
  private val now = Instant.parse("2021-01-01T00:00:00Z")

  private lateinit var notificationsCleanupTask: NotificationsCleanupTask

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns now
    every { config.notificationsCleanup.enabled } returns true
    every { config.notificationsCleanup.retentionDays } returns 1

    notificationsCleanupTask = NotificationsCleanupTask(clock, config, dslContext)
  }

  @Test
  fun `Does not delete notifications if there are none expired`() {
    every { config.notificationsCleanup.retentionDays } returns 5

    insertNotification(NotificationId(1), UserId(1), createdTime = now)

    val expected = notificationsDao.findAll()
    assertTrue(
        expected.find { row -> row.id == NotificationId(1) } != null,
        "Expected to find notification with id 1.")

    notificationsCleanupTask.cleanup(DailyTaskTimeArrivedEvent())

    val actual = notificationsDao.findAll()
    assertFalse(actual.isEmpty(), "Did not find any notifications but expected to.")
    assertEquals(expected, actual, "Notifications were deleted but shouldn't have.")
  }

  @Test
  fun `Deletes expired notifications`() {
    every { config.notificationsCleanup.retentionDays } returns 5

    insertNotification(NotificationId(1), UserId(1), createdTime = now)
    insertNotification(NotificationId(2), UserId(1), createdTime = now.minus(Duration.ofDays(6)))

    val before = notificationsDao.findAll()
    assertTrue(
        before.find { row -> row.id == NotificationId(1) } != null,
        "Expected to find notification with id 1.")
    assertTrue(
        before.find { row -> row.id == NotificationId(2) } != null,
        "Expected to find notification with id 2.")

    val expected = before.filter { row -> row.id != NotificationId(2) }

    notificationsCleanupTask.cleanup(DailyTaskTimeArrivedEvent())

    val actual = notificationsDao.findAll()
    assertFalse(actual.isEmpty(), "Did not find any notifications but expected to.")
    assertEquals(expected, actual, "Expected one notification to have been deleted.")
  }
}
