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
    every { config.notifications.retentionDays } returns 1

    notificationsCleanupTask = NotificationsCleanupTask(clock, config, dslContext)
  }

  @Test
  fun `does not delete notifications if there are none expired`() {
    every { config.notifications.retentionDays } returns 5

    insertNotification(NotificationId(1), UserId(1), createdTime = now)

    val expected = notificationsDao.findAll()
    assertTrue(
        expected.any { it.id == NotificationId(1) }, "Expected to find notification with id 1.")

    notificationsCleanupTask.cleanup(DailyTaskTimeArrivedEvent())

    val actual = notificationsDao.findAll()
    assertEquals(expected, actual, "Notifications were deleted but shouldn't have.")
  }

  @Test
  fun `deletes expired notifications`() {
    every { config.notifications.retentionDays } returns 5

    insertNotification(NotificationId(1), UserId(1), createdTime = now)
    val expected = notificationsDao.findAll()

    insertNotification(NotificationId(2), UserId(1), createdTime = now.minus(Duration.ofDays(6)))

    val beforeCleanup = notificationsDao.findAll()
    assertEquals(
        setOf(1L, 2L),
        beforeCleanup.map { it.id?.value }.toSet(),
        "Expected notification IDs 1 and 2")

    notificationsCleanupTask.cleanup(DailyTaskTimeArrivedEvent())

    val actual = notificationsDao.findAll()
    assertEquals(expected, actual, "Expected one notification to have been deleted.")
  }
}
