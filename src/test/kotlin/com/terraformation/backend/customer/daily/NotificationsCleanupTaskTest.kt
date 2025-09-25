package com.terraformation.backend.customer.daily

import com.terraformation.backend.TestClock
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.UserId
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class NotificationsCleanupTaskTest : DatabaseTest() {
  private val config: TerrawareServerConfig = mockk()
  private val now = Instant.parse("2021-01-01T00:00:00Z")
  private val clock = TestClock(now)

  private lateinit var notificationsCleanupTask: NotificationsCleanupTask

  @BeforeEach
  fun setUp() {
    every { config.notifications.retentionDays } returns 1

    notificationsCleanupTask = NotificationsCleanupTask(clock, config, dslContext)
  }

  @Test
  fun `does not delete notifications if there are none expired`() {
    every { config.notifications.retentionDays } returns 5

    val notificationId = insertNotification(UserId(1), createdTime = now)

    val expected = notificationsDao.findAll()
    assertTrue(expected.any { it.id == notificationId }, "Expected to find notification with id 1.")

    notificationsCleanupTask.cleanup(DailyTaskTimeArrivedEvent())

    val actual = notificationsDao.findAll()
    assertEquals(expected, actual, "Notifications were deleted but shouldn't have.")
  }

  @Test
  fun `deletes expired notifications`() {
    every { config.notifications.retentionDays } returns 5

    val notificationId1 = insertNotification(UserId(1), createdTime = now)
    val expected = notificationsDao.findAll()

    val notificationId2 = insertNotification(UserId(1), createdTime = now.minus(Duration.ofDays(6)))

    val beforeCleanup = notificationsDao.findAll()
    assertSetEquals(
        setOf(notificationId1, notificationId2),
        beforeCleanup.map { it.id }.toSet(),
        "Expected notification IDs 1 and 2",
    )

    notificationsCleanupTask.cleanup(DailyTaskTimeArrivedEvent())

    val actual = notificationsDao.findAll()
    assertEquals(expected, actual, "Expected one notification to have been deleted.")
  }
}
