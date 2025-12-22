package com.terraformation.backend.tracking.db.plantingSiteStore

import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreUpdateSubstratumCompletedTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class UpdateSubstratumCompleted {
    @Test
    fun `sets completed time to current time if not set previously`() {
      insertPlantingSite()
      insertStratum()
      val substratumId = insertSubstratum()

      val initial = substrataDao.fetchOneById(substratumId)!!

      val now = Instant.ofEpochSecond(5000)
      clock.instant = now

      store.updateSubstratumCompleted(substratumId, true)

      val expected = initial.copy(plantingCompletedTime = now, modifiedTime = now)
      val actual = substrataDao.fetchOneById(substratumId)!!

      assertEquals(expected, actual)
    }

    @Test
    fun `retains existing non-null completed time`() {
      val initialPlantingCompletedTime = Instant.ofEpochSecond(5)

      insertPlantingSite()
      insertStratum()
      val substratumId = insertSubstratum(plantingCompletedTime = initialPlantingCompletedTime)

      val initial = substrataDao.fetchOneById(substratumId)!!

      val now = Instant.ofEpochSecond(5000)
      clock.instant = now

      store.updateSubstratumCompleted(substratumId, true)

      val actual = substrataDao.fetchOneById(substratumId)!!

      assertEquals(initial, actual)
    }

    @Test
    fun `clears completed time`() {
      insertPlantingSite()
      insertStratum()
      val substratumId = insertSubstratum(plantingCompletedTime = Instant.ofEpochSecond(5))

      val initial = substrataDao.fetchOneById(substratumId)!!

      val now = Instant.ofEpochSecond(5000)
      clock.instant = now

      store.updateSubstratumCompleted(substratumId, false)

      val expected = initial.copy(plantingCompletedTime = null, modifiedTime = now)
      val actual = substrataDao.fetchOneById(substratumId)!!

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission`() {
      insertPlantingSite()
      insertStratum()
      val substratumId = insertSubstratum()

      every { user.canUpdateSubstratumCompleted(any()) } returns false

      assertThrows<AccessDeniedException> { store.updateSubstratumCompleted(substratumId, true) }
    }
  }
}
