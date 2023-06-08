package com.terraformation.backend.tracking.db

import com.opencsv.CSVReader
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.tracking.model.ObservationResultsPayload
import io.ktor.utils.io.core.use
import io.mockk.every
import java.io.InputStreamReader
import java.nio.file.NoSuchFileException
import java.time.Instant
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

class ObservationResultsStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private lateinit var observationId: ObservationId
  private lateinit var plantingSiteId: PlantingSiteId
  private lateinit var plotIds: Map<String, MonitoringPlotId>
  private lateinit var speciesIds: Map<String, SpeciesId>
  private lateinit var subzoneIds: Map<String, PlantingSubzoneId>
  private lateinit var zoneIds: Map<String, PlantingZoneId>

  private val clock = TestClock()
  private val observationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        recordedPlantsDao)
  }
  private val resultsStore by lazy { ObservationResultsStore(dslContext) }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    plantingSiteId = insertPlantingSite()
    observationId = insertObservation()

    every { user.canReadObservation(any()) } returns true
    every { user.canReadOrganization(organizationId) } returns true
    every { user.canReadPlantingSite(plantingSiteId) } returns true
    every { user.canUpdateObservation(any()) } returns true
  }

  @Nested
  inner class FetchByOrganizationId {
    @Test
    fun `results are in descending completed time order`() {
      val completedObservationId1 =
          insertObservation(
              ObservationsRow(completedTime = Instant.ofEpochSecond(1)),
              state = ObservationState.Completed)
      val completedObservationId2 =
          insertObservation(
              ObservationsRow(completedTime = Instant.ofEpochSecond(2)),
              state = ObservationState.Completed)
      val inProgressObservationId = insertObservation(state = ObservationState.InProgress)
      val upcomingObservationId = insertObservation(state = ObservationState.Upcoming)

      val results = resultsStore.fetchByOrganizationId(organizationId)

      assertEquals(
          listOf(
              completedObservationId2,
              completedObservationId1,
              upcomingObservationId,
              inProgressObservationId,
              observationId,
          ),
          results.map { it.observationId },
          "Observation IDs")
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
      insertObservation(
          ObservationsRow(completedTime = Instant.ofEpochSecond(1)),
          state = ObservationState.Completed)
      val mostRecentlyCompletedObservationId =
          insertObservation(
              ObservationsRow(completedTime = Instant.ofEpochSecond(3)),
              state = ObservationState.Completed)
      insertObservation(
          ObservationsRow(completedTime = Instant.ofEpochSecond(2)),
          state = ObservationState.Completed)

      val results = resultsStore.fetchByPlantingSiteId(plantingSiteId, limit = 1)

      assertEquals(
          listOf(mostRecentlyCompletedObservationId),
          results.map { it.observationId },
          "Observation IDs")
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
  inner class Scenarios {
    @Test
    fun `site with one zone finished planting and another not finished yet`() {
      runScenario("/tracking/observation/OneZoneFinished")
    }
  }

  private fun runScenario(prefix: String) {
    importFromCsvFiles(prefix)
    val results = resultsStore.fetchOneById(observationId)
    assertResults(prefix, results)
  }

  private fun assertResults(prefix: String, results: ObservationResultsPayload) {
    assertAll(
        { assertSiteResults(prefix, results) },
        { assertZoneResults(prefix, results) },
        { assertZoneSpeciesResults(prefix, results) },
        { assertPlotResults(prefix, results) },
        { assertPlotSpeciesResults(prefix, results) },
    )
  }

  private fun assertSiteResults(prefix: String, results: ObservationResultsPayload) {
    assertResultsMatchCsv("$prefix/SiteStats.csv") { cols ->
      val plantingDensity = cols[0].toIntOrNull()
      val numPlants = cols[1].toIntOrNull()
      val numSpecies = cols[2].toInt()
      val mortalityRate = cols[3].toDouble().roundToInt()

      ({
        assertAll(
            "Site",
            listOf(
                { assertEquals(plantingDensity, results.plantingDensity, "Planting density") },
                { assertEquals(numPlants, results.totalPlants, "Estimated # plants") },
                { assertEquals(numSpecies, results.totalSpecies, "Total species") },
                { assertEquals(mortalityRate, results.mortalityRate, "Mortality rate") },
            ))
      })
    }
  }

  private fun assertZoneResults(prefix: String, results: ObservationResultsPayload) {
    assertResultsMatchCsv("$prefix/ZoneStats.csv") { cols ->
      val zoneName = cols[0]
      val numPlants = cols[1].toInt()
      val plantingDensity = cols[2].toDoubleOrNull()?.roundToInt()
      val numSpecies = cols[3].toInt()
      val mortalityRate = cols[4].toDouble().roundToInt()

      val zoneId = zoneIds[zoneName]!!
      val zoneResults = results.plantingZones.single { it.plantingZoneId == zoneId }

      ({
        assertAll(
            "Zone $zoneName",
            listOf(
                { assertEquals(numPlants, zoneResults.totalPlants, "Total plants") },
                { assertEquals(plantingDensity, zoneResults.plantingDensity, "Planting density") },
                { assertEquals(numSpecies, zoneResults.totalSpecies, "Total species") },
                { assertEquals(mortalityRate, zoneResults.mortalityRate, "Mortality rate") },
            ))
      })
    }
  }

  private fun assertPlotResults(prefix: String, results: ObservationResultsPayload) {
    assertResultsMatchCsv("$prefix/PlotStats.csv") { cols ->
      val plotName = cols[0]
      val numPlants = cols[1].toInt()
      val numSpecies = cols[2].toInt()
      val mortalityRate = cols[3].toDoubleOrNull()?.roundToInt()
      val plantingDensity = cols[5].toDoubleOrNull()?.roundToInt()

      val plotId = plotIds[plotName]
      val plotResults =
          results.plantingZones
              .flatMap { zone -> zone.plantingSubzones.flatMap { it.monitoringPlots } }
              .single { it.monitoringPlotId == plotId }

      ({
        assertAll(
            "Plot $plotName",
            listOf(
                { assertEquals(numPlants, plotResults.totalPlants, "Total plants") },
                { assertEquals(numSpecies, plotResults.totalSpecies, "Total species") },
                { assertEquals(mortalityRate, plotResults.mortalityRate, "Mortality rate") },
                { assertEquals(plantingDensity, plotResults.plantingDensity, "Planting density") },
            ))
      })
    }
  }

  private fun assertZoneSpeciesResults(prefix: String, results: ObservationResultsPayload) {
    assertResultsMatchCsv("$prefix/ZoneStatsPerSpecies.csv") { cols ->
      val zoneName = cols[0]
      val speciesName = cols[1]
      val numPlants = cols[2].toInt()
      val mortalityRate = cols[3].toDoubleOrNull()?.roundToInt()

      val zoneId = zoneIds[zoneName]!!
      val speciesId = speciesIds[speciesName]
      val zoneResults = results.plantingZones.single { it.plantingZoneId == zoneId }
      val speciesResults =
          zoneResults.species.single { species ->
            (species.speciesId == null && species.speciesName == speciesName) ||
                (species.speciesId != null && species.speciesId == speciesId)
          }

      ({
        assertAll(
            "Zone $zoneName species $speciesName",
            listOf(
                { assertEquals(numPlants, speciesResults.totalPlants, "Total plants") },
                { assertEquals(mortalityRate, speciesResults.mortalityRate, "Mortality rate") },
            ))
      })
    }
  }

  private fun assertPlotSpeciesResults(prefix: String, results: ObservationResultsPayload) {
    assertResultsMatchCsv("$prefix/PlotStatsPerSpecies.csv") { cols ->
      val plotName = cols[0]
      val speciesName = cols[1]
      val numPlants = cols[2].toInt()
      val mortalityRate = cols[3].toDoubleOrNull()?.roundToInt()

      val plotId = plotIds[plotName]!!
      val speciesId = speciesIds[speciesName]
      val plotResults =
          results.plantingZones
              .flatMap { zone -> zone.plantingSubzones.flatMap { it.monitoringPlots } }
              .single { it.monitoringPlotId == plotId }
      val speciesResults =
          plotResults.species.single { species ->
            (species.speciesId == null && species.speciesName == speciesName) ||
                (species.speciesId != null && species.speciesId == speciesId)
          }

      ({
        assertAll(
            "Plot $plotName species $speciesName",
            listOf(
                { assertEquals(numPlants, speciesResults.totalPlants, "Total plants") },
                { assertEquals(mortalityRate, speciesResults.mortalityRate, "Mortality rate") },
            ))
      })
    }
  }

  private fun importFromCsvFiles(prefix: String) {
    zoneIds = importZonesCsv(prefix)
    subzoneIds = importSubzonesCsv(prefix)
    plotIds = importPlotsCsv(prefix)
    speciesIds = importPlantsCsv(prefix)
  }

  private fun importZonesCsv(prefix: String): Map<String, PlantingZoneId> {
    return associateCsv("$prefix/Zones.csv") { cols ->
      val zoneName = cols[1]

      zoneName to insertPlantingZone(name = zoneName)
    }
  }

  private fun importSubzonesCsv(prefix: String): Map<String, PlantingSubzoneId> {
    return associateCsv("$prefix/Subzones.csv") { cols ->
      val zoneName = cols[0]
      val subzoneName = cols[1]
      val finishedPlanting = cols[2] == "Yes"
      val zoneId = zoneIds[zoneName]!!

      val finishedPlantingTime =
          if (finishedPlanting) Instant.EPOCH else clock.instant.plusSeconds(1)

      subzoneName to
          insertPlantingSubzone(
              finishedPlantingTime = finishedPlantingTime,
              name = subzoneName,
              plantingZoneId = zoneId)
    }
  }

  private fun importPlotsCsv(prefix: String): Map<String, MonitoringPlotId> {
    return associateCsv("$prefix/Plots.csv") { cols ->
      val subzoneName = cols[0]
      val plotName = cols[1]
      val subzoneId = subzoneIds[subzoneName]!!

      val plotId = insertMonitoringPlot(name = plotName, plantingSubzoneId = subzoneId)

      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          isPermanent = cols[2] == "Permanent")

      plotName to plotId
    }
  }

  private fun importPlantsCsv(prefix: String): Map<String, SpeciesId> {
    val knownSpeciesIds = mutableMapOf<String, SpeciesId>()

    val plantsRows =
        mapCsv("$prefix/Plants.csv") { cols ->
          val plotName = cols[0]
          val certainty = RecordedSpeciesCertainty.forJsonValue(cols[1])
          val speciesName = cols[2].ifBlank { null }
          val status = RecordedPlantStatus.forJsonValue(cols[3])
          val plotId = plotIds[plotName]!!

          val speciesId =
              if (speciesName != null && certainty == RecordedSpeciesCertainty.Known) {
                knownSpeciesIds.computeIfAbsent(speciesName) { _ ->
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

          RecordedPlantsRow(
              certaintyId = certainty,
              gpsCoordinates = point(1.0),
              observationId = observationId,
              monitoringPlotId = plotId,
              speciesId = speciesId,
              speciesName = speciesNameIfOther,
              statusId = status,
          )
        }

    plantsRows
        .groupBy { it.monitoringPlotId!! }
        .forEach { (plotId, plants) ->
          observationStore.completePlot(
              observationId, plotId, emptySet(), "Notes", Instant.EPOCH, plants)
        }

    return knownSpeciesIds
  }

  /** Maps each data row of a CSV to a value. */
  private fun <T> mapCsv(path: String, func: (Array<String>) -> T): List<T> {
    val stream = javaClass.getResourceAsStream(path) ?: throw NoSuchFileException(path)

    return stream.use { inputStream ->
      CSVReader(InputStreamReader(inputStream)).use { csvReader ->
        // We never care about the header row.
        csvReader.skip(1)

        csvReader.map(func)
      }
    }
  }

  /** For each data row of a CSV, associates a string identifier with a value. */
  private fun <T> associateCsv(
      path: String,
      func: (Array<String>) -> Pair<String, T>
  ): Map<String, T> {
    return mapCsv(path, func).toMap()
  }

  private fun assertResultsMatchCsv(path: String, func: (Array<String>) -> (() -> Unit)) {
    val assertions = mapCsv(path, func)
    assertAll(assertions)
  }
}
