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
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsPayload
import io.ktor.utils.io.core.use
import io.mockk.every
import java.io.InputStreamReader
import java.nio.file.NoSuchFileException
import java.time.Instant
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

  private val speciesNames: Map<SpeciesId, String> by lazy {
    speciesIds.entries.associate { it.value to it.key }
  }
  private val zoneNames: Map<PlantingZoneId, String> by lazy {
    zoneIds.entries.associate { it.value to it.key }
  }

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
    fun `site with one zone completed planting and another not completed yet`() {
      runScenario("/tracking/observation/OneZoneCompleted")
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
        { assertSiteSpeciesResults(prefix, results) },
        { assertZoneResults(prefix, results) },
        { assertZoneSpeciesResults(prefix, results) },
        { assertPlotResults(prefix, results) },
        { assertPlotSpeciesResults(prefix, results) },
    )
  }

  private fun assertSiteResults(prefix: String, results: ObservationResultsPayload) {
    val actual =
        listOf(
            listOf(
                results.plantingDensity.toStringOrBlank(),
                results.totalPlants.toStringOrBlank(),
                results.totalSpecies.toStringOrBlank(),
                results.mortalityRate.toStringOrBlank("%"),
            ))
    assertResultsMatchCsv("$prefix/SiteStats.csv", actual)
  }

  private fun assertZoneResults(prefix: String, results: ObservationResultsPayload) {
    val actual =
        results.plantingZones.map { zone ->
          listOf(
              zoneNames.getValue(zone.plantingZoneId),
              zone.totalPlants.toStringOrBlank(),
              zone.plantingDensity.toStringOrBlank(),
              zone.totalSpecies.toStringOrBlank(),
              zone.mortalityRate.toStringOrBlank("%"),
          )
        }
    assertResultsMatchCsv("$prefix/ZoneStats.csv", actual)
  }

  private fun assertPlotResults(prefix: String, results: ObservationResultsPayload) {
    val actual =
        results.plantingZones.flatMap { zone ->
          zone.plantingSubzones.flatMap { subzone ->
            subzone.monitoringPlots.map { plot ->
              listOf(
                  plot.monitoringPlotName,
                  plot.totalPlants.toStringOrBlank(),
                  plot.totalSpecies.toStringOrBlank(),
                  plot.mortalityRate.toStringOrBlank("%"),
                  // Live plants column is in spreadsheet but not included in calculated results;
                  // it will be removed by the filter function below.
                  plot.plantingDensity.toStringOrBlank(),
              )
            }
          }
        }

    assertResultsMatchCsv("$prefix/PlotStats.csv", actual) { row ->
      row.filterIndexed { index, _ -> index != 4 }
    }
  }

  private fun assertSiteSpeciesResults(prefix: String, results: ObservationResultsPayload) {
    val actual =
        results.species.map { species ->
          listOf(
              getSpeciesNameValue(species),
              species.totalPlants.toStringOrBlank(),
              species.mortalityRate.toStringOrBlank("%"),
          )
        }

    assertResultsMatchCsv("$prefix/SiteStatsPerSpecies.csv", actual)
  }

  private fun assertZoneSpeciesResults(prefix: String, results: ObservationResultsPayload) {
    val actual =
        results.plantingZones.flatMap { zone ->
          zone.species.map { species ->
            listOf(
                zoneNames.getValue(zone.plantingZoneId),
                getSpeciesNameValue(species),
                species.totalPlants.toStringOrBlank(),
                species.mortalityRate.toStringOrBlank("%"),
            )
          }
        }
    assertResultsMatchCsv("$prefix/ZoneStatsPerSpecies.csv", actual)
  }

  private fun assertPlotSpeciesResults(prefix: String, results: ObservationResultsPayload) {
    val actual =
        results.plantingZones.flatMap { zone ->
          zone.plantingSubzones.flatMap { subzone ->
            subzone.monitoringPlots.flatMap { plot ->
              plot.species.map { species ->
                listOf(
                    plot.monitoringPlotName,
                    getSpeciesNameValue(species),
                    species.totalPlants.toStringOrBlank(),
                    species.mortalityRate.toStringOrBlank("%"),
                )
              }
            }
          }
        }
    assertResultsMatchCsv("$prefix/PlotStatsPerSpecies.csv", actual)
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
      val plantingCompleted = cols[2] == "Yes"
      val zoneId = zoneIds[zoneName]!!

      val plantingCompletedTime =
          if (plantingCompleted) Instant.EPOCH else clock.instant.plusSeconds(1)

      subzoneName to
          insertPlantingSubzone(
              plantingCompletedTime = plantingCompletedTime,
              fullName = subzoneName,
              name = subzoneName,
              plantingZoneId = zoneId)
    }
  }

  private fun importPlotsCsv(prefix: String): Map<String, MonitoringPlotId> {
    return associateCsv("$prefix/Plots.csv") { cols ->
      val subzoneName = cols[0]
      val plotName = cols[1]
      val subzoneId = subzoneIds[subzoneName]!!

      val plotId =
          insertMonitoringPlot(fullName = plotName, name = plotName, plantingSubzoneId = subzoneId)

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

  private fun assertResultsMatchCsv(
      path: String,
      actual: List<List<String>>,
      mapCsvRow: (List<String>) -> List<String> = { it }
  ) {
    val actualRendered = actual.map { it.joinToString(",") }.sorted().joinToString("\n")
    val expected =
        mapCsv(path) { mapCsvRow(it.toList()).joinToString(",") }.sorted().joinToString("\n")

    assertEquals(expected, actualRendered, path)
  }

  private fun getSpeciesNameValue(species: ObservationSpeciesResultsPayload): String =
      species.speciesName ?: species.speciesId?.let { speciesNames[it] } ?: ""

  private fun Int?.toStringOrBlank(suffix: String = "") = this?.let { "$it$suffix" } ?: ""
}
