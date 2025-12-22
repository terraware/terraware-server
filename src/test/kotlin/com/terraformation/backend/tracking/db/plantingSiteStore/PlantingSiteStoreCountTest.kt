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
              strata = emptyList(),
              plantsSinceLastObservation = 0,
              species = emptyList(),
              totalPlants = 0,
              totalSpecies = 0,
          )

      val actual = store.countReportedPlants(plantingSiteId)

      assertEquals(expected, actual)
    }

    @Test
    fun `returns site-level totals for sites without strata`() {
      val plantingSiteId = insertPlantingSite()
      val speciesId1 = insertSpecies()
      insertPlantingSitePopulation(plantsSinceLastObservation = 1, totalPlants = 10)
      val speciesId2 = insertSpecies()
      insertPlantingSitePopulation(plantsSinceLastObservation = 2, totalPlants = 20)

      val expected =
          PlantingSiteReportedPlantTotals(
              id = plantingSiteId,
              strata = emptyList(),
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
    fun `returns correct stratum-level and substratum-level totals`() {
      val plantingSiteId = insertPlantingSite()

      val stratumId1 = insertStratum(areaHa = BigDecimal(10), targetPlantingDensity = BigDecimal(2))
      val substratumId1a = insertSubstratum()
      val speciesId1 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 2, totalPlants = 5)
      insertStratumPopulation(plantsSinceLastObservation = 3, totalPlants = 10)
      insertPlantingSitePopulation(plantsSinceLastObservation = 3, totalPlants = 10)
      val substratumId1b = insertSubstratum()
      insertSubstratumPopulation(plantsSinceLastObservation = 1, totalPlants = 5)
      val speciesId2 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 2, totalPlants = 20)
      insertStratumPopulation(plantsSinceLastObservation = 2, totalPlants = 20)

      val stratumId2 =
          insertStratum(areaHa = BigDecimal(101), targetPlantingDensity = BigDecimal(4))
      val substratumId2 = insertSubstratum()
      insertSubstratumPopulation(plantsSinceLastObservation = 4, totalPlants = 50)
      insertStratumPopulation(plantsSinceLastObservation = 4, totalPlants = 40)
      insertPlantingSitePopulation(plantsSinceLastObservation = 6, totalPlants = 60)
      val speciesId3 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 8, totalPlants = 55)
      insertStratumPopulation(plantsSinceLastObservation = 7, totalPlants = 70)
      insertPlantingSitePopulation(plantsSinceLastObservation = 8, totalPlants = 80)

      val emptyStratumId =
          insertStratum(areaHa = BigDecimal(50), targetPlantingDensity = BigDecimal(5))
      val emptySubstratumId = insertSubstratum()

      // Note that the stratum/substratum/site totals don't all add up correctly here; that's
      // intentional to make sure the values are coming from the right places.

      val expected =
          PlantingSiteReportedPlantTotals(
              id = plantingSiteId,
              strata =
                  listOf(
                      PlantingSiteReportedPlantTotals.Stratum(
                          id = stratumId1,
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
                          substrata =
                              listOf(
                                  PlantingSiteReportedPlantTotals.Substratum(
                                      id = substratumId1a,
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
                                  PlantingSiteReportedPlantTotals.Substratum(
                                      id = substratumId1b,
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
                      PlantingSiteReportedPlantTotals.Stratum(
                          id = stratumId2,
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
                          substrata =
                              listOf(
                                  PlantingSiteReportedPlantTotals.Substratum(
                                      id = substratumId2,
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
                      PlantingSiteReportedPlantTotals.Stratum(
                          id = emptyStratumId,
                          plantsSinceLastObservation = 0,
                          substrata =
                              listOf(
                                  PlantingSiteReportedPlantTotals.Substratum(
                                      id = emptySubstratumId,
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
      assertEquals(150, actual.strata[0].progressPercent, "Progress% for stratum 1")
      assertEquals(
          (110.0 / 404.0 * 100.0).roundToInt(),
          actual.strata[1].progressPercent,
          "Progress% for stratum 2 should be rounded up",
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
    fun `returns correct stratum-level and substratum-level totals`() {
      val plantingSiteId = insertPlantingSite()

      val stratumId1 = insertStratum(areaHa = BigDecimal(10), targetPlantingDensity = BigDecimal(2))
      val substratumId1a = insertSubstratum()
      val speciesId1 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 2, totalPlants = 5)
      insertStratumPopulation(plantsSinceLastObservation = 3, totalPlants = 10)
      insertPlantingSitePopulation(plantsSinceLastObservation = 3, totalPlants = 10)
      val substratumId1b = insertSubstratum()
      insertSubstratumPopulation(plantsSinceLastObservation = 1, totalPlants = 5)
      val speciesId2 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 2, totalPlants = 20)
      insertStratumPopulation(plantsSinceLastObservation = 2, totalPlants = 20)

      val stratumId2 =
          insertStratum(areaHa = BigDecimal(101), targetPlantingDensity = BigDecimal(4))
      val substratumId2 = insertSubstratum()
      insertSubstratumPopulation(plantsSinceLastObservation = 4, totalPlants = 50)
      insertStratumPopulation(plantsSinceLastObservation = 4, totalPlants = 40)
      insertPlantingSitePopulation(plantsSinceLastObservation = 6, totalPlants = 60)
      val speciesId3 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 8, totalPlants = 55)
      insertStratumPopulation(plantsSinceLastObservation = 7, totalPlants = 70)
      insertPlantingSitePopulation(plantsSinceLastObservation = 8, totalPlants = 80)

      val emptyStratumId =
          insertStratum(areaHa = BigDecimal(50), targetPlantingDensity = BigDecimal(5))
      val emptySubstratumId = insertSubstratum()

      // planting site in the same organization with no plantings
      val otherPlantingSiteId = insertPlantingSite()

      // planting site in another organization
      insertOrganization()
      insertPlantingSite()

      val expected =
          listOf(
              PlantingSiteReportedPlantTotals(
                  id = plantingSiteId,
                  strata =
                      listOf(
                          PlantingSiteReportedPlantTotals.Stratum(
                              id = stratumId1,
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
                              substrata =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.Substratum(
                                          id = substratumId1a,
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
                                      PlantingSiteReportedPlantTotals.Substratum(
                                          id = substratumId1b,
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
                          PlantingSiteReportedPlantTotals.Stratum(
                              id = stratumId2,
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
                              substrata =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.Substratum(
                                          id = substratumId2,
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
                          PlantingSiteReportedPlantTotals.Stratum(
                              id = emptyStratumId,
                              plantsSinceLastObservation = 0,
                              substrata =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.Substratum(
                                          id = emptySubstratumId,
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
                  strata = emptyList(),
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
    fun `returns correct stratum-level and substratum-level totals`() {
      val projectId = insertProject()
      val plantingSiteId = insertPlantingSite(projectId = projectId)

      val stratumId1 = insertStratum(areaHa = BigDecimal(10), targetPlantingDensity = BigDecimal(2))
      val substratumId1a = insertSubstratum()
      val speciesId1 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 2, totalPlants = 5)
      insertStratumPopulation(plantsSinceLastObservation = 3, totalPlants = 10)
      insertPlantingSitePopulation(plantsSinceLastObservation = 3, totalPlants = 10)
      val substratumId1b = insertSubstratum()
      insertSubstratumPopulation(plantsSinceLastObservation = 1, totalPlants = 5)
      val speciesId2 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 2, totalPlants = 20)
      insertStratumPopulation(plantsSinceLastObservation = 2, totalPlants = 20)

      val stratumId2 =
          insertStratum(areaHa = BigDecimal(101), targetPlantingDensity = BigDecimal(4))
      val substratumId2 = insertSubstratum()
      insertSubstratumPopulation(plantsSinceLastObservation = 4, totalPlants = 50)
      insertStratumPopulation(plantsSinceLastObservation = 4, totalPlants = 40)
      insertPlantingSitePopulation(plantsSinceLastObservation = 6, totalPlants = 60)
      val speciesId3 = insertSpecies()
      insertSubstratumPopulation(plantsSinceLastObservation = 8, totalPlants = 55)
      insertStratumPopulation(plantsSinceLastObservation = 7, totalPlants = 70)
      insertPlantingSitePopulation(plantsSinceLastObservation = 8, totalPlants = 80)

      val emptyStratumId =
          insertStratum(areaHa = BigDecimal(50), targetPlantingDensity = BigDecimal(5))
      val emptySubstratumId = insertSubstratum()

      // planting site in the same project with no plantings
      val otherPlantingSiteId = insertPlantingSite(projectId = projectId)

      // planting site in another project
      val otherProjectId = insertProject()
      insertPlantingSite(projectId = otherProjectId)

      val expected =
          listOf(
              PlantingSiteReportedPlantTotals(
                  id = plantingSiteId,
                  strata =
                      listOf(
                          PlantingSiteReportedPlantTotals.Stratum(
                              id = stratumId1,
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
                              substrata =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.Substratum(
                                          id = substratumId1a,
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
                                      PlantingSiteReportedPlantTotals.Substratum(
                                          id = substratumId1b,
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
                          PlantingSiteReportedPlantTotals.Stratum(
                              id = stratumId2,
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
                              substrata =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.Substratum(
                                          id = substratumId2,
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
                          PlantingSiteReportedPlantTotals.Stratum(
                              id = emptyStratumId,
                              plantsSinceLastObservation = 0,
                              substrata =
                                  listOf(
                                      PlantingSiteReportedPlantTotals.Substratum(
                                          id = emptySubstratumId,
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
                  strata = emptyList(),
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
  inner class CountReportedPlantsInSubstrata {
    @Test
    fun `returns substrata with nursery deliveries`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      val plantingSiteId = insertPlantingSite()

      insertStratum()
      val substratumId11 = insertSubstratum()
      val substratumId12 = insertSubstratum()

      insertStratum()
      val substratumId21 = insertSubstratum()

      // Original delivery to substratum 12, then reassignment to 11, so 12 shouldn't be counted as
      // planted any more.
      insertNurseryWithdrawal(purpose = WithdrawalPurpose.OutPlant)
      insertDelivery()
      insertPlanting(numPlants = 1, substratumId = substratumId12)
      insertPlanting(
          numPlants = -1,
          plantingTypeId = PlantingType.ReassignmentFrom,
          substratumId = substratumId12,
      )
      insertPlanting(
          numPlants = 1,
          plantingTypeId = PlantingType.ReassignmentTo,
          substratumId = substratumId11,
      )
      insertSpecies()
      insertPlanting(numPlants = 2, substratumId = substratumId21)

      insertNurseryWithdrawal(purpose = WithdrawalPurpose.OutPlant)
      insertDelivery()
      insertPlanting(numPlants = 4, substratumId = substratumId21)

      // Additional substratum with no plantings.
      insertSubstratum()

      assertEquals(
          mapOf(substratumId11 to 1L, substratumId21 to 6L),
          store.countReportedPlantsInSubstrata(plantingSiteId),
      )
    }
  }
}
