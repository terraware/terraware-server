package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.default_schema.NotificationType
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreMarkNotificationTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class MarkSchedulingObservationsNotificationComplete {
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
}
