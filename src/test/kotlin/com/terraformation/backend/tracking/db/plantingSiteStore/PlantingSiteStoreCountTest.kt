package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.model.PlantingSiteReportedPlantTotals
import io.mockk.every
import java.math.BigDecimal
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PlantingSiteStoreCountTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class CountReportedPlants {
    @Test
    fun `returns zero total for sites without plants`() {
      val plantingSiteId = insertPlantingSite()

      val expected =
          PlantingSiteReportedPlantTotals(
              id = plantingSiteId,
              plantingZones = emptyList(),
              plantsSinceLastObservation = 0,
              totalPlants = 0,
              totalSpecies = 0,
          )

      val actual = store.countReportedPlants(plantingSiteId)

      assertEquals(expected, actual)
    }

    @Test
    fun `returns site-level totals for sites without zones`() {
      val plantingSiteId = insertPlantingSite()
      insertSpecies()
      insertPlantingSitePopulation(plantsSinceLastObservation = 1, totalPlants = 10)
      insertSpecies()
      insertPlantingSitePopulation(plantsSinceLastObservation = 2, totalPlants = 20)

      val expected =
          PlantingSiteReportedPlantTotals(
              id = plantingSiteId,
              plantingZones = emptyList(),
              plantsSinceLastObservation = 3,
              totalPlants = 30,
              totalSpecies = 2,
          )

      val actual = store.countReportedPlants(plantingSiteId)

      assertEquals(expected, actual)
      assertNull(actual.progressPercent, "Progress%")
    }

    @Test
    fun `returns correct zone-level and subzone-level totals`() {
      val plantingSiteId = insertPlantingSite()

      val plantingZoneId1 =
          insertPlantingZone(areaHa = BigDecimal(10), targetPlantingDensity = BigDecimal(2))
      val plantingSubzoneId1a = insertPlantingSubzone()
      insertSpecies()
      insertPlantingSubzonePopulation(totalPlants = 5)
      insertPlantingZonePopulation(plantsSinceLastObservation = 1, totalPlants = 10)
      insertPlantingSitePopulation(plantsSinceLastObservation = 1, totalPlants = 10)
      val plantingSubzoneId1b = insertPlantingSubzone()
      insertPlantingSubzonePopulation(totalPlants = 5)
      insertSpecies()
      insertPlantingSubzonePopulation(totalPlants = 20)
      insertPlantingZonePopulation(plantsSinceLastObservation = 2, totalPlants = 20)

      val plantingZoneId2 =
          insertPlantingZone(areaHa = BigDecimal(101), targetPlantingDensity = BigDecimal(4))
      val plantingSubzoneId2 = insertPlantingSubzone()
      insertPlantingSubzonePopulation(totalPlants = 50)
      insertPlantingZonePopulation(plantsSinceLastObservation = 4, totalPlants = 40)
      insertPlantingSitePopulation(plantsSinceLastObservation = 6, totalPlants = 60)
      insertSpecies()
      insertPlantingSubzonePopulation(totalPlants = 55)
      insertPlantingZonePopulation(plantsSinceLastObservation = 8, totalPlants = 80)
      insertPlantingSitePopulation(plantsSinceLastObservation = 8, totalPlants = 80)

      val emptyPlantingZoneId =
          insertPlantingZone(areaHa = BigDecimal(50), targetPlantingDensity = BigDecimal(5))
      insertPlantingSubzone()

      // Note that the zone/subzone/site totals don't all add up correctly here; that's intentional
      // to make sure the values are coming from the right places.

      val expected =
          PlantingSiteReportedPlantTotals(
              id = plantingSiteId,
              plantingZones =
                  listOf(
                      PlantingSiteReportedPlantTotals.PlantingZone(
                          id = plantingZoneId1,
                          plantsSinceLastObservation = 3,
                          targetPlants = 20,
                          totalPlants = 30,
                          plantingSubzones =
                              listOf(
                                  PlantingSiteReportedPlantTotals.PlantingSubzone(
                                      id = plantingSubzoneId1a,
                                      totalPlants = 5,
                                  ),
                                  PlantingSiteReportedPlantTotals.PlantingSubzone(
                                      id = plantingSubzoneId1b,
                                      totalPlants = 25,
                                  )),
                      ),
                      PlantingSiteReportedPlantTotals.PlantingZone(
                          id = plantingZoneId2,
                          plantsSinceLastObservation = 12,
                          targetPlants = 404,
                          totalPlants = 120,
                          plantingSubzones =
                              listOf(
                                  PlantingSiteReportedPlantTotals.PlantingSubzone(
                                      id = plantingSubzoneId2,
                                      totalPlants = 105,
                                  ))),
                      PlantingSiteReportedPlantTotals.PlantingZone(
                          id = emptyPlantingZoneId,
                          plantsSinceLastObservation = 0,
                          plantingSubzones = emptyList(),
                          targetPlants = 250,
                          totalPlants = 0,
                      ),
                  ),
              plantsSinceLastObservation = 15,
              totalPlants = 150,
              totalSpecies = 3,
          )

      val actual = store.countReportedPlants(plantingSiteId)

      assertEquals(expected, actual)
      assertEquals(150, actual.plantingZones[0].progressPercent, "Progress% for zone 1")
      assertEquals(
          (29.7).roundToInt(),
          actual.plantingZones[1].progressPercent,
          "Progress% for zone 2 should be rounded up")
      assertEquals(
          (150.0 / (20.0 + 404.0 + 250.0) * 100.0).roundToInt(),
          actual.progressPercent,
          "Progress% for site")
    }

    @Test
    fun `throws exception if no permission to read planting site`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> { store.countReportedPlants(plantingSiteId) }
    }
  }

  @Nested
  inner class CountReportedPlantsInSubzones {
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

      // Original delivery to subzone 12, then reassignment to 11, so 12 shouldn't be counted as
      // planted any more.
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
          mapOf(plantingSubzoneId11 to 1L, plantingSubzoneId21 to 6L),
          store.countReportedPlantsInSubzones(plantingSiteId))
    }
  }
}
