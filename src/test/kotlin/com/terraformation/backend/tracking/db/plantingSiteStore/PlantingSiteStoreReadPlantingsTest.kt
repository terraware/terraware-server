package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PlantingSiteStoreReadPlantingsTest : BasePlantingSiteStoreTest() {

  @Nested
  inner class HasPlantings {
    @BeforeEach
    fun setUp() {
      every { user.canReadPlantingSite(any()) } returns true
    }

    @Test
    fun `throws exception when no permission to read the planting site`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> { store.hasPlantings(plantingSiteId) }
    }

    @Test
    fun `returns false when there are no plantings in the site`() {
      val plantingSiteId = insertPlantingSite()

      assertFalse(store.hasPlantings(plantingSiteId))
    }

    @Test
    fun `returns true when there are plantings in the site`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      val plantingSiteId = insertPlantingSite()
      insertNurseryWithdrawal()
      insertDelivery()
      insertPlanting()

      assertTrue(store.hasPlantings(plantingSiteId))
    }
  }

  @Nested
  inner class HasSubzonePlantings {
    @BeforeEach
    fun setUp() {
      every { user.canReadPlantingSite(any()) } returns true
    }

    @Test
    fun `throws exception when no permission to read the planting site`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> { store.hasSubstratumPlantings(plantingSiteId) }
    }

    @Test
    fun `returns false when there are no plantings in subzones for a site without subzones`() {
      val plantingSiteId = insertPlantingSite()

      assertFalse(store.hasSubstratumPlantings(plantingSiteId))
    }

    @Test
    fun `returns false when there are no plantings in subzones for a site with subzones`() {
      val plantingSiteId = insertPlantingSite()
      insertStratum()
      insertSubstratum()

      assertFalse(store.hasSubstratumPlantings(plantingSiteId))
    }

    @Test
    fun `returns true when there are plantings in subzones`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      val plantingSiteId = insertPlantingSite()
      insertStratum()
      insertSubstratum()
      insertNurseryWithdrawal()
      insertDelivery()
      insertPlanting()

      assertTrue(store.hasSubstratumPlantings(plantingSiteId))
    }
  }
}
