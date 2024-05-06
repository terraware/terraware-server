package com.terraformation.backend.tracking.db.plantingSiteStore

import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreUpdateSubzoneTest : PlantingSiteStoreTest() {
  @Nested
  inner class UpdatePlantingSubzone {
    @Test
    fun `sets completed time to current time if not set previously`() {
      insertPlantingSite()
      insertPlantingZone()
      val plantingSubzoneId = insertPlantingSubzone()

      val initial = plantingSubzonesDao.fetchOneById(plantingSubzoneId)!!

      val now = Instant.ofEpochSecond(5000)
      clock.instant = now

      store.updatePlantingSubzone(plantingSubzoneId) { row ->
        row.copy(plantingCompletedTime = Instant.EPOCH)
      }

      val expected = initial.copy(plantingCompletedTime = now, modifiedTime = now)
      val actual = plantingSubzonesDao.fetchOneById(plantingSubzoneId)!!

      assertEquals(expected, actual)
    }

    @Test
    fun `retains existing non-null completed time`() {
      val initialPlantingCompletedTime = Instant.ofEpochSecond(5)

      insertPlantingSite()
      insertPlantingZone()
      val plantingSubzoneId =
          insertPlantingSubzone(plantingCompletedTime = initialPlantingCompletedTime)

      val initial = plantingSubzonesDao.fetchOneById(plantingSubzoneId)!!

      val now = Instant.ofEpochSecond(5000)
      clock.instant = now

      store.updatePlantingSubzone(plantingSubzoneId) { row ->
        row.copy(plantingCompletedTime = now)
      }

      val expected =
          initial.copy(plantingCompletedTime = initialPlantingCompletedTime, modifiedTime = now)
      val actual = plantingSubzonesDao.fetchOneById(plantingSubzoneId)!!

      assertEquals(expected, actual)
    }

    @Test
    fun `clears completed time`() {
      insertPlantingSite()
      insertPlantingZone()
      val plantingSubzoneId =
          insertPlantingSubzone(plantingCompletedTime = Instant.ofEpochSecond(5))

      val initial = plantingSubzonesDao.fetchOneById(plantingSubzoneId)!!

      val now = Instant.ofEpochSecond(5000)
      clock.instant = now

      store.updatePlantingSubzone(plantingSubzoneId) { row ->
        row.copy(plantingCompletedTime = null)
      }

      val expected = initial.copy(plantingCompletedTime = null, modifiedTime = now)
      val actual = plantingSubzonesDao.fetchOneById(plantingSubzoneId)!!

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission`() {
      insertPlantingSite()
      insertPlantingZone()
      val plantingSubzoneId = insertPlantingSubzone()

      every { user.canUpdatePlantingSubzone(any()) } returns false

      assertThrows<AccessDeniedException> { store.updatePlantingSubzone(plantingSubzoneId) { it } }
    }
  }
}
