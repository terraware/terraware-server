package com.terraformation.backend.tracking.db

import com.opencsv.CSVReader
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneHistoryId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneHistoryId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_REQUESTED_SUBZONES
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.toBigDecimal
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationRollupResultsModel
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsModel
import com.terraformation.backend.util.calculateAreaHectares
import io.mockk.every
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.file.NoSuchFileException
import java.time.Instant
import kotlin.math.sqrt
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertAll
import org.locationtech.jts.geom.MultiPolygon

abstract class ObservationScenarioTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  protected lateinit var organizationId: OrganizationId
  protected lateinit var plantingSiteId: PlantingSiteId
  protected val allSpeciesNames = mutableSetOf<String>()
  protected val permanentPlotNumbers = mutableSetOf<String>()
  protected val permanentPlotIds = mutableSetOf<MonitoringPlotId>()
  protected var speciesIds = mutableMapOf<String, SpeciesId>()

  protected val speciesNames: Map<SpeciesId, String> by lazy {
    speciesIds.entries.associate { it.value to it.key }
  }

  protected val clock = TestClock()
  protected val observationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        observationRequestedSubzonesDao,
        ParentStore(dslContext),
    )
  }
  protected val resultsStore by lazy { ObservationResultsStore(dslContext) }

  protected lateinit var plotIds: Map<String, MonitoringPlotId>
  protected lateinit var subzoneHistoryIds: Map<PlantingSubzoneId, PlantingSubzoneHistoryId>
  protected lateinit var subzoneIds: Map<String, PlantingSubzoneId>
  protected lateinit var zoneHistoryIds: Map<PlantingZoneId, PlantingZoneHistoryId>
  protected lateinit var zoneIds: Map<String, PlantingZoneId>

  protected val zoneNames: Map<PlantingZoneId, String> by lazy {
    zoneIds.entries.associate { it.value to it.key }
  }

  @BeforeEach
  fun initialSetUp() {
    organizationId = insertOrganization()
    plantingSiteId = insertPlantingSite(x = 0, areaHa = BigDecimal(2500))

    every { user.canReadObservation(any()) } returns true
    every { user.canUpdateObservation(any()) } returns true
    every { user.canReadPlantingSite(plantingSiteId) } returns true
  }

  protected fun runScenario(
      prefix: String,
      numObservations: Int,
      sizeMeters: Int,
      plantingSiteId: PlantingSiteId,
  ) {
    importFromCsvFiles(prefix, numObservations, sizeMeters)
    val allResults =
        resultsStore.fetchByPlantingSiteId(plantingSiteId, ObservationResultsDepth.Plant).sortedBy {
          it.observationId
        }
    assertResults(prefix, allResults)
  }

  protected fun runSummaryScenario(
      prefix: String,
      numObservations: Int,
      numSpecies: Int,
      sizeMeters: Int,
      plantingSiteId: PlantingSiteId,
  ) {
    importSiteFromCsvFile(prefix, sizeMeters)

    assertEquals(
        emptyList<ObservationRollupResultsModel>(),
        resultsStore.fetchSummariesForPlantingSite(plantingSiteId),
        "No observations made yet.",
    )

    val hasT0DensitiesSpecified = importT0DensitiesCsv(prefix)

    val observationTimes =
        List(numObservations) {
          val time = Instant.ofEpochSecond(it.toLong())
          importObservationsCsv(prefix, numSpecies, it, time, !hasT0DensitiesSpecified)
          time
        }

    val summaries = resultsStore.fetchSummariesForPlantingSite(plantingSiteId)
    assertSummary(prefix, numSpecies, summaries.reversed())

    assertEquals(
        summaries.take(2),
        resultsStore.fetchSummariesForPlantingSite(plantingSiteId, limit = 2),
        "Partial summaries via limit should contain the latest observations.",
    )

    if (observationTimes.size > 1) {
      assertEquals(
          summaries.drop(1),
          resultsStore.fetchSummariesForPlantingSite(
              plantingSiteId,
              maxCompletionTime = observationTimes[1],
          ),
          "Partial summaries via completion time should omit the more recent observations.",
      )

      assertEquals(
          listOf(summaries[1]),
          resultsStore.fetchSummariesForPlantingSite(
              plantingSiteId,
              maxCompletionTime = observationTimes[1],
              limit = 1,
          ),
          "Partial summaries via limit and completion time.",
      )
    }
  }

  protected fun assertSummary(
      prefix: String,
      numSpecies: Int,
      results: List<ObservationRollupResultsModel>,
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

  protected fun assertResults(prefix: String, allResults: List<ObservationResultsModel>) {
    assertAll(
        { assertSiteResults(prefix, allResults) },
        { assertSiteSpeciesResults(prefix, allResults) },
        { assertZoneResults(prefix, allResults) },
        { assertZoneSpeciesResults(prefix, allResults) },
        { assertSubzoneResults(prefix, allResults) },
        { assertSubzoneSpeciesResults(prefix, allResults) },
        { assertPlotResults(prefix, allResults) },
        { assertPlotSpeciesResults(prefix, allResults) },
        { assertAllPlantsResults(prefix, allResults) },
    )
  }

  protected fun assertSiteResults(prefix: String, allResults: List<ObservationResultsModel>) {
    val actual =
        makeActualCsv(
            allResults,
            listOf(emptyList()),
            { _, results ->
              listOf(
                  results.plantingDensity.toStringOrBlank(),
                  results.estimatedPlants.toStringOrBlank(),
                  results.totalSpecies.toStringOrBlank(),
                  results.mortalityRate.toStringOrBlank("%"),
                  results.survivalRate.toStringOrBlank("%"),
              )
            },
        )

    assertResultsMatchCsv("$prefix/SiteStats.csv", actual)
  }

  protected fun assertZoneResults(prefix: String, allResults: List<ObservationResultsModel>) {
    val rowKeys = zoneIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (zoneName), results ->
              val zone = results.plantingZones.first { it.plantingZoneId == zoneIds[zoneName] }
              listOf(
                  zone.totalPlants.toStringOrBlank(),
                  zone.plantingDensity.toStringOrBlank(),
                  zone.totalSpecies.toStringOrBlank(),
                  zone.mortalityRate.toStringOrBlank("%"),
                  zone.survivalRate.toStringOrBlank("%"),
                  zone.estimatedPlants.toStringOrBlank(),
              )
            },
        )

    assertResultsMatchCsv("$prefix/ZoneStats.csv", actual)
  }

  protected fun assertSubzoneResults(prefix: String, allResults: List<ObservationResultsModel>) {
    val rowKeys = subzoneIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (subzoneName), results ->
              val subzone =
                  results.plantingZones
                      .flatMap { it.plantingSubzones }
                      .firstOrNull { it.plantingSubzoneId == subzoneIds[subzoneName] }
              listOf(
                  subzone?.totalPlants.toStringOrBlank(),
                  subzone?.plantingDensity.toStringOrBlank(),
                  subzone?.totalSpecies.toStringOrBlank(),
                  subzone?.mortalityRate.toStringOrBlank("%"),
                  subzone?.survivalRate.toStringOrBlank("%"),
                  subzone?.estimatedPlants.toStringOrBlank(),
              )
            },
        )

    assertResultsMatchCsv("$prefix/SubzoneStats.csv", actual)
  }

  protected fun assertPlantsResults(filePath: String, results: ObservationResultsModel) {
    val plots =
        results.plantingZones
            .flatMap { zone -> zone.plantingSubzones }
            .flatMap { subzone -> subzone.monitoringPlots }

    val rowKeys =
        plots.flatMap { plot ->
          plot.plants?.map { listOf(plot.monitoringPlotNumber.toString(), it.id.toString()) }
              ?: emptyList()
        }

    val actual =
        makeActualCsv(
            listOf(results),
            rowKeys,
            { (plotNumber, plantId), results ->
              val plot =
                  results.plantingZones
                      .flatMap { zone -> zone.plantingSubzones }
                      .flatMap { subzone -> subzone.monitoringPlots }
                      .firstOrNull { it.monitoringPlotNumber == plotNumber.toLong() }
              val plant = plot?.plants?.firstOrNull { it.id.toString() == plantId }

              val speciesName =
                  plant?.speciesName ?: plant?.speciesId?.let { speciesNames[it] } ?: ""

              listOf(
                  plant?.certainty?.jsonValue ?: "",
                  speciesName,
                  plant?.status?.jsonValue ?: "",
                  plant?.gpsCoordinates?.x?.toString() ?: "",
                  plant?.gpsCoordinates?.y?.toString() ?: "",
              )
            },
            { (plotNumber, _) -> listOf(plotNumber) },
        )

    assertResultsMatchCsv(filePath, actual, 1)
  }

  protected fun assertAllPlantsResults(prefix: String, allResults: List<ObservationResultsModel>) {
    allResults.forEachIndexed { index, results ->
      assertPlantsResults("$prefix/Plants-${index + 1}.csv", results)
    }
  }

  protected fun assertPlotResults(prefix: String, allResults: List<ObservationResultsModel>) {
    val rowKeys = plotIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (plotNumber), results ->
              val plot =
                  results.plantingZones
                      .flatMap { zone -> zone.plantingSubzones }
                      .flatMap { subzone -> subzone.monitoringPlots }
                      .firstOrNull { it.monitoringPlotNumber == plotNumber.toLong() }

              listOf(
                  plot?.totalPlants.toStringOrBlank(),
                  plot?.totalSpecies.toStringOrBlank(),
                  plot?.mortalityRate.toStringOrBlank("%"),
                  plot?.survivalRate.toStringOrBlank("%"),
                  // Live and existing plants columns are in spreadsheet but not included in
                  // calculated results; it will be removed by the filter
                  // function below.
                  plot?.plantingDensity.toStringOrBlank(),
              )
            },
        )

    assertResultsMatchCsv("$prefix/PlotStats.csv", actual) { row ->
      row.filterIndexed { index, _ ->
        val positionInColumnGroup = (index - 1) % 7
        positionInColumnGroup !in 4..5
      }
    }
  }

  protected fun assertSiteSummary(prefix: String, allResults: List<ObservationRollupResultsModel>) {
    val actual =
        makeActualCsv(
            allResults,
            listOf(emptyList()),
            { _, results ->
              listOf(
                  results.plantingDensity.toStringOrBlank(),
                  results.plantingDensityStdDev.toStringOrBlank(),
                  results.estimatedPlants.toStringOrBlank(),
                  results.totalSpecies.toStringOrBlank(),
                  results.mortalityRate.toStringOrBlank("%"),
                  results.mortalityRateStdDev.toStringOrBlank("%"),
                  results.survivalRate.toStringOrBlank("%"),
                  results.survivalRateStdDev.toStringOrBlank("%"),
              )
            },
        )

    assertResultsMatchCsv("$prefix/SiteStatsSummary.csv", actual)
  }

  protected fun assertZoneSummary(prefix: String, allResults: List<ObservationRollupResultsModel>) {
    val rowKeys = zoneIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (zoneName), results ->
              val zone =
                  results.plantingZones.firstOrNull { it.plantingZoneId == zoneIds[zoneName] }
              listOf(
                  zone?.totalPlants.toStringOrBlank(),
                  zone?.plantingDensity.toStringOrBlank(),
                  zone?.plantingDensityStdDev.toStringOrBlank(),
                  zone?.totalSpecies.toStringOrBlank(),
                  zone?.mortalityRate.toStringOrBlank("%"),
                  zone?.mortalityRateStdDev.toStringOrBlank("%"),
                  zone?.survivalRate.toStringOrBlank("%"),
                  zone?.survivalRateStdDev.toStringOrBlank("%"),
                  zone?.estimatedPlants.toStringOrBlank(),
              )
            },
        )

    assertResultsMatchCsv("$prefix/ZoneStatsSummary.csv", actual)
  }

  protected fun assertSubzoneSummary(
      prefix: String,
      allResults: List<ObservationRollupResultsModel>,
  ) {
    val rowKeys = subzoneIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (subzoneName), results ->
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
                  subzone?.survivalRate.toStringOrBlank("%"),
                  subzone?.survivalRateStdDev.toStringOrBlank("%"),
                  subzone?.estimatedPlants.toStringOrBlank(),
              )
            },
        )

    assertResultsMatchCsv("$prefix/SubzoneStatsSummary.csv", actual)
  }

  protected fun assertPlotSummary(prefix: String, allResults: List<ObservationRollupResultsModel>) {
    val rowKeys = plotIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (plotNumber), results ->
              results.plantingZones
                  .flatMap { zone -> zone.plantingSubzones }
                  .flatMap { subzone -> subzone.monitoringPlots }
                  .firstOrNull { it.monitoringPlotNumber == plotNumber.toLong() }
                  ?.let { plot ->
                    listOf(
                        plot.totalPlants.toStringOrBlank(),
                        plot.totalSpecies.toStringOrBlank(),
                        plot.mortalityRate.toStringOrBlank("%"),
                        plot.survivalRate.toStringOrBlank("%"),
                        // Live and existing plants columns are in spreadsheet but not included in
                        // calculated
                        // results; it will be removed by the filter function below.
                        plot.plantingDensity.toStringOrBlank(),
                    )
                  } ?: listOf("", "", "", "", "")
            },
        )

    assertResultsMatchCsv("$prefix/PlotStatsSummary.csv", actual) { row ->
      row.filterIndexed { index, _ ->
        val positionInColumnGroup = (index - 1) % 10
        positionInColumnGroup !in 4..8
      }
    }
  }

  protected fun assertSiteSpeciesResults(
      prefix: String,
      allResults: List<ObservationResultsModel>,
  ) {
    val rowKeys = allSpeciesNames.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (speciesName), results ->
              results.species
                  .filter { it.certainty != RecordedSpeciesCertainty.Unknown }
                  .firstOrNull { getSpeciesNameValue(it) == speciesName }
                  ?.let { species ->
                    listOf(
                        species.totalPlants.toStringOrBlank(),
                        species.mortalityRate.toStringOrBlank("%"),
                        species.survivalRate.toStringOrBlank("%"),
                    )
                  } ?: listOf("", "", "")
            },
        )

    assertResultsMatchCsv("$prefix/SiteStatsPerSpecies.csv", actual)
  }

  protected fun assertSubzoneSpeciesResults(
      prefix: String,
      allResults: List<ObservationResultsModel>,
  ) {
    val rowKeys =
        subzoneIds.keys.flatMap { zoneName ->
          allSpeciesNames.map { speciesName -> listOf(zoneName, speciesName) }
        }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (subzoneName, speciesName), results ->
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
                        species.survivalRate.toStringOrBlank("%"),
                    )
                  } ?: listOf("", "", "")
            },
        )

    assertResultsMatchCsv("$prefix/SubzoneStatsPerSpecies.csv", actual)
  }

  protected fun assertZoneSpeciesResults(
      prefix: String,
      allResults: List<ObservationResultsModel>,
  ) {
    val rowKeys =
        zoneIds.keys.flatMap { zoneName ->
          allSpeciesNames.map { speciesName -> listOf(zoneName, speciesName) }
        }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (zoneName, speciesName), results ->
              results.plantingZones
                  .firstOrNull { zoneNames[it.plantingZoneId] == zoneName }
                  ?.species
                  ?.filter { it.certainty != RecordedSpeciesCertainty.Unknown }
                  ?.firstOrNull { getSpeciesNameValue(it) == speciesName }
                  ?.let { species ->
                    listOf(
                        species.totalPlants.toStringOrBlank(),
                        species.mortalityRate.toStringOrBlank("%"),
                        species.survivalRate.toStringOrBlank("%"),
                    )
                  } ?: listOf("", "", "")
            },
        )

    assertResultsMatchCsv("$prefix/ZoneStatsPerSpecies.csv", actual)
  }

  protected fun assertPlotSpeciesResults(
      prefix: String,
      allResults: List<ObservationResultsModel>,
  ) {
    val rowKeys =
        plotIds.keys.flatMap { plotName ->
          allSpeciesNames.map { speciesName -> listOf(plotName, speciesName) }
        }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (plotNumber, speciesName), results ->
              results.plantingZones
                  .flatMap { zone -> zone.plantingSubzones }
                  .flatMap { subzone -> subzone.monitoringPlots }
                  .firstOrNull { it.monitoringPlotNumber == plotNumber.toLong() }
                  ?.species
                  ?.filter { it.certainty != RecordedSpeciesCertainty.Unknown }
                  ?.firstOrNull { getSpeciesNameValue(it) == speciesName }
                  ?.let {
                    listOf(
                        it.totalPlants.toStringOrBlank(),
                        it.mortalityRate.toStringOrBlank("%"),
                        it.survivalRate.toStringOrBlank("%"),
                    )
                  } ?: listOf("", "", "")
            },
        )

    assertResultsMatchCsv("$prefix/PlotStatsPerSpecies.csv", actual)
  }

  protected fun assertSiteSpeciesSummary(
      prefix: String,
      numSpecies: Int,
      allResults: List<ObservationRollupResultsModel>,
  ) {
    val actual =
        makeActualCsv(
            allResults,
            listOf(emptyList()),
            { _, results -> makeCsvColumnsFromSpeciesSummary(numSpecies, results.species) },
        )

    assertResultsMatchCsv("$prefix/SiteStatsPerSpeciesSummary.csv", actual, skipRows = 3)
  }

  protected fun assertZoneSpeciesSummary(
      prefix: String,
      numSpecies: Int,
      allResults: List<ObservationRollupResultsModel>,
  ) {
    val rowKeys = zoneIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (zoneName), results ->
              results.plantingZones
                  .firstOrNull { it.plantingZoneId == zoneIds[zoneName] }
                  ?.let { makeCsvColumnsFromSpeciesSummary(numSpecies, it.species) }
                  ?: List(numSpecies * 3 + 3) { "" }
            },
        )

    assertResultsMatchCsv("$prefix/ZoneStatsPerSpeciesSummary.csv", actual, skipRows = 3)
  }

  protected fun assertSubzoneSpeciesSummary(
      prefix: String,
      numSpecies: Int,
      allResults: List<ObservationRollupResultsModel>,
  ) {
    val rowKeys = subzoneIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (subzoneName), results ->
              results.plantingZones
                  .flatMap { zone -> zone.plantingSubzones }
                  .firstOrNull { subzone -> subzone.plantingSubzoneId == subzoneIds[subzoneName] }
                  ?.let { makeCsvColumnsFromSpeciesSummary(numSpecies, it.species) }
                  ?: List(numSpecies * 3 + 3) { "" }
            },
        )

    assertResultsMatchCsv("$prefix/SubzoneStatsPerSpeciesSummary.csv", actual, skipRows = 3)
  }

  protected fun assertPlotSpeciesSummary(
      prefix: String,
      numSpecies: Int,
      allResults: List<ObservationRollupResultsModel>,
  ) {
    val rowKeys = plotIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (plotNumber), results ->
              results.plantingZones
                  .flatMap { zone -> zone.plantingSubzones }
                  .flatMap { subzone -> subzone.monitoringPlots }
                  .firstOrNull { it.monitoringPlotNumber == plotNumber.toLong() }
                  ?.let { makeCsvColumnsFromSpeciesSummary(numSpecies, it.species) }
                  ?: List(numSpecies * 3 + 3) { "" }
            },
        )

    assertResultsMatchCsv("$prefix/PlotStatsPerSpeciesSummary.csv", actual, skipRows = 3)
  }

  protected fun makeCsvColumnsFromSpeciesSummary(
      numSpecies: Int,
      speciesResults: List<ObservationSpeciesResultsModel>,
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
                          it.survivalRate.toStringOrBlank("%"),
                      )
                    } ?: listOf("", "", "")
              } else {
                listOf("", "", "")
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
                  it.survivalRate.toStringOrBlank("%"),
              )
            } ?: listOf("", "", "")

    return knownSpecies + otherSpecies
  }

  protected fun importFromCsvFiles(prefix: String, numObservations: Int, sizeMeters: Int) {
    importSiteFromCsvFile(prefix, sizeMeters)
    val hasT0DensitiesSpecified = importT0DensitiesCsv(prefix)
    importPlantsCsv(prefix, numObservations, !hasT0DensitiesSpecified)
  }

  protected fun importSiteFromCsvFile(prefix: String, sizeMeters: Int) {
    importZonesCsv(prefix)
    importSubzonesCsv(prefix)
    plotIds = importPlotsCsv(prefix, sizeMeters)
  }

  protected fun importZonesCsv(prefix: String) {
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

  protected fun importSubzonesCsv(prefix: String) {
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

  protected fun importPlotsCsv(prefix: String, sizeMeters: Int): Map<String, MonitoringPlotId> {
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
              sizeMeters = sizeMeters,
              permanentIndex = plotNumber.toInt(),
          )
      insertMonitoringPlotHistory(plantingSubzoneHistoryId = subzoneHistoryId)

      if (cols[2] == "Permanent") {
        permanentPlotNumbers.add(plotNumber)
        permanentPlotIds.add(plotId)
      }

      plotNumber to plotId
    }
  }

  fun importT0DensitiesCsv(prefix: String): Boolean {
    val filePath = "$prefix/T0Densities.csv"
    if (javaClass.getResource(filePath) == null) {
      return false
    }
    mapCsv(filePath, 1) { cols ->
      val plotName = cols[0]
      val plotId = plotIds[plotName]!!

      var speciesIndex = 0
      while (true) {
        if (cols.size <= speciesIndex + 1) {
          break
        }
        val density = BigDecimal(cols[speciesIndex + 1])
        val speciesId =
            speciesIds.computeIfAbsent("Species $speciesIndex") { _ ->
              insertSpecies(scientificName = "Species $speciesIndex")
            }
        insertPlotT0Density(speciesId = speciesId, monitoringPlotId = plotId, plotDensity = density)
        speciesIndex++
      }
    }
    return true
  }

  protected fun importPlantsCsv(
      prefix: String,
      numObservations: Int,
      includeT0Data: Boolean = true,
  ) {
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

            val x = cols[4].toDouble()
            val y = cols[5].toDouble()

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
                gpsCoordinates = point(x, y),
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
                    .where(MONITORING_PLOTS.PLOT_NUMBER.`in`(observedPlotNames.map { it.toLong() }))
            )
            .execute()
      }

      // This would normally happen in ObservationService.startObservation after plot selection;
      // do it explicitly since we're specifying our own plots in the test data.
      observationStore.populateCumulativeDead(observationId)

      plantsRows
          .groupBy { it.monitoringPlotId!! }
          .forEach { (plotId, plants) ->
            if (observationNum == 0 && plotId in permanentPlotIds && includeT0Data) {
              insertPlotT0Observation(monitoringPlotId = plotId)
              plants
                  .filter {
                    it.certaintyId == RecordedSpeciesCertainty.Known &&
                        it.speciesId != null &&
                        it.statusId != RecordedPlantStatus.Existing
                  }
                  .groupingBy { it.speciesId }
                  .eachCount()
                  .forEach { (speciesId, count) ->
                    insertPlotT0Density(
                        speciesId = speciesId!!,
                        monitoringPlotId = plotId,
                        plotDensity = count.toBigDecimal(),
                    )
                  }
            }

            observationStore.completePlot(
                observationId,
                plotId,
                emptySet(),
                "Notes",
                Instant.EPOCH,
                plants,
            )
          }
    }
  }

  /** Imports plants based on bulk observation numbers. */
  protected fun importObservationsCsv(
      prefix: String,
      numSpecies: Int,
      observationNum: Int,
      observationTime: Instant,
      includeT0Data: Boolean = true,
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
          if (observationNum == 0 && plotId in permanentPlotIds && includeT0Data) {
            insertPlotT0Observation(monitoringPlotId = plotId)
            plants
                .filter {
                  it.certaintyId == RecordedSpeciesCertainty.Known &&
                      it.speciesId != null &&
                      it.statusId != RecordedPlantStatus.Existing
                }
                .groupingBy { it.speciesId }
                .eachCount()
                .forEach { (speciesId, count) ->
                  insertPlotT0Density(
                      speciesId = speciesId!!,
                      monitoringPlotId = plotId,
                      plotDensity = count.toBigDecimal(),
                  )
                }
          }

          observationStore.completePlot(
              observationId,
              plotId,
              emptySet(),
              "Notes",
              Instant.EPOCH,
              plants,
          )
        }

    return observationId
  }

  /** Maps each data row of a CSV to a value. */
  protected fun <T> mapCsv(path: String, skipRows: Int = 1, func: (Array<String>) -> T): List<T> {
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
  protected fun <T> associateCsv(
      path: String,
      skipRows: Int = 1,
      func: (Array<String>) -> Pair<String, T>,
  ): Map<String, T> {
    return mapCsv(path, skipRows, func).toMap()
  }

  /**
   * Returns a CSV representation of the results of one or more observations.
   *
   * @param rowKeys The leftmost column(s) of all the rows that could appear in the CSV. The values
   *   in these columns act as unique keys: they identify which specific set of numbers are included
   *   in the rest of the row. For example, for the "per zone per species" CSV, the key would be a
   *   zone name column and a species name column, with one element for each possible permutation of
   *   zone name and species name.
   * @param columnsFromResult Returns a group of columns for the row with a particular key from a
   *   particular observation. If the observation doesn't have any data for the row, this must
   *   return a list of empty strings. If none of the observations have any data for the row, e.g.,
   *   it's a "per zone per species" CSV and a particular species wasn't present in a particular
   *   zone, the row is not included in the generated CSV.
   */
  protected fun <T> makeActualCsv(
      allResults: List<T>,
      rowKeys: List<List<String>>,
      columnsFromResult: (List<String>, T) -> List<String>,
      columnsFromRowKeys: (List<String>) -> List<String> = { it },
  ): List<List<String>> {
    return rowKeys.mapNotNull { rowKey ->
      val keyColumns = columnsFromRowKeys(rowKey)
      val dataColumns = allResults.flatMap { results -> columnsFromResult(rowKey, results) }
      if (dataColumns.any { it.isNotEmpty() }) {
        keyColumns + dataColumns
      } else {
        null
      }
    }
  }

  /**
   * Asserts that an expected-output CSV matches the CSV representation of the actual calculation
   * results. The two header rows in the expected-output CSV are discarded.
   */
  protected fun assertResultsMatchCsv(
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

  protected fun getSpeciesNameValue(species: ObservationSpeciesResultsModel): String =
      species.speciesName ?: species.speciesId?.let { speciesNames[it] } ?: ""

  protected fun Int?.toStringOrBlank(suffix: String = "") = this?.let { "$it$suffix" } ?: ""

  /**
   * Returns a square whose area in hectares (as returned by [calculateAreaHectares]) is exactly a
   * certain value.
   *
   * This isn't a simple matter of using the square root of the desired area as the length of each
   * edge of the square because the "square" is on a curved surface and is thus distorted by varying
   * amounts depending on how big it is. So we binary-search a range of edge lengths close to the
   * square root of the area, looking for a length whose area in hectares equals the target value.
   */
  protected fun squareWithArea(areaHa: Number): MultiPolygon {
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
