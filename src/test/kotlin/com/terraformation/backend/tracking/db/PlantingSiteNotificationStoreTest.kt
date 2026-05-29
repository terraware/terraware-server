package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteNotificationStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()

  private val store: PlantingSiteNotificationStore by lazy {
    PlantingSiteNotificationStore(clock, dslContext)
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
  }

  @Test
  fun `records notification sent time`() {
    val plantingSiteId = insertPlantingSite()

    every { user.canReadPlantingSite(plantingSiteId) } returns true
    every { user.canManageNotifications() } returns true

    clock.instant = Instant.ofEpochSecond(1234)

    store.markNotificationComplete(plantingSiteId, NotificationType.ScheduleObservation, 1)

    assertEquals(clock.instant, plantingSiteNotificationsDao.findAll().single().sentTime)
  }

  @Test
  fun `throws exception if no permission`() {
    val plantingSiteId = insertPlantingSite()

    every { user.canReadPlantingSite(plantingSiteId) } returns true
    every { user.canManageNotifications() } returns false

    assertThrows<AccessDeniedException> {
      store.markNotificationComplete(plantingSiteId, NotificationType.ScheduleObservation, 1)
    }
  }
}
