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
              species = emptyList(),
              totalPlants = 0,
              totalSpecies = 0,
          )

      val actual = store.countReportedPlants(plantingSiteId)

      assertEquals(expected, actual)
    }

    @Test
    fun `returns site-level totals for sites without zones`() {
      val plantingSiteId = insertPlantingSite()
      val speciesId1 = insertSpecies()
      insertPlantingSitePopulation(plantsSinceLastObservation = 1, totalPlants = 10)
      val speciesId2 = insertSpecies()
      insertPlantingSitePopulation(plantsSinceLastObservation = 2, totalPlants = 20)

      val expected =
          PlantingSiteReportedPlantTotals(
              id = plantingSiteId,
              plantingZones = emptyList(),
              plantsSinceLastObservation = 3,
              species =
                  listOf(
                      PlantingSiteReportedPlantTotals.Species(speciesId1, 1, 10),
                      PlantingSiteReportedPlantTotals.Species(speciesId2, 2, 20),
                  ),
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
          insertStratum(areaHa = BigDecimal(10), targetPlantingDensity = BigDecimal(2))
      val plantingSubzoneId1a = insertSubstratum()
      val speciesId1 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 2, totalPlants = 5)
      insertStratumPopulation(plantsSinceLastObservation = 3, totalPlants = 10)
      insertPlantingSitePopulation(plantsSinceLastObservation = 3, totalPlants = 10)
      val plantingSubzoneId1b = insertSubstratum()
      insertSubstratumPopulation(plantsSinceLastObservation = 1, totalPlants = 5)
      val speciesId2 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 2, totalPlants = 20)
      insertStratumPopulation(plantsSinceLastObservation = 2, totalPlants = 20)

      val plantingZoneId2 =
          insertStratum(areaHa = BigDecimal(101), targetPlantingDensity = BigDecimal(4))
      val plantingSubzoneId2 = insertSubstratum()
      insertSubstratumPopulation(plantsSinceLastObservation = 4, totalPlants = 50)
      insertStratumPopulation(plantsSinceLastObservation = 4, totalPlants = 40)
      insertPlantingSitePopulation(plantsSinceLastObservation = 6, totalPlants = 60)
      val speciesId3 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 8, totalPlants = 55)
      insertStratumPopulation(plantsSinceLastObservation = 7, totalPlants = 70)
      insertPlantingSitePopulation(plantsSinceLastObservation = 8, totalPlants = 80)

      val emptyPlantingZoneId =
          insertStratum(areaHa = BigDecimal(50), targetPlantingDensity = BigDecimal(5))
      val emptyPlantingSubzoneId = insertSubstratum()

      // Note that the zone/subzone/site totals don't all add up correctly here; that's intentional
      // to make sure the values are coming from the right places.

      val expected =
          PlantingSiteReportedPlantTotals(
              id = plantingSiteId,
              plantingZones =
                  listOf(
                      PlantingSiteReportedPlantTotals.PlantingZone(
                          id = plantingZoneId1,
                          plantsSinceLastObservation = 5,
                          species =
                              listOf(
                                  PlantingSiteReportedPlantTotals.Species(
                                      id = speciesId1,
                                      plantsSinceLastObservation = 3,
                                      totalPlants = 10,
                                  ),
                                  PlantingSiteReportedPlantTotals.Species(
                                      id = speciesId2,
                                      plantsSinceLastObservation = 2,
                                      totalPlants = 20,
                                  ),
                              ),
                          targetPlants = 20,
                          totalPlants = 30,
                          totalSpecies = 2,
                          plantingSubzones =
                              listOf(
                                  PlantingSiteReportedPlantTotals.PlantingSubzone(
                                      id = plantingSubzoneId1a,
                                      plantsSinceLastObservation = 2,
                                      species =
                                          listOf(
                                              PlantingSiteReportedPlantTotals.Species(
                                                  id = speciesId1,
                                                  plantsSinceLastObservation = 2,
                                                  totalPlants = 5,
                                              )
                                          ),
                                      totalPlants = 5,
                                      totalSpecies = 1,
                                  ),
                                  PlantingSiteReportedPlantTotals.PlantingSubzone(
                                      id = plantingSubzoneId1b,
                                      plantsSinceLastObservation = 3,
                                      species =
                                          listOf(
                                              PlantingSiteReportedPlantTotals.Species(
                                                  id = speciesId1,
                                                  plantsSinceLastObservation = 1,
                                                  totalPlants = 5,
                                              ),
                                              PlantingSiteReportedPlantTotals.Species(
                                                  id = speciesId2,
                                                  plantsSinceLastObservation = 2,
                                                  totalPlants = 20,
                                              ),
                                          ),
                                      totalPlants = 25,
                                      totalSpecies = 2,
                                  ),
                              ),
                      ),
                      PlantingSiteReportedPlantTotals.PlantingZone(
                          id = plantingZoneId2,
                          plantsSinceLastObservation = 11,
                          species =
                              listOf(
                                  PlantingSiteReportedPlantTotals.Species(
                                      id = speciesId2,
                                      plantsSinceLastObservation = 4,
                                      totalPlants = 40,
                                  ),
                                  PlantingSiteReportedPlantTotals.Species(
                                      id = speciesId3,
                                      plantsSinceLastObservation = 7,
                                      totalPlants = 70,
                                  ),
                              ),
                          targetPlants = 404,
                          totalPlants = 110,
                          totalSpecies = 2,
                          plantingSubzones =
                              listOf(
                                  PlantingSiteReportedPlantTotals.PlantingSubzone(
                                      id = plantingSubzoneId2,
                                      plantsSinceLastObservation = 12,
                                      species =
                                          listOf(
                                              PlantingSiteReportedPlantTotals.Species(
                                                  id = speciesId2,
                                                  plantsSinceLastObservation = 4,
                                                  totalPlants = 50,
                                              ),
                                              PlantingSiteReportedPlantTotals.Species(
                                                  id = speciesId3,
                                                  plantsSinceLastObservation = 8,
                                                  totalPlants = 55,
                                              ),
                                          ),
                                      totalPlants = 105,
                                      totalSpecies = 2,
                                  )
                              ),
                      ),
                      PlantingSiteReportedPlantTotals.PlantingZone(
                          id = emptyPlantingZoneId,
                          plantsSinceLastObservation = 0,
                          plantingSubzones =
                              listOf(
                                  PlantingSiteReportedPlantTotals.PlantingSubzone(
                                      id = emptyPlantingSubzoneId,
                                      plantsSinceLastObservation = 0,
                                      species = emptyList(),
                                      totalSpecies = 0,
                                      totalPlants = 0,
                                  )
                              ),
                          species = emptyList(),
                          targetPlants = 250,
                          totalSpecies = 0,
                          totalPlants = 0,
                      ),
                  ),
              plantsSinceLastObservation = 17,
              species =
                  listOf(
                      PlantingSiteReportedPlantTotals.Species(
                          id = speciesId1,
                          plantsSinceLastObservation = 3,
                          totalPlants = 10,
                      ),
                      PlantingSiteReportedPlantTotals.Species(
                          id = speciesId2,
                          plantsSinceLastObservation = 6,
                          totalPlants = 60,
                      ),
                      PlantingSiteReportedPlantTotals.Species(
                          id = speciesId3,
                          plantsSinceLastObservation = 8,
                          totalPlants = 80,
                      ),
                  ),
              totalPlants = 150,
              totalSpecies = 3,
          )

      val actual = store.countReportedPlants(plantingSiteId)

      assertEquals(expected, actual)
      assertEquals(150, actual.plantingZones[0].progressPercent, "Progress% for zone 1")
      assertEquals(
          (110.0 / 404.0 * 100.0).roundToInt(),
          actual.plantingZones[1].progressPercent,
          "Progress% for zone 2 should be rounded up",
      )
      assertEquals(
          (150.0 / (20.0 + 404.0 + 250.0) * 100.0).roundToInt(),
          actual.progressPercent,
          "Progress% for site",
      )
    }

    @Test
    fun `throws exception if no permission to read planting site`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> { store.countReportedPlants(plantingSiteId) }
    }
  }

  @Nested
  inner class CountReportedPlantsForOrganization {
    @Test
    fun `returns correct zone-level and subzone-level totals`() {
      val plantingSiteId = insertPlantingSite()

      val plantingZoneId1 =
          insertStratum(areaHa = BigDecimal(10), targetPlantingDensity = BigDecimal(2))
      val plantingSubzoneId1a = insertSubstratum()
      val speciesId1 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 2, totalPlants = 5)
      insertStratumPopulation(plantsSinceLastObservation = 3, totalPlants = 10)
      insertPlantingSitePopulation(plantsSinceLastObservation = 3, totalPlants = 10)
      val plantingSubzoneId1b = insertSubstratum()
      insertSubstratumPopulation(plantsSinceLastObservation = 1, totalPlants = 5)
      val speciesId2 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 2, totalPlants = 20)
      insertStratumPopulation(plantsSinceLastObservation = 2, totalPlants = 20)

      val plantingZoneId2 =
          insertStratum(areaHa = BigDecimal(101), targetPlantingDensity = BigDecimal(4))
      val plantingSubzoneId2 = insertSubstratum()
      insertSubstratumPopulation(plantsSinceLastObservation = 4, totalPlants = 50)
      insertStratumPopulation(plantsSinceLastObservation = 4, totalPlants = 40)
      insertPlantingSitePopulation(plantsSinceLastObservation = 6, totalPlants = 60)
      val speciesId3 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 8, totalPlants = 55)
      insertStratumPopulation(plantsSinceLastObservation = 7, totalPlants = 70)
      insertPlantingSitePopulation(plantsSinceLastObservation = 8, totalPlants = 80)

      val emptyPlantingZoneId =
          insertStratum(areaHa = BigDecimal(50), targetPlantingDensity = BigDecimal(5))
      val emptyPlantingSubzoneId = insertSubstratum()

      // planting site in the same organization with no plantings
      val otherPlantingSiteId = insertPlantingSite()

      // planting site in another organization
      insertOrganization()
      insertPlantingSite()

      val expected =
          listOf(
              PlantingSiteReportedPlantTotals(
                  id = plantingSiteId,
                  plantingZones =
                      listOf(
                          PlantingSiteReportedPlantTotals.PlantingZone(
                              id = plantingZoneId1,
                              plantsSinceLastObservation = 5,
                              species =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.Species(
                                          id = speciesId1,
                                          plantsSinceLastObservation = 3,
                                          totalPlants = 10,
                                      ),
                                      PlantingSiteReportedPlantTotals.Species(
                                          id = speciesId2,
                                          plantsSinceLastObservation = 2,
                                          totalPlants = 20,
                                      ),
                                  ),
                              targetPlants = 20,
                              totalPlants = 30,
                              totalSpecies = 2,
                              plantingSubzones =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.PlantingSubzone(
                                          id = plantingSubzoneId1a,
                                          plantsSinceLastObservation = 2,
                                          species =
                                              listOf(
                                                  PlantingSiteReportedPlantTotals.Species(
                                                      id = speciesId1,
                                                      plantsSinceLastObservation = 2,
                                                      totalPlants = 5,
                                                  )
                                              ),
                                          totalPlants = 5,
                                          totalSpecies = 1,
                                      ),
                                      PlantingSiteReportedPlantTotals.PlantingSubzone(
                                          id = plantingSubzoneId1b,
                                          plantsSinceLastObservation = 3,
                                          species =
                                              listOf(
                                                  PlantingSiteReportedPlantTotals.Species(
                                                      id = speciesId1,
                                                      plantsSinceLastObservation = 1,
                                                      totalPlants = 5,
                                                  ),
                                                  PlantingSiteReportedPlantTotals.Species(
                                                      id = speciesId2,
                                                      plantsSinceLastObservation = 2,
                                                      totalPlants = 20,
                                                  ),
                                              ),
                                          totalPlants = 25,
                                          totalSpecies = 2,
                                      ),
                                  ),
                          ),
                          PlantingSiteReportedPlantTotals.PlantingZone(
                              id = plantingZoneId2,
                              plantsSinceLastObservation = 11,
                              species =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.Species(
                                          id = speciesId2,
                                          plantsSinceLastObservation = 4,
                                          totalPlants = 40,
                                      ),
                                      PlantingSiteReportedPlantTotals.Species(
                                          id = speciesId3,
                                          plantsSinceLastObservation = 7,
                                          totalPlants = 70,
                                      ),
                                  ),
                              targetPlants = 404,
                              totalPlants = 110,
                              totalSpecies = 2,
                              plantingSubzones =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.PlantingSubzone(
                                          id = plantingSubzoneId2,
                                          plantsSinceLastObservation = 12,
                                          species =
                                              listOf(
                                                  PlantingSiteReportedPlantTotals.Species(
                                                      id = speciesId2,
                                                      plantsSinceLastObservation = 4,
                                                      totalPlants = 50,
                                                  ),
                                                  PlantingSiteReportedPlantTotals.Species(
                                                      id = speciesId3,
                                                      plantsSinceLastObservation = 8,
                                                      totalPlants = 55,
                                                  ),
                                              ),
                                          totalPlants = 105,
                                          totalSpecies = 2,
                                      )
                                  ),
                          ),
                          PlantingSiteReportedPlantTotals.PlantingZone(
                              id = emptyPlantingZoneId,
                              plantsSinceLastObservation = 0,
                              plantingSubzones =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.PlantingSubzone(
                                          id = emptyPlantingSubzoneId,
                                          plantsSinceLastObservation = 0,
                                          species = emptyList(),
                                          totalSpecies = 0,
                                          totalPlants = 0,
                                      )
                                  ),
                              species = emptyList(),
                              targetPlants = 250,
                              totalSpecies = 0,
                              totalPlants = 0,
                          ),
                      ),
                  plantsSinceLastObservation = 17,
                  species =
                      listOf(
                          PlantingSiteReportedPlantTotals.Species(
                              id = speciesId1,
                              plantsSinceLastObservation = 3,
                              totalPlants = 10,
                          ),
                          PlantingSiteReportedPlantTotals.Species(
                              id = speciesId2,
                              plantsSinceLastObservation = 6,
                              totalPlants = 60,
                          ),
                          PlantingSiteReportedPlantTotals.Species(
                              id = speciesId3,
                              plantsSinceLastObservation = 8,
                              totalPlants = 80,
                          ),
                      ),
                  totalPlants = 150,
                  totalSpecies = 3,
              ),
              PlantingSiteReportedPlantTotals(
                  id = otherPlantingSiteId,
                  plantingZones = emptyList(),
                  plantsSinceLastObservation = 0,
                  species = emptyList(),
                  totalPlants = 0,
                  totalSpecies = 0,
              ),
          )

      val actual = store.countReportedPlantsForOrganization(organizationId)

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class CountReportedPlantsForProject {
    @Test
    fun `returns correct zone-level and subzone-level totals`() {
      val projectId = insertProject()
      val plantingSiteId = insertPlantingSite(projectId = projectId)

      val plantingZoneId1 =
          insertStratum(areaHa = BigDecimal(10), targetPlantingDensity = BigDecimal(2))
      val plantingSubzoneId1a = insertSubstratum()
      val speciesId1 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 2, totalPlants = 5)
      insertStratumPopulation(plantsSinceLastObservation = 3, totalPlants = 10)
      insertPlantingSitePopulation(plantsSinceLastObservation = 3, totalPlants = 10)
      val plantingSubzoneId1b = insertSubstratum()
      insertSubstratumPopulation(plantsSinceLastObservation = 1, totalPlants = 5)
      val speciesId2 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 2, totalPlants = 20)
      insertStratumPopulation(plantsSinceLastObservation = 2, totalPlants = 20)

      val plantingZoneId2 =
          insertStratum(areaHa = BigDecimal(101), targetPlantingDensity = BigDecimal(4))
      val plantingSubzoneId2 = insertSubstratum()
      insertSubstratumPopulation(plantsSinceLastObservation = 4, totalPlants = 50)
      insertStratumPopulation(plantsSinceLastObservation = 4, totalPlants = 40)
      insertPlantingSitePopulation(plantsSinceLastObservation = 6, totalPlants = 60)
      val speciesId3 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 8, totalPlants = 55)
      insertStratumPopulation(plantsSinceLastObservation = 7, totalPlants = 70)
      insertPlantingSitePopulation(plantsSinceLastObservation = 8, totalPlants = 80)

      val emptyPlantingZoneId =
          insertStratum(areaHa = BigDecimal(50), targetPlantingDensity = BigDecimal(5))
      val emptyPlantingSubzoneId = insertSubstratum()

      // planting site in the same project with no plantings
      val otherPlantingSiteId = insertPlantingSite(projectId = projectId)

      // planting site in another project
      val otherProjectId = insertProject()
      insertPlantingSite(projectId = otherProjectId)

      val expected =
          listOf(
              PlantingSiteReportedPlantTotals(
                  id = plantingSiteId,
                  plantingZones =
                      listOf(
                          PlantingSiteReportedPlantTotals.PlantingZone(
                              id = plantingZoneId1,
                              plantsSinceLastObservation = 5,
                              species =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.Species(
                                          id = speciesId1,
                                          plantsSinceLastObservation = 3,
                                          totalPlants = 10,
                                      ),
                                      PlantingSiteReportedPlantTotals.Species(
                                          id = speciesId2,
                                          plantsSinceLastObservation = 2,
                                          totalPlants = 20,
                                      ),
                                  ),
                              targetPlants = 20,
                              totalPlants = 30,
                              totalSpecies = 2,
                              plantingSubzones =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.PlantingSubzone(
                                          id = plantingSubzoneId1a,
                                          plantsSinceLastObservation = 2,
                                          species =
                                              listOf(
                                                  PlantingSiteReportedPlantTotals.Species(
                                                      id = speciesId1,
                                                      plantsSinceLastObservation = 2,
                                                      totalPlants = 5,
                                                  )
                                              ),
                                          totalPlants = 5,
                                          totalSpecies = 1,
                                      ),
                                      PlantingSiteReportedPlantTotals.PlantingSubzone(
                                          id = plantingSubzoneId1b,
                                          plantsSinceLastObservation = 3,
                                          species =
                                              listOf(
                                                  PlantingSiteReportedPlantTotals.Species(
                                                      id = speciesId1,
                                                      plantsSinceLastObservation = 1,
                                                      totalPlants = 5,
                                                  ),
                                                  PlantingSiteReportedPlantTotals.Species(
                                                      id = speciesId2,
                                                      plantsSinceLastObservation = 2,
                                                      totalPlants = 20,
                                                  ),
                                              ),
                                          totalPlants = 25,
                                          totalSpecies = 2,
                                      ),
                                  ),
                          ),
                          PlantingSiteReportedPlantTotals.PlantingZone(
                              id = plantingZoneId2,
                              plantsSinceLastObservation = 11,
                              species =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.Species(
                                          id = speciesId2,
                                          plantsSinceLastObservation = 4,
                                          totalPlants = 40,
                                      ),
                                      PlantingSiteReportedPlantTotals.Species(
                                          id = speciesId3,
                                          plantsSinceLastObservation = 7,
                                          totalPlants = 70,
                                      ),
                                  ),
                              targetPlants = 404,
                              totalPlants = 110,
                              totalSpecies = 2,
                              plantingSubzones =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.PlantingSubzone(
                                          id = plantingSubzoneId2,
                                          plantsSinceLastObservation = 12,
                                          species =
                                              listOf(
                                                  PlantingSiteReportedPlantTotals.Species(
                                                      id = speciesId2,
                                                      plantsSinceLastObservation = 4,
                                                      totalPlants = 50,
                                                  ),
                                                  PlantingSiteReportedPlantTotals.Species(
                                                      id = speciesId3,
                                                      plantsSinceLastObservation = 8,
                                                      totalPlants = 55,
                                                  ),
                                              ),
                                          totalPlants = 105,
                                          totalSpecies = 2,
                                      )
                                  ),
                          ),
                          PlantingSiteReportedPlantTotals.PlantingZone(
                              id = emptyPlantingZoneId,
                              plantsSinceLastObservation = 0,
                              plantingSubzones =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.PlantingSubzone(
                                          id = emptyPlantingSubzoneId,
                                          plantsSinceLastObservation = 0,
                                          species = emptyList(),
                                          totalSpecies = 0,
                                          totalPlants = 0,
                                      )
                                  ),
                              species = emptyList(),
                              targetPlants = 250,
                              totalSpecies = 0,
                              totalPlants = 0,
                          ),
                      ),
                  plantsSinceLastObservation = 17,
                  species =
                      listOf(
                          PlantingSiteReportedPlantTotals.Species(
                              id = speciesId1,
                              plantsSinceLastObservation = 3,
                              totalPlants = 10,
                          ),
                          PlantingSiteReportedPlantTotals.Species(
                              id = speciesId2,
                              plantsSinceLastObservation = 6,
                              totalPlants = 60,
                          ),
                          PlantingSiteReportedPlantTotals.Species(
                              id = speciesId3,
                              plantsSinceLastObservation = 8,
                              totalPlants = 80,
                          ),
                      ),
                  totalPlants = 150,
                  totalSpecies = 3,
              ),
              PlantingSiteReportedPlantTotals(
                  id = otherPlantingSiteId,
                  plantingZones = emptyList(),
                  plantsSinceLastObservation = 0,
                  species = emptyList(),
                  totalPlants = 0,
                  totalSpecies = 0,
              ),
          )

      val actual = store.countReportedPlantsForProject(projectId)

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class CountReportedPlantsInSubzones {
    @Test
    fun `returns subzones with nursery deliveries`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      val plantingSiteId = insertPlantingSite()

      insertStratum()
      val plantingSubzoneId11 = insertSubstratum()
      val plantingSubzoneId12 = insertSubstratum()

      insertStratum()
      val plantingSubzoneId21 = insertSubstratum()

      // Original delivery to subzone 12, then reassignment to 11, so 12 shouldn't be counted as
      // planted any more.
      insertNurseryWithdrawal(purpose = WithdrawalPurpose.OutPlant)
      insertDelivery()
      insertPlanting(numPlants = 1, substratumId = plantingSubzoneId12)
      insertPlanting(
          numPlants = -1,
          plantingTypeId = PlantingType.ReassignmentFrom,
          substratumId = plantingSubzoneId12,
      )
      insertPlanting(
          numPlants = 1,
          plantingTypeId = PlantingType.ReassignmentTo,
          substratumId = plantingSubzoneId11,
      )
      insertSpecies()
      insertPlanting(numPlants = 2, substratumId = plantingSubzoneId21)

      insertNurseryWithdrawal(purpose = WithdrawalPurpose.OutPlant)
      insertDelivery()
      insertPlanting(numPlants = 4, substratumId = plantingSubzoneId21)

      // Additional planting subzone with no plantings.
      insertSubstratum()

      assertEquals(
          mapOf(plantingSubzoneId11 to 1L, plantingSubzoneId21 to 6L),
          store.countReportedPlantsInSubzones(plantingSiteId),
      )
    }
  }
}
