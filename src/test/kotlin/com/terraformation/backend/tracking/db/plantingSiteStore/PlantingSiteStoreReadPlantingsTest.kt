package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PlantingSiteStoreReadPlantingsTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class FetchSubzoneIdsWithPastPlantings {
    @Test
    fun `returns subzones with nursery deliveries`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      val plantingSiteId = insertPlantingSite()

      insertPlantingZone()
      val plantingSubzoneId11 = insertPlantingSubzone()
      val plantingSubzoneId12 = insertPlantingSubzone()

      insertPlantingZone()
      val plantingSubzoneId21 = insertPlantingSubzone()

      // Original delivery to subzone 12, then reassignment to 11. Both 11 and 12 should be counted
      // as having had past plantings.
      insertWithdrawal(purpose = WithdrawalPurpose.OutPlant)
      insertDelivery()
      insertPlanting(numPlants = 1, plantingSubzoneId = plantingSubzoneId12)
      insertPlanting(
          numPlants = -1,
          plantingTypeId = PlantingType.ReassignmentFrom,
          plantingSubzoneId = plantingSubzoneId12)
      insertPlanting(
          numPlants = 1,
          plantingTypeId = PlantingType.ReassignmentTo,
          plantingSubzoneId = plantingSubzoneId11)
      insertSpecies()
      insertPlanting(numPlants = 2, plantingSubzoneId = plantingSubzoneId21)

      insertWithdrawal(purpose = WithdrawalPurpose.OutPlant)
      insertDelivery()
      insertPlanting(numPlants = 4, plantingSubzoneId = plantingSubzoneId21)

      // Additional planting subzone with no plantings.
      insertPlantingSubzone()

      assertEquals(
          setOf(plantingSubzoneId11, plantingSubzoneId12, plantingSubzoneId21),
          store.fetchSubzoneIdsWithPastPlantings(plantingSiteId))
    }
  }

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
      insertWithdrawal()
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

      assertThrows<PlantingSiteNotFoundException> { store.hasSubzonePlantings(plantingSiteId) }
    }

    @Test
    fun `returns false when there are no plantings in subzones for a site without subzones`() {
      val plantingSiteId = insertPlantingSite()

      assertFalse(store.hasSubzonePlantings(plantingSiteId))
    }

    @Test
    fun `returns false when there are no plantings in subzones for a site with subzones`() {
      val plantingSiteId = insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()

      assertFalse(store.hasSubzonePlantings(plantingSiteId))
    }

    @Test
    fun `returns true when there are plantings in subzones`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      val plantingSiteId = insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()
      insertWithdrawal()
      insertDelivery()
      insertPlanting()

      assertTrue(store.hasSubzonePlantings(plantingSiteId))
    }
  }
}
