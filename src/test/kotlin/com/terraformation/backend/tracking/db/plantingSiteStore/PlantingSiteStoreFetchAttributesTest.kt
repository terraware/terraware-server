package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PlantingSiteStoreFetchAttributesTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class IsDetailed {
    @BeforeEach
    fun setUp() {
      every { user.canReadPlantingSite(any()) } returns true
    }

    @Test
    fun `returns false when site has no subzones`() {
      val plantingSiteId = insertPlantingSite()

      assertFalse(store.isDetailed(plantingSiteId))
    }

    @Test
    fun `returns true when site has a subzone`() {
      val plantingSiteId = insertPlantingSite()
      insertStratum()
      insertSubstratum()

      assertTrue(store.isDetailed(plantingSiteId))
    }

    @Test
    fun `throws exception when no permission to read the planting site`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> { store.isDetailed(plantingSiteId) }
    }
  }
}
