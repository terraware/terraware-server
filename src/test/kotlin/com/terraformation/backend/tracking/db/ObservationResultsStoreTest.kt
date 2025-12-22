package com.terraformation.backend.tracking.db

import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MangroveTide
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationMediaType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassDetailsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassQuadratDetailsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassQuadratSpeciesRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedTreesRow
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.tracking.model.BiomassQuadratModel
import com.terraformation.backend.tracking.model.BiomassQuadratSpeciesModel
import com.terraformation.backend.tracking.model.BiomassSpeciesModel
import com.terraformation.backend.tracking.model.ExistingBiomassDetailsModel
import com.terraformation.backend.tracking.model.ExistingRecordedTreeModel
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotMediaModel
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationStratumResultsModel
import com.terraformation.backend.tracking.model.ObservedPlotCoordinatesModel
import com.terraformation.backend.tracking.model.RecordedPlantModel
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ObservationResultsStoreTest : ObservationScenarioTest() {
  override val user = mockUser()

  @BeforeEach
  fun setUp() {
    every { user.canReadOrganization(organizationId) } returns true
  }

  @Nested
  inner class FetchByOrganizationId {
    @Test
    fun `results are in descending completed time order`() {
      val completedObservationId1 = insertObservation(completedTime = Instant.ofEpochSecond(1))
      val completedObservationId2 = insertObservation(completedTime = Instant.ofEpochSecond(2))
      val inProgressObservationId = insertObservation(state = ObservationState.InProgress)
      val upcomingObservationId = insertObservation(state = ObservationState.Upcoming)

      val results = resultsStore.fetchByOrganizationId(organizationId)

      assertEquals(
          listOf(
              completedObservationId2,
              completedObservationId1,
              upcomingObservationId,
              inProgressObservationId,
          ),
          results.map { it.observationId },
          "Observation IDs",
      )
    }

    @Test
    fun `respects depth`() {
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      insertObservation(completedTime = Instant.ofEpochSecond(1))
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      val plantResults =
          resultsStore.fetchByOrganizationId(organizationId, depth = ObservationResultsDepth.Plant)
      val plotResults =
          resultsStore.fetchByOrganizationId(organizationId, depth = ObservationResultsDepth.Plot)

      assertEquals(
          emptyList<RecordedPlantModel>(),
          plantResults[0].strata[0].substrata[0].monitoringPlots[0].plants,
          "Plant depth",
      )

      assertNull(
          plotResults[0].strata[0].substrata[0].monitoringPlots[0].plants,
          "Plot depth",
      )
    }

    @Test
    fun `returns photo metadata`() {
      val gpsCoordinates = point(2, 3)
      val position = ObservationPlotPosition.NortheastCorner

      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)
      insertFile(geolocation = gpsCoordinates)
      insertObservationMediaFile(caption = "selfie", position = position)

      val results = resultsStore.fetchByOrganizationId(organizationId)

      assertEquals(
          listOf(
              ObservationMonitoringPlotMediaModel(
                  caption = "selfie",
                  contentType = "image/jpeg",
                  fileId = inserted.fileId,
                  gpsCoordinates = gpsCoordinates,
                  isOriginal = true,
                  position = position,
                  type = ObservationMediaType.Plot,
              )
          ),
          results[0].strata[0].substrata[0].monitoringPlots[0].media,
      )
    }

    @Test
    fun `throws exception if no permission to read organization`() {
      every { user.canReadOrganization(organizationId) } returns false

      assertThrows<OrganizationNotFoundException> {
        resultsStore.fetchByOrganizationId(organizationId)
      }
    }
  }

  @Nested
  inner class FetchByPlantingSiteId {
    @Test
    fun `limit of 1 returns most recently completed observation`() {
      insertObservation(completedTime = Instant.ofEpochSecond(1))
      val mostRecentlyCompletedObservationId =
          insertObservation(completedTime = Instant.ofEpochSecond(3))
      insertObservation(completedTime = Instant.ofEpochSecond(2))

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId, limit = 1)

      assertEquals(
          listOf(mostRecentlyCompletedObservationId),
          results.map { it.observationId },
          "Observation IDs",
      )
    }

    @Test
    fun `associates monitoring plots with the substrata they were in at the time of the observation`() {
      // Plot starts off in substratum 1
      insertPlantingZone()
      val substratumId1 = insertPlantingSubzone()
      val plotId = insertMonitoringPlot()
      insertObservation(completedTime = Instant.ofEpochSecond(1))
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      // Then there's a map edit and the plot moves to substratum 2
      insertPlantingSiteHistory()
      insertPlantingZone()
      val substratumId2 = insertPlantingSubzone()
      monitoringPlotsDao.update(
          monitoringPlotsDao.fetchOneById(plotId)!!.copy(substratumId = substratumId2)
      )
      insertMonitoringPlotHistory()

      insertObservation(completedTime = Instant.ofEpochSecond(2))
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId)

      // Results are in reverse chronological order
      assertEquals(
          listOf(substratumId2),
          results[0].strata.flatMap { stratum ->
            stratum.substrata
                .filter { substratum ->
                  substratum.monitoringPlots.any { it.monitoringPlotId == plotId }
                }
                .map { it.substratumId }
          },
          "Substratum of monitoring plot in second observation",
      )
      assertEquals(
          listOf(substratumId1),
          results[1].strata.flatMap { stratum ->
            stratum.substrata
                .filter { substratum ->
                  substratum.monitoringPlots.any { it.monitoringPlotId == plotId }
                }
                .map { it.substratumId }
          },
          "Substratum of monitoring plot in first observation",
      )
    }

    @Test
    fun `includes monitoring plots in substrata that have subsequently been deleted`() {
      insertPlantingZone()
      val substratumId = insertPlantingSubzone()
      val plotId = insertMonitoringPlot()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      plantingSubzonesDao.deleteById(substratumId)

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId)

      assertNotEquals(
          emptyList<ObservationResultsModel>(),
          results,
          "Should have returned observation result",
      )
      assertEquals(
          listOf(plotId),
          results[0].strata.flatMap { stratum ->
            stratum.substrata.flatMap { substratum ->
              substratum.monitoringPlots.map { it.monitoringPlotId }
            }
          },
          "Monitoring plot IDs in observation",
      )
    }

    @Test
    fun `includes monitoring plots in strata that have subsequently been deleted`() {
      val stratumId = insertPlantingZone()
      insertPlantingSubzone()
      val plotId = insertMonitoringPlot()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      plantingZonesDao.deleteById(stratumId)

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId)

      assertNotEquals(
          emptyList<ObservationResultsModel>(),
          results,
          "Should have returned observation result",
      )
      assertEquals(
          listOf(plotId),
          results[0].strata.flatMap { stratum ->
            stratum.substrata.flatMap { substratum ->
              substratum.monitoringPlots.map { it.monitoringPlotId }
            }
          },
          "Monitoring plot IDs in observation",
      )
    }

    @Test
    fun `returns observed coordinates in counterclockwise position order`() {
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      val northwest = point(1)
      val northeast = point(2)
      val southwest = point(3)

      val id1 =
          insertObservedCoordinates(
              position = ObservationPlotPosition.NorthwestCorner,
              gpsCoordinates = northwest,
          )
      val id2 =
          insertObservedCoordinates(
              position = ObservationPlotPosition.SouthwestCorner,
              gpsCoordinates = southwest,
          )
      val id3 =
          insertObservedCoordinates(
              position = ObservationPlotPosition.NortheastCorner,
              gpsCoordinates = northeast,
          )

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId)

      val actualCoordinates = results[0].strata[0].substrata[0].monitoringPlots[0].coordinates

      assertEquals(
          listOf(
              ObservedPlotCoordinatesModel(id2, southwest, ObservationPlotPosition.SouthwestCorner),
              ObservedPlotCoordinatesModel(id3, northeast, ObservationPlotPosition.NortheastCorner),
              ObservedPlotCoordinatesModel(id1, northwest, ObservationPlotPosition.NorthwestCorner),
          ),
          actualCoordinates,
      )
    }

    @Test
    fun `returns stratum and substratum names even for strata that have subsequently been deleted`() {
      insertObservation(completedTime = Instant.EPOCH)
      insertPlantingZone(name = "Stratum 1")
      insertPlantingSubzone(name = "Substratum 1")
      insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)
      insertObservationPlotCondition(condition = ObservableCondition.AnimalDamage)
      val stratumId2 = insertPlantingZone(name = "Stratum 2")
      insertPlantingSubzone(name = "Substratum 2")
      insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)
      insertObservationPlotCondition(condition = ObservableCondition.Pests)

      plantingZonesDao.deleteById(stratumId2)

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId)

      val stratum1Result =
          results[0].strata.single { stratum ->
            stratum.substrata.any { substratum ->
              substratum.monitoringPlots.any { ObservableCondition.AnimalDamage in it.conditions }
            }
          }
      val stratum2Result =
          results[0].strata.single { stratum ->
            stratum.substrata.any { substratum ->
              substratum.monitoringPlots.any { ObservableCondition.Pests in it.conditions }
            }
          }

      assertEquals("Stratum 1", stratum1Result.name)
      assertEquals("Stratum 2", stratum2Result.name)
      assertNull(stratum2Result.stratumId, "ID of deleted stratum")
      assertEquals(
          listOf("Substratum 1"),
          stratum1Result.substrata.map { it.name },
          "Names of all substrata in stratum 1",
      )
      assertEquals(
          listOf("Substratum 2"),
          stratum2Result.substrata.map { it.name },
          "Names of all substrata in stratum 2",
      )
    }

    @Test
    fun `returns plot information`() {
      // Ad-hoc observations are not returned
      insertMonitoringPlot(isAdHoc = true)
      val adHocObservationId = insertObservation(completedTime = Instant.EPOCH, isAdHoc = true)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)
      insertObservationPlotCondition(condition = ObservableCondition.AnimalDamage)
      insertObservationPlotCondition(condition = ObservableCondition.Fungus)
      insertObservationPlotCondition(condition = ObservableCondition.UnfavorableWeather)

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId)
      assertNull(results.find { it.observationId == adHocObservationId }, "No ad-hoc observation")

      val observationResults = results.first()
      assertEquals(inserted.observationId, observationResults.observationId, "Observation ID")
      assertFalse(observationResults.isAdHoc, "Observation Is Ad Hoc")
      assertNull(observationResults.mortalityRate, "Observation mortality rate with no plants")
      assertNull(observationResults.survivalRate, "Observation survival rate with no plants")

      val plotResults = observationResults.strata.first().substrata.first().monitoringPlots.first()
      assertEquals(inserted.monitoringPlotId, plotResults.monitoringPlotId, "Plot ID")
      assertFalse(plotResults.isAdHoc, "Plot Is Ad Hoc")
      assertEquals(2L, plotResults.monitoringPlotNumber, "Plot number")
      assertSetEquals(
          setOf(
              ObservableCondition.AnimalDamage,
              ObservableCondition.Fungus,
              ObservableCondition.UnfavorableWeather,
          ),
          plotResults.conditions,
          "Plot conditions",
      )
    }

    @Test
    fun `returns ad-hoc observation with biomass details`() {
      val herbaceousSpeciesId1 = insertSpecies()
      val herbaceousSpeciesId2 = insertSpecies()
      val treeSpeciesId1 = insertSpecies()
      val treeSpeciesId2 = insertSpecies()

      // Assigned observation is omitted
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      val assignedObservationId = insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      val plotId = insertMonitoringPlot(isAdHoc = true, plantingSubzoneId = null)
      val observationId =
          insertObservation(
              completedTime = Instant.EPOCH,
              isAdHoc = true,
              observationType = ObservationType.BiomassMeasurements,
          )
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      insertObservationBiomassDetails(
          ObservationBiomassDetailsRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              description = "description",
              forestTypeId = BiomassForestType.Mangrove,
              herbaceousCoverPercent = 10,
              ph = BigDecimal.valueOf(6.5),
              salinityPpt = BigDecimal.valueOf(20),
              smallTreesCountHigh = 10,
              smallTreesCountLow = 0,
              soilAssessment = "soil",
              tideId = MangroveTide.High,
              tideTime = Instant.ofEpochSecond(123),
              waterDepthCm = 2,
          )
      )

      val biomassHerbaceousSpeciesId1 =
          insertObservationBiomassSpecies(
              observationId = observationId,
              monitoringPlotId = plotId,
              speciesId = herbaceousSpeciesId1,
              isInvasive = true,
              isThreatened = false,
          )

      val biomassHerbaceousSpeciesId2 =
          insertObservationBiomassSpecies(
              observationId = observationId,
              monitoringPlotId = plotId,
              speciesId = herbaceousSpeciesId2,
              isInvasive = false,
              isThreatened = false,
          )

      val biomassHerbaceousSpeciesId3 =
          insertObservationBiomassSpecies(
              observationId = observationId,
              monitoringPlotId = plotId,
              scientificName = "Herbaceous species",
              commonName = "Common herb",
              isInvasive = false,
              isThreatened = true,
          )

      val biomassTreeSpeciesId1 =
          insertObservationBiomassSpecies(
              observationId = observationId,
              monitoringPlotId = plotId,
              speciesId = treeSpeciesId1,
              isInvasive = false,
              isThreatened = true,
          )

      val biomassTreeSpeciesId2 =
          insertObservationBiomassSpecies(
              observationId = observationId,
              monitoringPlotId = plotId,
              speciesId = treeSpeciesId2,
              isInvasive = true,
              isThreatened = false,
          )

      val biomassTreeSpeciesId3 =
          insertObservationBiomassSpecies(
              observationId = observationId,
              monitoringPlotId = plotId,
              scientificName = "Tree species",
              commonName = "Common tree",
              isInvasive = false,
              isThreatened = false,
          )

      insertObservationBiomassQuadratDetails(
          ObservationBiomassQuadratDetailsRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.NortheastCorner,
              description = "NE description",
          )
      )

      insertObservationBiomassQuadratDetails(
          ObservationBiomassQuadratDetailsRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.NorthwestCorner,
              description = "NW description",
          )
      )

      insertObservationBiomassQuadratDetails(
          ObservationBiomassQuadratDetailsRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.SoutheastCorner,
              description = "SE description",
          )
      )

      insertObservationBiomassQuadratDetails(
          ObservationBiomassQuadratDetailsRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.SouthwestCorner,
              description = "SW description",
          )
      )

      insertObservationBiomassQuadratSpecies(
          ObservationBiomassQuadratSpeciesRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.NortheastCorner,
              abundancePercent = 40,
              biomassSpeciesId = biomassHerbaceousSpeciesId1,
          )
      )

      insertObservationBiomassQuadratSpecies(
          ObservationBiomassQuadratSpeciesRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.NorthwestCorner,
              abundancePercent = 5,
              biomassSpeciesId = biomassHerbaceousSpeciesId3,
          )
      )

      insertObservationBiomassQuadratSpecies(
          ObservationBiomassQuadratSpeciesRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.NorthwestCorner,
              abundancePercent = 60,
              biomassSpeciesId = biomassHerbaceousSpeciesId2,
          )
      )

      insertObservationBiomassQuadratSpecies(
          ObservationBiomassQuadratSpeciesRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.SoutheastCorner,
              abundancePercent = 90,
              biomassSpeciesId = biomassHerbaceousSpeciesId1,
          )
      )

      val treeId1 =
          insertRecordedTree(
              RecordedTreesRow(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  biomassSpeciesId = biomassTreeSpeciesId1,
                  gpsCoordinates = point(1),
                  isDead = false,
                  shrubDiameterCm = 25,
                  treeGrowthFormId = TreeGrowthForm.Shrub,
                  treeNumber = 1,
                  trunkNumber = 1,
              ),
          )

      // Insert this first to show sort by treeNumber
      val treeId3 =
          insertRecordedTree(
              RecordedTreesRow(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  biomassSpeciesId = biomassTreeSpeciesId2,
                  diameterAtBreastHeightCm = BigDecimal.TEN,
                  pointOfMeasurementM = BigDecimal.valueOf(1.5),
                  gpsCoordinates = point(2),
                  isDead = false,
                  treeGrowthFormId = TreeGrowthForm.Trunk,
                  treeNumber = 3,
                  trunkNumber = 1,
              ),
          )

      val treeId4 =
          insertRecordedTree(
              RecordedTreesRow(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  biomassSpeciesId = biomassTreeSpeciesId2,
                  diameterAtBreastHeightCm = BigDecimal.TWO,
                  pointOfMeasurementM = BigDecimal.valueOf(1.1),
                  gpsCoordinates = point(2),
                  isDead = false,
                  treeGrowthFormId = TreeGrowthForm.Trunk,
                  treeNumber = 3,
                  trunkNumber = 2,
              ),
          )

      val treeId2 =
          insertRecordedTree(
              RecordedTreesRow(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  biomassSpeciesId = biomassTreeSpeciesId3,
                  diameterAtBreastHeightCm = BigDecimal.TWO,
                  pointOfMeasurementM = BigDecimal.valueOf(1.3),
                  heightM = BigDecimal.TEN,
                  isDead = true,
                  treeGrowthFormId = TreeGrowthForm.Tree,
                  treeNumber = 2,
                  trunkNumber = 1,
              ),
          )

      val expectedBiomassModel =
          ExistingBiomassDetailsModel(
              description = "description",
              forestType = BiomassForestType.Mangrove,
              herbaceousCoverPercent = 10,
              observationId = observationId,
              ph = BigDecimal.valueOf(6.5),
              quadrats =
                  mapOf(
                      ObservationPlotPosition.NortheastCorner to
                          BiomassQuadratModel(
                              description = "NE description",
                              species =
                                  setOf(
                                      BiomassQuadratSpeciesModel(
                                          abundancePercent = 40,
                                          speciesId = herbaceousSpeciesId1,
                                      )
                                  ),
                          ),
                      ObservationPlotPosition.NorthwestCorner to
                          BiomassQuadratModel(
                              description = "NW description",
                              species =
                                  setOf(
                                      BiomassQuadratSpeciesModel(
                                          abundancePercent = 60,
                                          speciesId = herbaceousSpeciesId2,
                                      ),
                                      BiomassQuadratSpeciesModel(
                                          abundancePercent = 5,
                                          speciesName = "Herbaceous species",
                                      ),
                                  ),
                          ),
                      ObservationPlotPosition.SoutheastCorner to
                          BiomassQuadratModel(
                              description = "SE description",
                              species =
                                  setOf(
                                      BiomassQuadratSpeciesModel(
                                          abundancePercent = 90,
                                          speciesId = herbaceousSpeciesId1,
                                      )
                                  ),
                          ),
                      ObservationPlotPosition.SouthwestCorner to
                          BiomassQuadratModel(
                              description = "SW description",
                              species = emptySet(),
                          ),
                  ),
              salinityPpt = BigDecimal.valueOf(20),
              smallTreeCountRange = 0 to 10,
              soilAssessment = "soil",
              species =
                  setOf(
                      BiomassSpeciesModel(
                          speciesId = herbaceousSpeciesId1,
                          isInvasive = true,
                          isThreatened = false,
                      ),
                      BiomassSpeciesModel(
                          speciesId = herbaceousSpeciesId2,
                          isInvasive = false,
                          isThreatened = false,
                      ),
                      BiomassSpeciesModel(
                          scientificName = "Herbaceous species",
                          commonName = "Common herb",
                          isInvasive = false,
                          isThreatened = true,
                      ),
                      BiomassSpeciesModel(
                          speciesId = treeSpeciesId1,
                          isInvasive = false,
                          isThreatened = true,
                      ),
                      BiomassSpeciesModel(
                          speciesId = treeSpeciesId2,
                          isInvasive = true,
                          isThreatened = false,
                      ),
                      BiomassSpeciesModel(
                          scientificName = "Tree species",
                          commonName = "Common tree",
                          isInvasive = false,
                          isThreatened = false,
                      ),
                  ),
              plotId = plotId,
              tide = MangroveTide.High,
              tideTime = Instant.ofEpochSecond(123),
              trees =
                  listOf(
                      ExistingRecordedTreeModel(
                          id = treeId1,
                          gpsCoordinates = point(1),
                          isDead = false,
                          shrubDiameterCm = 25,
                          speciesId = treeSpeciesId1,
                          treeGrowthForm = TreeGrowthForm.Shrub,
                          treeNumber = 1,
                          trunkNumber = 1,
                      ),
                      ExistingRecordedTreeModel(
                          id = treeId2,
                          diameterAtBreastHeightCm = BigDecimal.TWO,
                          pointOfMeasurementM = BigDecimal.valueOf(1.3),
                          gpsCoordinates = null,
                          heightM = BigDecimal.TEN,
                          isDead = true,
                          speciesName = "Tree species",
                          treeGrowthForm = TreeGrowthForm.Tree,
                          treeNumber = 2,
                          trunkNumber = 1,
                      ),
                      ExistingRecordedTreeModel(
                          id = treeId3,
                          diameterAtBreastHeightCm = BigDecimal.TEN,
                          pointOfMeasurementM = BigDecimal.valueOf(1.5),
                          gpsCoordinates = point(2),
                          isDead = false,
                          speciesId = treeSpeciesId2,
                          treeGrowthForm = TreeGrowthForm.Trunk,
                          treeNumber = 3,
                          trunkNumber = 1,
                      ),
                      ExistingRecordedTreeModel(
                          id = treeId4,
                          diameterAtBreastHeightCm = BigDecimal.TWO,
                          pointOfMeasurementM = BigDecimal.valueOf(1.1),
                          gpsCoordinates = point(2),
                          isDead = false,
                          speciesId = treeSpeciesId2,
                          treeGrowthForm = TreeGrowthForm.Trunk,
                          treeNumber = 3,
                          trunkNumber = 2,
                      ),
                  ),
              waterDepthCm = 2,
          )

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId, isAdHoc = true)
      assertNull(
          results.find { it.observationId == assignedObservationId },
          "No assigned observation",
      )

      val observationResults = results.first()
      assertEquals(observationId, observationResults.observationId, "Observation ID")
      assertEquals(
          ObservationType.BiomassMeasurements,
          observationResults.observationType,
          "Observation type",
      )
      assertTrue(observationResults.isAdHoc, "Observation Is Ad Hoc")
      assertEquals(
          emptyList<ObservationStratumResultsModel>(),
          observationResults.strata,
          "No stratum in observation",
      )

      assertEquals(expectedBiomassModel, observationResults.biomassDetails, "Biomass details")

      val plotResults = observationResults.adHocPlot!!
      assertEquals(plotId, plotResults.monitoringPlotId, "Plot ID")
      assertTrue(plotResults.isAdHoc, "Plot Is Ad Hoc")
      assertEquals(2L, plotResults.monitoringPlotNumber, "Plot number")
    }

    @Test
    fun `returns plot overlaps in both directions`() {
      insertPlantingZone()
      insertPlantingSubzone()
      insertObservation(completedTime = Instant.EPOCH)
      val oldPlotId1 = insertMonitoringPlot()
      val oldPlotId2 = insertMonitoringPlot()
      val currentPlotId = insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)
      val newPlotId1 = insertMonitoringPlot()
      val newPlotId2 = insertMonitoringPlot()

      // Current plot overlaps with two older plots and is overlapped by two newer plots.
      insertMonitoringPlotOverlap(monitoringPlotId = currentPlotId, overlapsPlotId = oldPlotId1)
      insertMonitoringPlotOverlap(monitoringPlotId = currentPlotId, overlapsPlotId = oldPlotId2)
      insertMonitoringPlotOverlap(monitoringPlotId = newPlotId1, overlapsPlotId = currentPlotId)
      insertMonitoringPlotOverlap(monitoringPlotId = newPlotId2, overlapsPlotId = currentPlotId)

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId)
      val plotResults = results[0].strata[0].substrata[0].monitoringPlots[0]

      assertSetEquals(
          setOf(oldPlotId1, oldPlotId2),
          plotResults.overlapsWithPlotIds,
          "Overlaps with",
      )
      assertSetEquals(
          setOf(newPlotId1, newPlotId2),
          plotResults.overlappedByPlotIds,
          "Overlapped by",
      )
    }

    @Test
    fun `throws exception if no permission to read planting site`() {
      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> {
        resultsStore.fetchByPlantingSiteId(plantingSiteId)
      }
    }
  }

  @Nested
  inner class IncompletePlots {
    @Test
    fun `planting density calculations only consider completed plots`() {
      insertSpecies()
      insertPlantingZone()
      insertPlantingSubzone()
      insertObservation()

      // Plot with one plant
      val completePlotId = insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId)

      // Plot with no plant because it is not observed
      val incompletePlotId = insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId)

      observationStore.completePlot(
          inserted.observationId,
          completePlotId,
          emptySet(),
          "Notes",
          Instant.EPOCH,
          listOf(
              RecordedPlantsRow(
                  certaintyId = RecordedSpeciesCertainty.Known,
                  gpsCoordinates = point(1),
                  observationId = inserted.observationId,
                  monitoringPlotId = completePlotId,
                  speciesId = inserted.speciesId,
                  statusId = RecordedPlantStatus.Live,
              )
          ),
      )

      observationStore.abandonObservation(inserted.observationId)

      val results = resultsStore.fetchOneById(inserted.observationId)
      val stratumResults = results.strata[0]
      val substratumResults = stratumResults.substrata[0]
      val incompletePlotResults =
          substratumResults.monitoringPlots.first { it.monitoringPlotId == incompletePlotId }
      val completePlotResults =
          substratumResults.monitoringPlots.first { it.monitoringPlotId == completePlotId }

      assertEquals(ObservationState.Abandoned, results.state, "Observation state")
      assertEquals(11, results.plantingDensity, "Site Planting Density")
      assertEquals(null, results.plantingDensityStdDev, "Site Planting Density Standard Deviation")
      assertEquals(11, stratumResults.plantingDensity, "Stratum Planting Density")
      assertEquals(
          null,
          stratumResults.plantingDensityStdDev,
          "Stratum Planting Density Standard Deviation",
      )
      assertEquals(11, substratumResults.plantingDensity, "Substratum Planting Density")
      assertEquals(
          null,
          substratumResults.plantingDensityStdDev,
          "Substratum Planting Density Standard Deviation",
      )

      assertEquals(11, completePlotResults.plantingDensity, "Completed Plot Planting Density")
      assertEquals(
          ObservationPlotStatus.Completed,
          completePlotResults.status,
          "Completed Plot Status",
      )
      assertEquals(0, incompletePlotResults.plantingDensity, "Incomplete Plot Planting Density")
      assertEquals(
          ObservationPlotStatus.NotObserved,
          incompletePlotResults.status,
          "Incomplete Plot Status",
      )
    }

    @Test
    fun `planting site summary only considers substrata with completed plots`() {
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      insertPlantingZone()

      // Each substratum only has one plot
      val substratumId1 = insertPlantingSubzone()
      val plotId1 = insertMonitoringPlot()
      val substratumId2 = insertPlantingSubzone()
      val plotId2 = insertMonitoringPlot()
      val substratumId3 = insertPlantingSubzone()
      val plotId3 = insertMonitoringPlot()

      val neverObservedPlotId = insertMonitoringPlot(plantingSubzoneId = substratumId3)

      // For the first observation, plot1 and plot2 are both assigned and completed.
      clock.instant = Instant.ofEpochSecond(300)
      val observationId1 = insertObservation()
      insertObservationPlot(
          observationId = observationId1,
          monitoringPlotId = plotId1,
          claimedBy = user.userId,
      )
      insertObservationPlot(
          observationId = observationId1,
          monitoringPlotId = plotId2,
          claimedBy = user.userId,
      )
      observationStore.populateCumulativeDead(observationId1)

      observationStore.completePlot(
          observationId1,
          plotId1,
          emptySet(),
          "Notes 1",
          clock.instant,
          listOf(
              RecordedPlantsRow(
                  certaintyId = RecordedSpeciesCertainty.Known,
                  gpsCoordinates = point(1),
                  observationId = observationId1,
                  monitoringPlotId = plotId1,
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Live,
              )
          ),
      )

      observationStore.completePlot(
          observationId1,
          plotId2,
          emptySet(),
          "Notes 2",
          clock.instant,
          listOf(
              RecordedPlantsRow(
                  certaintyId = RecordedSpeciesCertainty.Known,
                  gpsCoordinates = point(1),
                  observationId = observationId1,
                  monitoringPlotId = plotId2,
                  speciesId = speciesId2,
                  statusId = RecordedPlantStatus.Live,
              ),
              RecordedPlantsRow(
                  certaintyId = RecordedSpeciesCertainty.Known,
                  gpsCoordinates = point(1),
                  observationId = observationId1,
                  monitoringPlotId = plotId2,
                  speciesId = speciesId2,
                  statusId = RecordedPlantStatus.Live,
              ),
          ),
      )

      // For the second observation, plot2 and plot3 are both assigned, only plot 3 is completed.
      clock.instant = Instant.ofEpochSecond(600)
      val observationId2 = insertObservation()
      insertObservationPlot(
          observationId = observationId2,
          monitoringPlotId = plotId2,
          claimedBy = user.userId,
      )
      insertObservationPlot(
          observationId = observationId2,
          monitoringPlotId = plotId3,
          claimedBy = user.userId,
      )

      // Add a second plot to substratum 3, to test for if substratum has no completed time yet.
      insertObservationPlot(observationId = observationId2, monitoringPlotId = neverObservedPlotId)

      observationStore.populateCumulativeDead(observationId2)
      observationStore.completePlot(
          observationId2,
          plotId3,
          emptySet(),
          "Notes 3",
          clock.instant,
          emptyList(),
      )
      observationStore.abandonObservation(observationId2)

      val results1 = resultsStore.fetchOneById(observationId1)
      val observation1Substratum1Result =
          results1.strata.flatMap { it.substrata }.first { it.substratumId == substratumId1 }
      val observation1Substratum2Result =
          results1.strata.flatMap { it.substrata }.first { it.substratumId == substratumId2 }

      val results2 = resultsStore.fetchOneById(observationId2)
      val observation2Substratum2Result =
          results2.strata.flatMap { it.substrata }.first { it.substratumId == substratumId2 }
      val observation2Substratum3Result =
          results2.strata.flatMap { it.substrata }.first { it.substratumId == substratumId3 }

      assertEquals(
          ObservationPlotStatus.Completed,
          observation1Substratum1Result.monitoringPlots[0].status,
          "Plot status in observation 1 substratum 1",
      )
      assertEquals(
          ObservationPlotStatus.Completed,
          observation1Substratum2Result.monitoringPlots[0].status,
          "Plot status in observation 1 substratum 2",
      )
      assertEquals(
          ObservationPlotStatus.NotObserved,
          observation2Substratum2Result.monitoringPlots[0].status,
          "Plot status in observation 2 substratum 2",
      )
      assertEquals(
          ObservationPlotStatus.Completed,
          observation2Substratum3Result.monitoringPlots
              .first { it.monitoringPlotId == plotId3 }
              .status,
          "Plot status in observation 2 substratum 3",
      )
      assertEquals(
          ObservationPlotStatus.NotObserved,
          observation2Substratum3Result.monitoringPlots
              .first { it.monitoringPlotId == neverObservedPlotId }
              .status,
          "Plot status in observation 2 substratum 3",
      )

      val summary = resultsStore.fetchSummariesForPlantingSite(inserted.plantingSiteId, 1).first()
      assertSetEquals(
          setOf(
              observation1Substratum1Result,
              observation1Substratum2Result,
              observation2Substratum3Result,
          ),
          summary.strata.flatMap { it.substrata }.toSet(),
          "Substrata used for summary did not include the Incomplete substratum result",
      )
    }
  }

  @Nested
  inner class Scenarios {
    @Test
    fun `site with two observations`() {
      runScenario(
          "/tracking/observation/TwoObservations",
          numObservations = 2,
          sizeMeters = 30,
          plantingSiteId,
      )
    }

    @Test
    fun `partial observations of disjoint substratum lists`() {
      runScenario(
          "/tracking/observation/DisjointSubzones",
          numObservations = 3,
          sizeMeters = 30,
          plantingSiteId,
      )
    }

    @Test
    fun `permanent plots being added and removed`() {
      runScenario(
          "/tracking/observation/PermanentPlotChanges",
          numObservations = 3,
          sizeMeters = 25,
          plantingSiteId,
      )
    }

    @Test
    fun `fetch observation summary`() {
      runSummaryScenario(
          "/tracking/observation/ObservationsSummary",
          numObservations = 3,
          numSpecies = 3,
          sizeMeters = 25,
          plantingSiteId,
      )
    }

    @Test
    fun `fetch observation summary with temp plots`() {
      val plantingSiteId =
          insertPlantingSite(x = 0, areaHa = BigDecimal(2500), survivalRateIncludesTempPlots = true)
      every { user.canReadPlantingSite(plantingSiteId) } returns true

      runSummaryScenario(
          "/tracking/observation/ObservationsSummaryTempPlots",
          numObservations = 3,
          numSpecies = 3,
          sizeMeters = 25,
          plantingSiteId,
      )
    }

    @Test
    fun `plots without observations don't count towards survival rate denominator sums`() {
      runSummaryScenario(
          "/tracking/observation/SurvivalRateNoObservations",
          numObservations = 1,
          numSpecies = 2,
          sizeMeters = 30,
          plantingSiteId,
      )
    }
  }
}
