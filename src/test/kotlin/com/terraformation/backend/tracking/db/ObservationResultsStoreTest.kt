package com.terraformation.backend.tracking.db

import com.opencsv.CSVReader
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MangroveTide
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPhotoType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneHistoryId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneHistoryId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassDetailsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassQuadratDetailsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassQuadratSpeciesRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedTreesRow
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_REQUESTED_SUBZONES
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.model.BiomassQuadratModel
import com.terraformation.backend.tracking.model.BiomassQuadratSpeciesModel
import com.terraformation.backend.tracking.model.BiomassSpeciesModel
import com.terraformation.backend.tracking.model.ExistingBiomassDetailsModel
import com.terraformation.backend.tracking.model.ExistingRecordedTreeModel
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotPhotoModel
import com.terraformation.backend.tracking.model.ObservationPlantingZoneResultsModel
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationRollupResultsModel
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsModel
import com.terraformation.backend.tracking.model.ObservedPlotCoordinatesModel
import com.terraformation.backend.util.calculateAreaHectares
import io.mockk.every
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.file.NoSuchFileException
import java.time.Instant
import kotlin.math.sqrt
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.MultiPolygon

class ObservationResultsStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val allSpeciesNames = mutableSetOf<String>()
  private val permanentPlotNumbers = mutableSetOf<String>()
  private val speciesIds = mutableMapOf<String, SpeciesId>()

  private val speciesNames: Map<SpeciesId, String> by lazy {
    speciesIds.entries.associate { it.value to it.key }
  }

  private val clock = TestClock()
  private val observationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        observationRequestedSubzonesDao,
        ParentStore(dslContext),
        recordedPlantsDao)
  }
  private val resultsStore by lazy { ObservationResultsStore(dslContext) }

  private lateinit var organizationId: OrganizationId
  private lateinit var plantingSiteId: PlantingSiteId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    plantingSiteId = insertPlantingSite(x = 0, areaHa = BigDecimal(2500))

    every { user.canReadObservation(any()) } returns true
    every { user.canReadOrganization(organizationId) } returns true
    every { user.canReadPlantingSite(plantingSiteId) } returns true
    every { user.canUpdateObservation(any()) } returns true
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
          "Observation IDs")
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
      insertFile()
      insertObservationPhoto(gpsCoordinates = gpsCoordinates, position = position)

      val results = resultsStore.fetchByOrganizationId(organizationId)

      assertEquals(
          listOf(
              ObservationMonitoringPlotPhotoModel(
                  inserted.fileId, gpsCoordinates, position, ObservationPhotoType.Plot)),
          results[0].plantingZones[0].plantingSubzones[0].monitoringPlots[0].photos)
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
          "Observation IDs")
    }

    @Test
    fun `associates monitoring plots with the subzones they were in at the time of the observation`() {
      // Plot starts off in subzone 1
      insertPlantingZone()
      val subzoneId1 = insertPlantingSubzone()
      val plotId = insertMonitoringPlot()
      insertObservation(completedTime = Instant.ofEpochSecond(1))
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      // Then there's a map edit and the plot moves to subzone 2
      insertPlantingSiteHistory()
      insertPlantingZone()
      val subzoneId2 = insertPlantingSubzone()
      monitoringPlotsDao.update(
          monitoringPlotsDao.fetchOneById(plotId)!!.copy(plantingSubzoneId = subzoneId2))
      insertMonitoringPlotHistory()

      insertObservation(completedTime = Instant.ofEpochSecond(2))
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId)

      // Results are in reverse chronological order
      assertEquals(
          listOf(subzoneId2),
          results[0].plantingZones.flatMap { zone ->
            zone.plantingSubzones
                .filter { subzone -> subzone.monitoringPlots.any { it.monitoringPlotId == plotId } }
                .map { it.plantingSubzoneId }
          },
          "Subzone of monitoring plot in second observation")
      assertEquals(
          listOf(subzoneId1),
          results[1].plantingZones.flatMap { zone ->
            zone.plantingSubzones
                .filter { subzone -> subzone.monitoringPlots.any { it.monitoringPlotId == plotId } }
                .map { it.plantingSubzoneId }
          },
          "Subzone of monitoring plot in first observation")
    }

    @Test
    fun `includes monitoring plots in subzones that have subsequently been deleted`() {
      insertPlantingZone()
      val subzoneId = insertPlantingSubzone()
      val plotId = insertMonitoringPlot()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      plantingSubzonesDao.deleteById(subzoneId)

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId)

      assertNotEquals(
          emptyList<ObservationResultsModel>(), results, "Should have returned observation result")
      assertEquals(
          listOf(plotId),
          results[0].plantingZones.flatMap { zone ->
            zone.plantingSubzones.flatMap { subzone ->
              subzone.monitoringPlots.map { it.monitoringPlotId }
            }
          },
          "Monitoring plot IDs in observation")
    }

    @Test
    fun `includes monitoring plots in zones that have subsequently been deleted`() {
      val zoneId = insertPlantingZone()
      insertPlantingSubzone()
      val plotId = insertMonitoringPlot()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      plantingZonesDao.deleteById(zoneId)

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId)

      assertNotEquals(
          emptyList<ObservationResultsModel>(), results, "Should have returned observation result")
      assertEquals(
          listOf(plotId),
          results[0].plantingZones.flatMap { zone ->
            zone.plantingSubzones.flatMap { subzone ->
              subzone.monitoringPlots.map { it.monitoringPlotId }
            }
          },
          "Monitoring plot IDs in observation")
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
              position = ObservationPlotPosition.NorthwestCorner, gpsCoordinates = northwest)
      val id2 =
          insertObservedCoordinates(
              position = ObservationPlotPosition.SouthwestCorner, gpsCoordinates = southwest)
      val id3 =
          insertObservedCoordinates(
              position = ObservationPlotPosition.NortheastCorner, gpsCoordinates = northeast)

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId)

      val actualCoordinates =
          results[0].plantingZones[0].plantingSubzones[0].monitoringPlots[0].coordinates

      assertEquals(
          listOf(
              ObservedPlotCoordinatesModel(id2, southwest, ObservationPlotPosition.SouthwestCorner),
              ObservedPlotCoordinatesModel(id3, northeast, ObservationPlotPosition.NortheastCorner),
              ObservedPlotCoordinatesModel(id1, northwest, ObservationPlotPosition.NorthwestCorner),
          ),
          actualCoordinates)
    }

    @Test
    fun `returns zone and subzone names even for zones that have subsequently been deleted`() {
      insertObservation(completedTime = Instant.EPOCH)
      insertPlantingZone(name = "Zone 1")
      insertPlantingSubzone(name = "Subzone 1")
      insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)
      insertObservationPlotCondition(condition = ObservableCondition.AnimalDamage)
      val zoneId2 = insertPlantingZone(name = "Zone 2")
      insertPlantingSubzone(name = "Subzone 2")
      insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)
      insertObservationPlotCondition(condition = ObservableCondition.Pests)

      plantingZonesDao.deleteById(zoneId2)

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId)

      val zone1Result =
          results[0].plantingZones.single { zone ->
            zone.plantingSubzones.any { subzone ->
              subzone.monitoringPlots.any { ObservableCondition.AnimalDamage in it.conditions }
            }
          }
      val zone2Result =
          results[0].plantingZones.single { zone ->
            zone.plantingSubzones.any { subzone ->
              subzone.monitoringPlots.any { ObservableCondition.Pests in it.conditions }
            }
          }

      assertEquals("Zone 1", zone1Result.name)
      assertEquals("Zone 2", zone2Result.name)
      assertNull(zone2Result.plantingZoneId, "ID of deleted planting zone")
      assertEquals(
          listOf("Subzone 1"),
          zone1Result.plantingSubzones.map { it.name },
          "Names of all subzones in zone 1")
      assertEquals(
          listOf("Subzone 2"),
          zone2Result.plantingSubzones.map { it.name },
          "Names of all subzones in zone 2")
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

      val plotResults =
          observationResults.plantingZones.first().plantingSubzones.first().monitoringPlots.first()
      assertEquals(inserted.monitoringPlotId, plotResults.monitoringPlotId, "Plot ID")
      assertFalse(plotResults.isAdHoc, "Plot Is Ad Hoc")
      assertEquals(2L, plotResults.monitoringPlotNumber, "Plot number")
      assertEquals(
          setOf(
              ObservableCondition.AnimalDamage,
              ObservableCondition.Fungus,
              ObservableCondition.UnfavorableWeather),
          plotResults.conditions,
          "Plot conditions")
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
              observationType = ObservationType.BiomassMeasurements)
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
          ))

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
          ))

      insertObservationBiomassQuadratDetails(
          ObservationBiomassQuadratDetailsRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.NorthwestCorner,
              description = "NW description",
          ))

      insertObservationBiomassQuadratDetails(
          ObservationBiomassQuadratDetailsRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.SoutheastCorner,
              description = "SE description",
          ))

      insertObservationBiomassQuadratDetails(
          ObservationBiomassQuadratDetailsRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.SouthwestCorner,
              description = "SW description",
          ))

      insertObservationBiomassQuadratSpecies(
          ObservationBiomassQuadratSpeciesRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.NortheastCorner,
              abundancePercent = 40,
              biomassSpeciesId = biomassHerbaceousSpeciesId1,
          ))

      insertObservationBiomassQuadratSpecies(
          ObservationBiomassQuadratSpeciesRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.NorthwestCorner,
              abundancePercent = 5,
              biomassSpeciesId = biomassHerbaceousSpeciesId3,
          ))

      insertObservationBiomassQuadratSpecies(
          ObservationBiomassQuadratSpeciesRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.NorthwestCorner,
              abundancePercent = 60,
              biomassSpeciesId = biomassHerbaceousSpeciesId2,
          ))

      insertObservationBiomassQuadratSpecies(
          ObservationBiomassQuadratSpeciesRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              positionId = ObservationPlotPosition.SoutheastCorner,
              abundancePercent = 90,
              biomassSpeciesId = biomassHerbaceousSpeciesId1,
          ))

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
                                      ))),
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
                                  )),
                      ObservationPlotPosition.SoutheastCorner to
                          BiomassQuadratModel(
                              description = "SE description",
                              species =
                                  setOf(
                                      BiomassQuadratSpeciesModel(
                                          abundancePercent = 90,
                                          speciesId = herbaceousSpeciesId1,
                                      ))),
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
                      )),
              waterDepthCm = 2,
          )

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId, isAdHoc = true)
      assertNull(
          results.find { it.observationId == assignedObservationId }, "No assigned observation")

      val observationResults = results.first()
      assertEquals(observationId, observationResults.observationId, "Observation ID")
      assertEquals(
          ObservationType.BiomassMeasurements,
          observationResults.observationType,
          "Observation type")
      assertTrue(observationResults.isAdHoc, "Observation Is Ad Hoc")
      assertEquals(
          emptyList<ObservationPlantingZoneResultsModel>(),
          observationResults.plantingZones,
          "No zone in observation")

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
      val plotResults = results[0].plantingZones[0].plantingSubzones[0].monitoringPlots[0]

      assertEquals(setOf(oldPlotId1, oldPlotId2), plotResults.overlapsWithPlotIds, "Overlaps with")
      assertEquals(setOf(newPlotId1, newPlotId2), plotResults.overlappedByPlotIds, "Overlapped by")
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
              )))

      observationStore.abandonObservation(inserted.observationId)

      val results = resultsStore.fetchOneById(inserted.observationId)
      val zoneResults = results.plantingZones[0]
      val subzoneResults = zoneResults.plantingSubzones[0]
      val incompletePlotResults =
          subzoneResults.monitoringPlots.first { it.monitoringPlotId == incompletePlotId }
      val completePlotResults =
          subzoneResults.monitoringPlots.first { it.monitoringPlotId == completePlotId }

      assertEquals(ObservationState.Abandoned, results.state, "Observation state")
      assertEquals(11, results.plantingDensity, "Site Planting Density")
      assertEquals(null, results.plantingDensityStdDev, "Site Planting Density Standard Deviation")
      assertEquals(11, zoneResults.plantingDensity, "Zone Planting Density")
      assertEquals(
          null, zoneResults.plantingDensityStdDev, "Zone Planting Density Standard Deviation")
      assertEquals(11, subzoneResults.plantingDensity, "Subzone Planting Density")
      assertEquals(
          null, subzoneResults.plantingDensityStdDev, "Subzone Planting Density Standard Deviation")

      assertEquals(11, completePlotResults.plantingDensity, "Completed Plot Planting Density")
      assertEquals(
          ObservationPlotStatus.Completed, completePlotResults.status, "Completed Plot Status")
      assertEquals(0, incompletePlotResults.plantingDensity, "Incomplete Plot Planting Density")
      assertEquals(
          ObservationPlotStatus.NotObserved, incompletePlotResults.status, "Incomplete Plot Status")
    }

    @Test
    fun `planting site summary only considers subzones with completed plots`() {
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      insertPlantingZone()

      // Each subzone only has one plot
      val subzoneId1 = insertPlantingSubzone()
      val plotId1 = insertMonitoringPlot()
      val subzoneId2 = insertPlantingSubzone()
      val plotId2 = insertMonitoringPlot()
      val subzoneId3 = insertPlantingSubzone()
      val plotId3 = insertMonitoringPlot()

      val neverObservedPlotId = insertMonitoringPlot(plantingSubzoneId = subzoneId3)

      // For the first observation, plot1 and plot2 are both assigned and completed.
      clock.instant = Instant.ofEpochSecond(300)
      val observationId1 = insertObservation()
      insertObservationPlot(
          observationId = observationId1, monitoringPlotId = plotId1, claimedBy = user.userId)
      insertObservationPlot(
          observationId = observationId1, monitoringPlotId = plotId2, claimedBy = user.userId)
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
              )))

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
              )))

      // For the second observation, plot2 and plot3 are both assigned, only plot 3 is completed.
      clock.instant = Instant.ofEpochSecond(600)
      val observationId2 = insertObservation()
      insertObservationPlot(
          observationId = observationId2, monitoringPlotId = plotId2, claimedBy = user.userId)
      insertObservationPlot(
          observationId = observationId2, monitoringPlotId = plotId3, claimedBy = user.userId)

      // Add a second plot to subzone 3, to test for if subzone has no completed time yet.
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
      val observation1Subzone1Result =
          results1.plantingZones
              .flatMap { it.plantingSubzones }
              .first { it.plantingSubzoneId == subzoneId1 }
      val observation1Subzone2Result =
          results1.plantingZones
              .flatMap { it.plantingSubzones }
              .first { it.plantingSubzoneId == subzoneId2 }

      val results2 = resultsStore.fetchOneById(observationId2)
      val observation2Subzone2Result =
          results2.plantingZones
              .flatMap { it.plantingSubzones }
              .first { it.plantingSubzoneId == subzoneId2 }
      val observation2Subzone3Result =
          results2.plantingZones
              .flatMap { it.plantingSubzones }
              .first { it.plantingSubzoneId == subzoneId3 }

      assertEquals(
          ObservationPlotStatus.Completed,
          observation1Subzone1Result.monitoringPlots[0].status,
          "Plot status in observation 1 subzone 1")
      assertEquals(
          ObservationPlotStatus.Completed,
          observation1Subzone2Result.monitoringPlots[0].status,
          "Plot status in observation 1 subzone 2")
      assertEquals(
          ObservationPlotStatus.NotObserved,
          observation2Subzone2Result.monitoringPlots[0].status,
          "Plot status in observation 2 subzone 2")
      assertEquals(
          ObservationPlotStatus.Completed,
          observation2Subzone3Result.monitoringPlots
              .first { it.monitoringPlotId == plotId3 }
              .status,
          "Plot status in observation 2 subzone 3")
      assertEquals(
          ObservationPlotStatus.NotObserved,
          observation2Subzone3Result.monitoringPlots
              .first { it.monitoringPlotId == neverObservedPlotId }
              .status,
          "Plot status in observation 2 subzone 3")

      val summary = resultsStore.fetchSummariesForPlantingSite(inserted.plantingSiteId, 1).first()
      assertEquals(
          setOf(observation1Subzone1Result, observation1Subzone2Result, observation2Subzone3Result),
          summary.plantingZones.flatMap { it.plantingSubzones }.toSet(),
          "Planting subzones used for summary did not include the Incomplete subzone result")
    }
  }

  @Nested
  inner class Scenarios {
    private lateinit var plotIds: Map<String, MonitoringPlotId>
    private lateinit var subzoneHistoryIds: Map<PlantingSubzoneId, PlantingSubzoneHistoryId>
    private lateinit var subzoneIds: Map<String, PlantingSubzoneId>
    private lateinit var zoneHistoryIds: Map<PlantingZoneId, PlantingZoneHistoryId>
    private lateinit var zoneIds: Map<String, PlantingZoneId>

    private val zoneNames: Map<PlantingZoneId, String> by lazy {
      zoneIds.entries.associate { it.value to it.key }
    }

    @Test
    fun `site with two observations`() {
      runScenario("/tracking/observation/TwoObservations", numObservations = 2, sizeMeters = 30)
    }

    @Test
    fun `partial observations of disjoint subzone lists`() {
      runScenario("/tracking/observation/DisjointSubzones", numObservations = 3, sizeMeters = 30)
    }

    @Test
    fun `permanent plots being added and removed`() {
      runScenario(
          "/tracking/observation/PermanentPlotChanges", numObservations = 3, sizeMeters = 25)
    }

    @Test
    fun `fetch observation summary`() {
      runSummaryScenario(
          "/tracking/observation/ObservationsSummary",
          numObservations = 3,
          numSpecies = 3,
          sizeMeters = 25)
    }

    private fun runScenario(prefix: String, numObservations: Int, sizeMeters: Int) {
      importFromCsvFiles(prefix, numObservations, sizeMeters)
      val allResults =
          resultsStore.fetchByPlantingSiteId(plantingSiteId).sortedBy { it.observationId }
      assertResults(prefix, allResults)
    }

    private fun runSummaryScenario(
        prefix: String,
        numObservations: Int,
        numSpecies: Int,
        sizeMeters: Int,
    ) {
      importSiteFromCsvFile(prefix, sizeMeters)

      assertEquals(
          emptyList<ObservationRollupResultsModel>(),
          resultsStore.fetchSummariesForPlantingSite(plantingSiteId),
          "No observations made yet.")

      val observationTimes =
          List(numObservations) {
            val time = Instant.ofEpochSecond(it.toLong())
            importObservationsCsv(prefix, numSpecies, it, time)
            time
          }

      val summaries = resultsStore.fetchSummariesForPlantingSite(plantingSiteId)
      assertSummary(prefix, numSpecies, summaries.reversed())

      assertEquals(
          summaries.take(2),
          resultsStore.fetchSummariesForPlantingSite(plantingSiteId, limit = 2),
          "Partial summaries via limit should contain the latest observations.")

      assertEquals(
          summaries.drop(1),
          resultsStore.fetchSummariesForPlantingSite(
              plantingSiteId, maxCompletionTime = observationTimes[1]),
          "Partial summaries via completion time should omit the more recent observations.")

      assertEquals(
          listOf(summaries[1]),
          resultsStore.fetchSummariesForPlantingSite(
              plantingSiteId, maxCompletionTime = observationTimes[1], limit = 1),
          "Partial summaries via limit and completion time.")
    }

    private fun assertSummary(
        prefix: String,
        numSpecies: Int,
        results: List<ObservationRollupResultsModel>
    ) {
      assertAll(
          { assertSiteSummary(prefix, results) },
          { assertSiteSpeciesSummary(prefix, numSpecies, results) },
          { assertZoneSummary(prefix, results) },
          { assertZoneSpeciesSummary(prefix, numSpecies, results) },
          { assertSubzoneSummary(prefix, results) },
          { assertSubzoneSpeciesSummary(prefix, numSpecies, results) },
          { assertPlotSummary(prefix, results) },
          { assertPlotSpeciesSummary(prefix, numSpecies, results) },
      )
    }

    private fun assertResults(prefix: String, allResults: List<ObservationResultsModel>) {
      assertAll(
          { assertSiteResults(prefix, allResults) },
          { assertSiteSpeciesResults(prefix, allResults) },
          { assertZoneResults(prefix, allResults) },
          { assertZoneSpeciesResults(prefix, allResults) },
          { assertSubzoneResults(prefix, allResults) },
          { assertSubzoneSpeciesResults(prefix, allResults) },
          { assertPlotResults(prefix, allResults) },
          { assertPlotSpeciesResults(prefix, allResults) },
      )
    }

    private fun assertSiteResults(prefix: String, allResults: List<ObservationResultsModel>) {
      val actual =
          makeActualCsv(allResults, listOf(emptyList())) { _, results ->
            listOf(
                results.plantingDensity.toStringOrBlank(),
                results.estimatedPlants.toStringOrBlank(),
                results.totalSpecies.toStringOrBlank(),
                results.mortalityRate.toStringOrBlank("%"),
            )
          }

      assertResultsMatchCsv("$prefix/SiteStats.csv", actual)
    }

    private fun assertZoneResults(prefix: String, allResults: List<ObservationResultsModel>) {
      val rowKeys = zoneIds.keys.map { listOf(it) }

      val actual =
          makeActualCsv(allResults, rowKeys) { (zoneName), results ->
            val zone = results.plantingZones.first { it.plantingZoneId == zoneIds[zoneName] }
            listOf(
                zone.totalPlants.toStringOrBlank(),
                zone.plantingDensity.toStringOrBlank(),
                zone.totalSpecies.toStringOrBlank(),
                zone.mortalityRate.toStringOrBlank("%"),
                zone.estimatedPlants.toStringOrBlank(),
            )
          }

      assertResultsMatchCsv("$prefix/ZoneStats.csv", actual)
    }

    private fun assertSubzoneResults(prefix: String, allResults: List<ObservationResultsModel>) {
      val rowKeys = subzoneIds.keys.map { listOf(it) }

      val actual =
          makeActualCsv(allResults, rowKeys) { (subzoneName), results ->
            val subzone =
                results.plantingZones
                    .flatMap { it.plantingSubzones }
                    .firstOrNull { it.plantingSubzoneId == subzoneIds[subzoneName] }
            listOf(
                subzone?.totalPlants.toStringOrBlank(),
                subzone?.plantingDensity.toStringOrBlank(),
                subzone?.totalSpecies.toStringOrBlank(),
                subzone?.mortalityRate.toStringOrBlank("%"),
                subzone?.estimatedPlants.toStringOrBlank(),
            )
          }

      assertResultsMatchCsv("$prefix/SubzoneStats.csv", actual)
    }

    private fun assertPlotResults(prefix: String, allResults: List<ObservationResultsModel>) {
      val rowKeys = plotIds.keys.map { listOf(it) }

      val actual =
          makeActualCsv(allResults, rowKeys) { (plotNumber), results ->
            val plot =
                results.plantingZones
                    .flatMap { zone -> zone.plantingSubzones }
                    .flatMap { subzone -> subzone.monitoringPlots }
                    .firstOrNull { it.monitoringPlotNumber == plotNumber.toLong() }

            listOf(
                plot?.totalPlants.toStringOrBlank(),
                plot?.totalSpecies.toStringOrBlank(),
                plot?.mortalityRate.toStringOrBlank("%"),
                // Live and existing plants columns are in spreadsheet but not included in
                // calculated results; it will be removed by the filter
                // function below.
                plot?.plantingDensity.toStringOrBlank(),
            )
          }

      assertResultsMatchCsv("$prefix/PlotStats.csv", actual) { row ->
        row.filterIndexed { index, _ ->
          val positionInColumnGroup = (index - 1) % 6
          positionInColumnGroup != 3 && positionInColumnGroup != 4
        }
      }
    }

    private fun assertSiteSummary(prefix: String, allResults: List<ObservationRollupResultsModel>) {
      val actual =
          makeActualCsv(allResults, listOf(emptyList())) { _, results ->
            listOf(
                results.plantingDensity.toStringOrBlank(),
                results.plantingDensityStdDev.toStringOrBlank(),
                results.estimatedPlants.toStringOrBlank(),
                results.totalSpecies.toStringOrBlank(),
                results.mortalityRate.toStringOrBlank("%"),
                results.mortalityRateStdDev.toStringOrBlank("%"),
            )
          }

      assertResultsMatchCsv("$prefix/SiteStats.csv", actual)
    }

    private fun assertZoneSummary(prefix: String, allResults: List<ObservationRollupResultsModel>) {
      val rowKeys = zoneIds.keys.map { listOf(it) }

      val actual =
          makeActualCsv(allResults, rowKeys) { (zoneName), results ->
            val zone = results.plantingZones.firstOrNull { it.plantingZoneId == zoneIds[zoneName] }
            listOf(
                zone?.totalPlants.toStringOrBlank(),
                zone?.plantingDensity.toStringOrBlank(),
                zone?.plantingDensityStdDev.toStringOrBlank(),
                zone?.totalSpecies.toStringOrBlank(),
                zone?.mortalityRate.toStringOrBlank("%"),
                zone?.mortalityRateStdDev.toStringOrBlank("%"),
                zone?.estimatedPlants.toStringOrBlank(),
            )
          }

      assertResultsMatchCsv("$prefix/ZoneStats.csv", actual)
    }

    private fun assertSubzoneSummary(
        prefix: String,
        allResults: List<ObservationRollupResultsModel>
    ) {
      val rowKeys = subzoneIds.keys.map { listOf(it) }

      val actual =
          makeActualCsv(allResults, rowKeys) { (subzoneName), results ->
            val subzone =
                results.plantingZones
                    .flatMap { it.plantingSubzones }
                    .firstOrNull { it.plantingSubzoneId == subzoneIds[subzoneName] }
            listOf(
                subzone?.totalPlants.toStringOrBlank(),
                subzone?.plantingDensity.toStringOrBlank(),
                subzone?.plantingDensityStdDev.toStringOrBlank(),
                subzone?.totalSpecies.toStringOrBlank(),
                subzone?.mortalityRate.toStringOrBlank("%"),
                subzone?.mortalityRateStdDev.toStringOrBlank("%"),
                subzone?.estimatedPlants.toStringOrBlank(),
            )
          }

      assertResultsMatchCsv("$prefix/SubzoneStats.csv", actual)
    }

    private fun assertPlotSummary(prefix: String, allResults: List<ObservationRollupResultsModel>) {
      val rowKeys = plotIds.keys.map { listOf(it) }

      val actual =
          makeActualCsv(allResults, rowKeys) { (plotNumber), results ->
            results.plantingZones
                .flatMap { zone -> zone.plantingSubzones }
                .flatMap { subzone -> subzone.monitoringPlots }
                .firstOrNull { it.monitoringPlotNumber == plotNumber.toLong() }
                ?.let { plot ->
                  listOf(
                      plot.totalPlants.toStringOrBlank(),
                      plot.totalSpecies.toStringOrBlank(),
                      plot.mortalityRate.toStringOrBlank("%"),
                      // Live and existing plants columns are in spreadsheet but not included in
                      // calculated
                      // results; it will be removed by the filter function below.
                      plot.plantingDensity.toStringOrBlank(),
                  )
                } ?: listOf("", "", "", "")
          }

      assertResultsMatchCsv("$prefix/PlotStats.csv", actual) { row ->
        row.filterIndexed { index, _ ->
          val positionInColumnGroup = (index - 1) % 8
          positionInColumnGroup != 3 &&
              positionInColumnGroup != 4 &&
              positionInColumnGroup != 5 &&
              positionInColumnGroup != 6
        }
      }
    }

    private fun assertSiteSpeciesResults(
        prefix: String,
        allResults: List<ObservationResultsModel>
    ) {
      val rowKeys = allSpeciesNames.map { listOf(it) }

      val actual =
          makeActualCsv(allResults, rowKeys) { (speciesName), results ->
            results.species
                .filter { it.certainty != RecordedSpeciesCertainty.Unknown }
                .firstOrNull { getSpeciesNameValue(it) == speciesName }
                ?.let { species ->
                  listOf(
                      species.totalPlants.toStringOrBlank(),
                      species.mortalityRate.toStringOrBlank("%"),
                  )
                } ?: listOf("", "")
          }

      assertResultsMatchCsv("$prefix/SiteStatsPerSpecies.csv", actual)
    }

    private fun assertSubzoneSpeciesResults(
        prefix: String,
        allResults: List<ObservationResultsModel>
    ) {
      val rowKeys =
          subzoneIds.keys.flatMap { zoneName ->
            allSpeciesNames.map { speciesName -> listOf(zoneName, speciesName) }
          }

      val actual =
          makeActualCsv(allResults, rowKeys) { (subzoneName, speciesName), results ->
            results.plantingZones
                .flatMap { it.plantingSubzones }
                .firstOrNull { it.plantingSubzoneId == subzoneIds[subzoneName] }
                ?.species
                ?.filter { it.certainty != RecordedSpeciesCertainty.Unknown }
                ?.firstOrNull { getSpeciesNameValue(it) == speciesName }
                ?.let { species ->
                  listOf(
                      species.totalPlants.toStringOrBlank(),
                      species.mortalityRate.toStringOrBlank("%"),
                  )
                } ?: listOf("", "")
          }

      assertResultsMatchCsv("$prefix/SubzoneStatsPerSpecies.csv", actual)
    }

    private fun assertZoneSpeciesResults(
        prefix: String,
        allResults: List<ObservationResultsModel>
    ) {
      val rowKeys =
          zoneIds.keys.flatMap { zoneName ->
            allSpeciesNames.map { speciesName -> listOf(zoneName, speciesName) }
          }

      val actual =
          makeActualCsv(allResults, rowKeys) { (zoneName, speciesName), results ->
            results.plantingZones
                .firstOrNull { zoneNames[it.plantingZoneId] == zoneName }
                ?.species
                ?.filter { it.certainty != RecordedSpeciesCertainty.Unknown }
                ?.firstOrNull { getSpeciesNameValue(it) == speciesName }
                ?.let { species ->
                  listOf(
                      species.totalPlants.toStringOrBlank(),
                      species.mortalityRate.toStringOrBlank("%"),
                  )
                } ?: listOf("", "")
          }

      assertResultsMatchCsv("$prefix/ZoneStatsPerSpecies.csv", actual)
    }

    private fun assertPlotSpeciesResults(
        prefix: String,
        allResults: List<ObservationResultsModel>
    ) {
      val rowKeys =
          plotIds.keys.flatMap { plotName ->
            allSpeciesNames.map { speciesName -> listOf(plotName, speciesName) }
          }

      val actual =
          makeActualCsv(allResults, rowKeys) { (plotNumber, speciesName), results ->
            results.plantingZones
                .flatMap { zone -> zone.plantingSubzones }
                .flatMap { subzone -> subzone.monitoringPlots }
                .firstOrNull { it.monitoringPlotNumber == plotNumber.toLong() }
                ?.species
                ?.filter { it.certainty != RecordedSpeciesCertainty.Unknown }
                ?.firstOrNull { getSpeciesNameValue(it) == speciesName }
                ?.let {
                  listOf(it.totalPlants.toStringOrBlank(), it.mortalityRate.toStringOrBlank("%"))
                } ?: listOf("", "")
          }

      assertResultsMatchCsv("$prefix/PlotStatsPerSpecies.csv", actual)
    }

    private fun assertSiteSpeciesSummary(
        prefix: String,
        numSpecies: Int,
        allResults: List<ObservationRollupResultsModel>
    ) {
      val actual =
          makeActualCsv(allResults, listOf(emptyList())) { _, results ->
            makeCsvColumnsFromSpeciesSummary(numSpecies, results.species)
          }

      assertResultsMatchCsv("$prefix/SiteStatsPerSpecies.csv", actual, skipRows = 3)
    }

    private fun assertZoneSpeciesSummary(
        prefix: String,
        numSpecies: Int,
        allResults: List<ObservationRollupResultsModel>
    ) {
      val rowKeys = zoneIds.keys.map { listOf(it) }

      val actual =
          makeActualCsv(allResults, rowKeys) { (zoneName), results ->
            results.plantingZones
                .firstOrNull { it.plantingZoneId == zoneIds[zoneName] }
                ?.let { makeCsvColumnsFromSpeciesSummary(numSpecies, it.species) }
                ?: List(numSpecies * 2 + 2) { "" }
          }

      assertResultsMatchCsv("$prefix/ZoneStatsPerSpecies.csv", actual, skipRows = 3)
    }

    private fun assertSubzoneSpeciesSummary(
        prefix: String,
        numSpecies: Int,
        allResults: List<ObservationRollupResultsModel>
    ) {
      val rowKeys = subzoneIds.keys.map { listOf(it) }

      val actual =
          makeActualCsv(allResults, rowKeys) { (subzoneName), results ->
            results.plantingZones
                .flatMap { zone -> zone.plantingSubzones }
                .firstOrNull { subzone -> subzone.plantingSubzoneId == subzoneIds[subzoneName] }
                ?.let { makeCsvColumnsFromSpeciesSummary(numSpecies, it.species) }
                ?: List(numSpecies * 2 + 2) { "" }
          }

      assertResultsMatchCsv("$prefix/SubzoneStatsPerSpecies.csv", actual, skipRows = 3)
    }

    private fun assertPlotSpeciesSummary(
        prefix: String,
        numSpecies: Int,
        allResults: List<ObservationRollupResultsModel>
    ) {
      val rowKeys = plotIds.keys.map { listOf(it) }

      val actual =
          makeActualCsv(allResults, rowKeys) { (plotNumber), results ->
            results.plantingZones
                .flatMap { zone -> zone.plantingSubzones }
                .flatMap { subzone -> subzone.monitoringPlots }
                .firstOrNull { it.monitoringPlotNumber == plotNumber.toLong() }
                ?.let { makeCsvColumnsFromSpeciesSummary(numSpecies, it.species) }
                ?: List(numSpecies * 2 + 2) { "" }
          }

      assertResultsMatchCsv("$prefix/PlotStatsPerSpecies.csv", actual, skipRows = 3)
    }

    private fun makeCsvColumnsFromSpeciesSummary(
        numSpecies: Int,
        speciesResults: List<ObservationSpeciesResultsModel>
    ): List<String> {
      val knownSpecies =
          List(numSpecies) { speciesNum ->
                val speciesName = "Species $speciesNum"
                val speciesId = speciesIds[speciesName]

                if (speciesId != null) {
                  speciesResults
                      .firstOrNull { it.speciesId == speciesId }
                      ?.let {
                        listOf(
                            it.totalPlants.toStringOrBlank(),
                            it.mortalityRate.toStringOrBlank("%"),
                        )
                      } ?: listOf("", "")
                } else {
                  listOf("", "")
                }
              }
              .flatten()

      val otherSpecies =
          speciesResults
              .firstOrNull { it.certainty == RecordedSpeciesCertainty.Other }
              ?.let {
                listOf(
                    it.totalPlants.toStringOrBlank(),
                    it.mortalityRate.toStringOrBlank("%"),
                )
              } ?: listOf("", "")

      return knownSpecies + otherSpecies
    }

    private fun importFromCsvFiles(prefix: String, numObservations: Int, sizeMeters: Int) {
      importSiteFromCsvFile(prefix, sizeMeters)
      importPlantsCsv(prefix, numObservations)
    }

    private fun importSiteFromCsvFile(prefix: String, sizeMeters: Int) {
      importZonesCsv(prefix)
      importSubzonesCsv(prefix)
      plotIds = importPlotsCsv(prefix, sizeMeters)
    }

    private fun importZonesCsv(prefix: String) {
      val newZoneIds = mutableMapOf<String, PlantingZoneId>()
      val newZoneHistoryIds = mutableMapOf<PlantingZoneId, PlantingZoneHistoryId>()

      mapCsv("$prefix/Zones.csv", 2) { cols ->
        val zoneName = cols[1]
        val areaHa = BigDecimal(cols[2])

        val zoneId =
            insertPlantingZone(areaHa = areaHa, boundary = squareWithArea(areaHa), name = zoneName)
        newZoneIds[zoneName] = zoneId
        newZoneHistoryIds[zoneId] = inserted.plantingZoneHistoryId
      }

      zoneIds = newZoneIds
      zoneHistoryIds = newZoneHistoryIds
    }

    private fun importSubzonesCsv(prefix: String) {
      val newSubzoneIds = mutableMapOf<String, PlantingSubzoneId>()
      val newSubzoneHistoryIds = mutableMapOf<PlantingSubzoneId, PlantingSubzoneHistoryId>()

      mapCsv("$prefix/Subzones.csv", 2) { cols ->
        val zoneName = cols[0]
        val subzoneName = cols[1]
        val subZoneArea = BigDecimal(cols[2])
        val zoneId = zoneIds[zoneName]!!
        val zoneHistoryId = zoneHistoryIds[zoneId]!!

        // Find the first observation where the subzone is marked as completed planting, if any.
        val plantingCompletedColumn = cols.drop(3).indexOfFirst { it == "Yes" }
        val plantingCompletedTime =
            if (plantingCompletedColumn >= 0) {
              Instant.ofEpochSecond(plantingCompletedColumn.toLong())
            } else {
              null
            }

        val subzoneId =
            insertPlantingSubzone(
                areaHa = subZoneArea,
                boundary = squareWithArea(subZoneArea),
                plantingCompletedTime = plantingCompletedTime,
                fullName = subzoneName,
                insertHistory = false,
                name = subzoneName,
                plantingZoneId = zoneId,
            )
        insertPlantingSubzoneHistory(plantingZoneHistoryId = zoneHistoryId)

        newSubzoneIds[subzoneName] = subzoneId
        newSubzoneHistoryIds[subzoneId] = inserted.plantingSubzoneHistoryId
      }

      subzoneIds = newSubzoneIds
      subzoneHistoryIds = newSubzoneHistoryIds
    }

    private fun importPlotsCsv(prefix: String, sizeMeters: Int): Map<String, MonitoringPlotId> {
      return associateCsv("$prefix/Plots.csv") { cols ->
        val subzoneName = cols[0]
        val plotNumber = cols[1]
        val subzoneId = subzoneIds[subzoneName]!!
        val subzoneHistoryId = subzoneHistoryIds[subzoneId]!!

        val plotId =
            insertMonitoringPlot(
                insertHistory = false,
                plantingSubzoneId = subzoneId,
                plotNumber = plotNumber.toLong(),
                sizeMeters = sizeMeters)
        insertMonitoringPlotHistory(plantingSubzoneHistoryId = subzoneHistoryId)

        if (cols[2] == "Permanent") {
          permanentPlotNumbers.add(plotNumber)
        }

        plotNumber to plotId
      }
    }

    private fun importPlantsCsv(prefix: String, numObservations: Int) {
      repeat(numObservations) { observationNum ->
        clock.instant = Instant.ofEpochSecond(observationNum.toLong())

        val observationId = insertObservation()

        val observedPlotNames = mutableSetOf<String>()

        val plantsRows =
            mapCsv("$prefix/Plants-${observationNum+1}.csv") { cols ->
              val plotName = cols[0]
              val certainty = RecordedSpeciesCertainty.forJsonValue(cols[1])
              val speciesName = cols[2].ifBlank { null }
              val status = RecordedPlantStatus.forJsonValue(cols[3])
              val plotId = plotIds[plotName]!!

              if (speciesName != null) {
                allSpeciesNames.add(speciesName)
              }

              val speciesId =
                  if (speciesName != null && certainty == RecordedSpeciesCertainty.Known) {
                    speciesIds.computeIfAbsent(speciesName) { _ ->
                      insertSpecies(scientificName = speciesName)
                    }
                  } else {
                    null
                  }
              val speciesNameIfOther =
                  if (certainty == RecordedSpeciesCertainty.Other) {
                    speciesName
                  } else {
                    null
                  }

              if (plotName !in observedPlotNames) {
                insertObservationPlot(
                    claimedBy = user.userId,
                    claimedTime = Instant.EPOCH,
                    isPermanent = plotName in permanentPlotNumbers,
                    observationId = observationId,
                    monitoringPlotId = plotId,
                )

                observedPlotNames.add(plotName)
              }

              RecordedPlantsRow(
                  certaintyId = certainty,
                  gpsCoordinates = point(1),
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  speciesId = speciesId,
                  speciesName = speciesNameIfOther,
                  statusId = status,
              )
            }

        with(OBSERVATION_REQUESTED_SUBZONES) {
          dslContext
              .insertInto(OBSERVATION_REQUESTED_SUBZONES, OBSERVATION_ID, PLANTING_SUBZONE_ID)
              .select(
                  DSL.selectDistinct(DSL.value(observationId), MONITORING_PLOTS.PLANTING_SUBZONE_ID)
                      .from(MONITORING_PLOTS)
                      .where(
                          MONITORING_PLOTS.PLOT_NUMBER.`in`(observedPlotNames.map { it.toLong() })))
              .execute()
        }

        // This would normally happen in ObservationService.startObservation after plot selection;
        // do it explicitly since we're specifying our own plots in the test data.
        observationStore.populateCumulativeDead(observationId)

        plantsRows
            .groupBy { it.monitoringPlotId!! }
            .forEach { (plotId, plants) ->
              observationStore.completePlot(
                  observationId, plotId, emptySet(), "Notes", Instant.EPOCH, plants)
            }
      }
    }

    /** Imports plants based on bulk observation numbers. */
    private fun importObservationsCsv(
        prefix: String,
        numSpecies: Int,
        observationNum: Int,
        observationTime: Instant
    ): ObservationId {
      clock.instant = observationTime

      val observationId = insertObservation()
      val observedPlotNames = mutableSetOf<String>()

      val speciesIds =
          List(numSpecies) {
            speciesIds.computeIfAbsent("Species $it") { _ ->
              insertSpecies(scientificName = "Species $it")
            }
          }

      val plantsRows =
          mapCsv("$prefix/Observation-${observationNum+1}.csv", 2) { cols ->
                val plotName = cols[0]
                val plotId = plotIds[plotName]!!

                val knownPlantsRows =
                    List(numSpecies) { speciesNum ->
                          val existingNum = cols[1 + 3 * speciesNum].toIntOrNull()
                          val liveNum = cols[2 + 3 * speciesNum].toIntOrNull()
                          val deadNum = cols[3 + 3 * speciesNum].toIntOrNull()

                          if (existingNum == null || liveNum == null || deadNum == null) {
                            // No observation made for this plot if any grid is empty
                            return@mapCsv emptyList<RecordedPlantsRow>()
                          }

                          val existingRows =
                              List(existingNum) { _ ->
                                RecordedPlantsRow(
                                    certaintyId = RecordedSpeciesCertainty.Known,
                                    gpsCoordinates = point(1),
                                    observationId = observationId,
                                    monitoringPlotId = plotId,
                                    speciesId = speciesIds[speciesNum],
                                    speciesName = null,
                                    statusId = RecordedPlantStatus.Existing,
                                )
                              }

                          val liveRows =
                              List(liveNum) { _ ->
                                RecordedPlantsRow(
                                    certaintyId = RecordedSpeciesCertainty.Known,
                                    gpsCoordinates = point(1),
                                    observationId = observationId,
                                    monitoringPlotId = plotId,
                                    speciesId = speciesIds[speciesNum],
                                    speciesName = null,
                                    statusId = RecordedPlantStatus.Live,
                                )
                              }

                          val deadRows =
                              List(deadNum) {
                                RecordedPlantsRow(
                                    certaintyId = RecordedSpeciesCertainty.Known,
                                    gpsCoordinates = point(1),
                                    observationId = observationId,
                                    monitoringPlotId = plotId,
                                    speciesId = speciesIds[speciesNum],
                                    speciesName = null,
                                    statusId = RecordedPlantStatus.Dead,
                                )
                              }

                          listOf(existingRows, liveRows, deadRows).flatten()
                        }
                        .flatten()

                val unknownLiveNum = cols[1 + 3 * numSpecies].toIntOrNull() ?: 0
                val unknownDeadNum = cols[2 + 3 * numSpecies].toIntOrNull() ?: 0

                val otherLiveNum = cols[3 + 3 * numSpecies].toIntOrNull() ?: 0
                val otherDeadNum = cols[4 + 3 * numSpecies].toIntOrNull() ?: 0

                if (otherLiveNum + otherDeadNum > 0 && !allSpeciesNames.contains("Other")) {
                  allSpeciesNames.add("Other")
                }

                val unknownLivePlantsRows =
                    List(unknownLiveNum) {
                      RecordedPlantsRow(
                          certaintyId = RecordedSpeciesCertainty.Unknown,
                          gpsCoordinates = point(1),
                          observationId = observationId,
                          monitoringPlotId = plotId,
                          speciesId = null,
                          speciesName = null,
                          statusId = RecordedPlantStatus.Live,
                      )
                    }
                val unknownDeadPlantsRows =
                    List(unknownDeadNum) {
                      RecordedPlantsRow(
                          certaintyId = RecordedSpeciesCertainty.Unknown,
                          gpsCoordinates = point(1),
                          observationId = observationId,
                          monitoringPlotId = plotId,
                          speciesId = null,
                          speciesName = null,
                          statusId = RecordedPlantStatus.Dead,
                      )
                    }

                val otherLivePlantsRows =
                    List(otherLiveNum) {
                      RecordedPlantsRow(
                          certaintyId = RecordedSpeciesCertainty.Other,
                          gpsCoordinates = point(1),
                          observationId = observationId,
                          monitoringPlotId = plotId,
                          speciesId = null,
                          speciesName = "Other",
                          statusId = RecordedPlantStatus.Live,
                      )
                    }
                val otherDeadPlantsRows =
                    List(otherDeadNum) {
                      RecordedPlantsRow(
                          certaintyId = RecordedSpeciesCertainty.Other,
                          gpsCoordinates = point(1),
                          observationId = observationId,
                          monitoringPlotId = plotId,
                          speciesId = null,
                          speciesName = "Other",
                          statusId = RecordedPlantStatus.Dead,
                      )
                    }

                if (plotName !in observedPlotNames) {
                  insertObservationPlot(
                      claimedBy = user.userId,
                      claimedTime = Instant.EPOCH,
                      isPermanent = plotName in permanentPlotNumbers,
                      observationId = observationId,
                      monitoringPlotId = plotId,
                  )

                  observedPlotNames.add(plotName)
                }

                listOf(
                        knownPlantsRows,
                        unknownLivePlantsRows,
                        unknownDeadPlantsRows,
                        otherLivePlantsRows,
                        otherDeadPlantsRows,
                    )
                    .flatten()
              }
              .flatten()

      // This would normally happen in ObservationService.startObservation after plot selection;
      // do it explicitly since we're specifying our own plots in the test data.
      observationStore.populateCumulativeDead(observationId)

      plantsRows
          .groupBy { it.monitoringPlotId!! }
          .forEach { (plotId, plants) ->
            observationStore.completePlot(
                observationId, plotId, emptySet(), "Notes", Instant.EPOCH, plants)
          }

      return observationId
    }

    /** Maps each data row of a CSV to a value. */
    private fun <T> mapCsv(path: String, skipRows: Int = 1, func: (Array<String>) -> T): List<T> {
      val stream = javaClass.getResourceAsStream(path) ?: throw NoSuchFileException(path)

      return stream.use { inputStream ->
        CSVReader(InputStreamReader(inputStream)).use { csvReader ->
          // We never care about the header rows.
          csvReader.skip(skipRows)

          csvReader.map(func)
        }
      }
    }

    /** For each data row of a CSV, associates a string identifier with a value. */
    private fun <T> associateCsv(
        path: String,
        skipRows: Int = 1,
        func: (Array<String>) -> Pair<String, T>
    ): Map<String, T> {
      return mapCsv(path, skipRows, func).toMap()
    }

    /**
     * Returns a CSV representation of the results of one or more observations.
     *
     * @param rowKeys The leftmost column(s) of all the rows that could appear in the CSV. The
     *   values in these columns act as unique keys: they identify which specific set of numbers are
     *   included in the rest of the row. For example, for the "per zone per species" CSV, the key
     *   would be a zone name column and a species name column, with one element for each possible
     *   permutation of zone name and species name.
     * @param columnsFromResult Returns a group of columns for the row with a particular key from a
     *   particular observation. If the observation doesn't have any data for the row, this must
     *   return a list of empty strings. If none of the observations have any data for the row,
     *   e.g., it's a "per zone per species" CSV and a particular species wasn't present in a
     *   particular zone, the row is not included in the generated CSV.
     */
    private fun <T> makeActualCsv(
        allResults: List<T>,
        rowKeys: List<List<String>>,
        columnsFromResult: (List<String>, T) -> List<String>
    ): List<List<String>> {
      return rowKeys.mapNotNull { initialRow ->
        val dataColumns = allResults.flatMap { results -> columnsFromResult(initialRow, results) }
        if (dataColumns.any { it.isNotEmpty() }) {
          initialRow + dataColumns
        } else {
          null
        }
      }
    }

    /**
     * Asserts that an expected-output CSV matches the CSV representation of the actual calculation
     * results. The two header rows in the expected-output CSV are discarded.
     */
    private fun assertResultsMatchCsv(
        path: String,
        actual: List<List<String>>,
        skipRows: Int = 2,
        mapCsvRow: (List<String>) -> List<String> = { it },
    ) {
      val actualRendered = actual.map { it.joinToString(",") }.sorted().joinToString("\n")
      val expected =
          mapCsv(path, skipRows) { mapCsvRow(it.toList()).joinToString(",") }
              .sorted()
              .joinToString("\n")

      assertEquals(expected, actualRendered, path)
    }

    private fun getSpeciesNameValue(species: ObservationSpeciesResultsModel): String =
        species.speciesName ?: species.speciesId?.let { speciesNames[it] } ?: ""

    private fun Int?.toStringOrBlank(suffix: String = "") = this?.let { "$it$suffix" } ?: ""

    /**
     * Returns a square whose area in hectares (as returned by [calculateAreaHectares]) is exactly a
     * certain value.
     *
     * This isn't a simple matter of using the square root of the desired area as the length of each
     * edge of the square because the "square" is on a curved surface and is thus distorted by
     * varying amounts depending on how big it is. So we binary-search a range of edge lengths close
     * to the square root of the area, looking for a length whose area in hectares equals the target
     * value.
     */
    private fun squareWithArea(areaHa: Number): MultiPolygon {
      val targetArea = areaHa.toDouble()
      val areaSquareMeters = targetArea * 10000.0
      val initialSize = sqrt(areaSquareMeters)
      var minSize = initialSize * 0.9
      var maxSize = initialSize * 1.1

      while (minSize < maxSize) {
        val candidateSize = (minSize + maxSize) / 2.0
        val candidate = rectangle(candidateSize)
        val candidateArea = candidate.calculateAreaHectares().toDouble()
        if (candidateArea < targetArea) {
          minSize = candidateSize
        } else if (candidateArea > targetArea) {
          maxSize = candidateSize
        } else {
          return candidate
        }
      }

      throw RuntimeException("Unable to generate square with requested area $areaHa")
    }
  }
}
