package com.terraformation.backend.tracking.db

import com.opencsv.CSVReader
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
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
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsModel
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

  private lateinit var plantingSiteId: PlantingSiteId
  private lateinit var plotIds: Map<String, MonitoringPlotId>
  private lateinit var subzoneIds: Map<String, PlantingSubzoneId>
  private lateinit var zoneIds: Map<String, PlantingZoneId>

  private val allSpeciesNames = mutableSetOf<String>()
  private val permanentPlotNames = mutableSetOf<String>()
  private val speciesIds = mutableMapOf<String, SpeciesId>()

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
      runScenario("/tracking/observation/OneZoneCompleted", 1)
    }

    private fun runScenario(prefix: String, numObservations: Int) {
      importFromCsvFiles(prefix, numObservations)
      val allResults =
          resultsStore.fetchByPlantingSiteId(plantingSiteId).sortedBy { it.observationId.value }
      assertResults(prefix, allResults)
    }

    private fun assertResults(prefix: String, allResults: List<ObservationResultsModel>) {
      assertAll(
          { assertSiteResults(prefix, allResults) },
          { assertSiteSpeciesResults(prefix, allResults) },
          { assertZoneResults(prefix, allResults) },
          { assertZoneSpeciesResults(prefix, allResults) },
          { assertPlotResults(prefix, allResults) },
          { assertPlotSpeciesResults(prefix, allResults) },
      )
    }

    private fun assertSiteResults(prefix: String, allResults: List<ObservationResultsModel>) {
      val actual =
          makeActualCsv(allResults, listOf(emptyList())) { _, results ->
            listOf(
                results.plantingDensity.toStringOrBlank(),
                results.totalPlants.toStringOrBlank(),
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
            )
          }

      assertResultsMatchCsv("$prefix/ZoneStats.csv", actual)
    }

    private fun assertPlotResults(prefix: String, allResults: List<ObservationResultsModel>) {
      val rowKeys = plotIds.keys.map { listOf(it) }

      val actual =
          makeActualCsv(allResults, rowKeys) { (plotName), results ->
            results.plantingZones
                .flatMap { zone -> zone.plantingSubzones }
                .flatMap { subzone -> subzone.monitoringPlots }
                .firstOrNull { it.monitoringPlotName == plotName }
                ?.let { plot ->
                  listOf(
                      plot.totalPlants.toStringOrBlank(),
                      plot.totalSpecies.toStringOrBlank(),
                      plot.mortalityRate.toStringOrBlank("%"),
                      // Live plants column is in spreadsheet but not included in calculated
                      // results; it will be removed by the filter function below.
                      plot.plantingDensity.toStringOrBlank(),
                  )
                }
                ?: listOf("", "", "", "")
          }

      assertResultsMatchCsv("$prefix/PlotStats.csv", actual) { row ->
        row.filterIndexed { index, _ -> ((index - 1) % 5) != 3 }
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
                .filter { it.certainty != RecordedSpeciesCertainty.Unknown && it.totalPlants > 0 }
                .firstOrNull { getSpeciesNameValue(it) == speciesName }
                ?.let { species ->
                  listOf(
                      species.totalPlants.toStringOrBlank(),
                      species.mortalityRate.toStringOrBlank("%"),
                  )
                }
                ?: listOf("", "")
          }

      assertResultsMatchCsv("$prefix/SiteStatsPerSpecies.csv", actual)
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
                ?.filter { it.certainty != RecordedSpeciesCertainty.Unknown && it.totalPlants > 0 }
                ?.firstOrNull { getSpeciesNameValue(it) == speciesName }
                ?.let { species ->
                  listOf(
                      species.totalPlants.toStringOrBlank(),
                      species.mortalityRate.toStringOrBlank("%"),
                  )
                }
                ?: listOf("", "")
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
          makeActualCsv(allResults, rowKeys) { (plotName, speciesName), results ->
            results.plantingZones
                .flatMap { zone -> zone.plantingSubzones }
                .flatMap { subzone -> subzone.monitoringPlots }
                .first { it.monitoringPlotName == plotName }
                .species
                .filter { it.certainty != RecordedSpeciesCertainty.Unknown && it.totalPlants > 0 }
                .firstOrNull { getSpeciesNameValue(it) == speciesName }
                ?.let {
                  listOf(it.totalPlants.toStringOrBlank(), it.mortalityRate.toStringOrBlank("%"))
                }
                ?: listOf("", "")
          }

      assertResultsMatchCsv("$prefix/PlotStatsPerSpecies.csv", actual)
    }

    private fun importFromCsvFiles(prefix: String, numObservations: Int) {
      zoneIds = importZonesCsv(prefix)
      subzoneIds = importSubzonesCsv(prefix)
      plotIds = importPlotsCsv(prefix)
      importPlantsCsv(prefix, numObservations)
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
            insertMonitoringPlot(
                fullName = plotName, name = plotName, plantingSubzoneId = subzoneId)

        if (cols[2] == "Permanent") {
          permanentPlotNames.add(plotName)
        }

        plotName to plotId
      }
    }

    private fun importPlantsCsv(prefix: String, numObservations: Int) {
      repeat(numObservations) { observationNum ->
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
                    isPermanent = plotName in permanentPlotNames,
                    observationId = observationId,
                    monitoringPlotId = plotId,
                )

                observedPlotNames.add(plotName)
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
      }
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
        func: (Array<String>) -> Pair<String, T>
    ): Map<String, T> {
      return mapCsv(path, 1, func).toMap()
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
    private fun makeActualCsv(
        allResults: List<ObservationResultsModel>,
        rowKeys: List<List<String>>,
        columnsFromResult: (List<String>, ObservationResultsModel) -> List<String>
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
        mapCsvRow: (List<String>) -> List<String> = { it }
    ) {
      val actualRendered = actual.map { it.joinToString(",") }.sorted().joinToString("\n")
      val expected =
          mapCsv(path, 2) { mapCsvRow(it.toList()).joinToString(",") }.sorted().joinToString("\n")

      assertEquals(expected, actualRendered, path)
    }

    private fun getSpeciesNameValue(species: ObservationSpeciesResultsModel): String =
        species.speciesName ?: species.speciesId?.let { speciesNames[it] } ?: ""

    private fun Int?.toStringOrBlank(suffix: String = "") = this?.let { "$it$suffix" } ?: ""
  }
}
