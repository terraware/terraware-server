package com.terraformation.backend.tracking.db.observationStore

import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStoreMarkUpcomingNotificationCompleteTest : BaseObservationStoreTest() {
  @Test
  fun `updates notification sent timestamp`() {
    val observationId = insertObservation()

    clock.instant = Instant.ofEpochSecond(1234)

    store.markUpcomingNotificationComplete(observationId)

    assertEquals(
        clock.instant,
        observationsDao.fetchOneById(observationId)?.upcomingNotificationSentTime,
    )
  }

  @Test
  fun `throws exception if no permission to manage observation`() {
    val observationId = insertObservation()

    every { user.canManageObservation(observationId) } returns false

    assertThrows<AccessDeniedException> { store.markUpcomingNotificationComplete(observationId) }
  }
}
