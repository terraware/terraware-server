package com.terraformation.backend.tracking.db

import com.opencsv.CSVReader
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_REQUESTED_SUBSTRATA
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.toBigDecimal
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationRollupResultsModel
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsModel
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.toPlantsPerHectare
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
  protected val eventPublisher = TestEventPublisher()
  protected val observationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        eventPublisher,
        ObservationLocker(dslContext),
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        observationRequestedSubstrataDao,
        ParentStore(dslContext),
    )
  }
  protected val resultsStore by lazy { ObservationResultsStore(dslContext) }

  protected lateinit var plotIds: MutableMap<String, MonitoringPlotId>
  protected val plotHistoryIds = mutableMapOf<MonitoringPlotId, MonitoringPlotHistoryId>()
  protected lateinit var substratumHistoryIds: Map<SubstratumId, SubstratumHistoryId>
  protected lateinit var substratumIds: Map<String, SubstratumId>
  protected lateinit var stratumHistoryIds: Map<StratumId, StratumHistoryId>
  protected lateinit var stratumIds: Map<String, StratumId>

  protected val stratumNames: Map<StratumId, String> by lazy {
    stratumIds.entries.associate { it.value to it.key }
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
    importT0ZoneDensitiesCsv(prefix)

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
        { assertStratumSummary(prefix, results) },
        { assertStratumSpeciesSummary(prefix, numSpecies, results) },
        { assertSubstratumSummary(prefix, results) },
        { assertSubstratumSpeciesSummary(prefix, numSpecies, results) },
        { assertPlotSummary(prefix, results) },
        { assertPlotSpeciesSummary(prefix, numSpecies, results) },
    )
  }

  protected fun assertResults(prefix: String, allResults: List<ObservationResultsModel>) {
    assertAll(
        { assertSiteResults(prefix, allResults) },
        { assertSiteSpeciesResults(prefix, allResults) },
        { assertStratumResults(prefix, allResults) },
        { assertStratumSpeciesResults(prefix, allResults) },
        { assertSubstratumResults(prefix, allResults) },
        { assertSubstratumSpeciesResults(prefix, allResults) },
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

  protected fun assertStratumResults(prefix: String, allResults: List<ObservationResultsModel>) {
    val rowKeys = stratumIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (stratumName), results ->
              val stratum = results.strata.first { it.stratumId == stratumIds[stratumName] }
              listOf(
                  stratum.totalPlants.toStringOrBlank(),
                  stratum.plantingDensity.toStringOrBlank(),
                  stratum.totalSpecies.toStringOrBlank(),
                  stratum.mortalityRate.toStringOrBlank("%"),
                  stratum.survivalRate.toStringOrBlank("%"),
                  stratum.estimatedPlants.toStringOrBlank(),
              )
            },
        )

    assertResultsMatchCsv("$prefix/ZoneStats.csv", actual)
  }

  protected fun assertSubstratumResults(prefix: String, allResults: List<ObservationResultsModel>) {
    val rowKeys = substratumIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (substratumName), results ->
              val substratum =
                  results.strata
                      .flatMap { it.substrata }
                      .firstOrNull { it.substratumId == substratumIds[substratumName] }
              listOf(
                  substratum?.totalPlants.toStringOrBlank(),
                  substratum?.plantingDensity.toStringOrBlank(),
                  substratum?.totalSpecies.toStringOrBlank(),
                  substratum?.mortalityRate.toStringOrBlank("%"),
                  substratum?.survivalRate.toStringOrBlank("%"),
                  substratum?.estimatedPlants.toStringOrBlank(),
              )
            },
        )

    assertResultsMatchCsv("$prefix/SubzoneStats.csv", actual)
  }

  protected fun assertPlantsResults(filePath: String, results: ObservationResultsModel) {
    val plots =
        results.strata
            .flatMap { stratum -> stratum.substrata }
            .flatMap { substratum -> substratum.monitoringPlots }

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
                  results.strata
                      .flatMap { stratum -> stratum.substrata }
                      .flatMap { substratum -> substratum.monitoringPlots }
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
                  results.strata
                      .flatMap { stratum -> stratum.substrata }
                      .flatMap { substratum -> substratum.monitoringPlots }
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

  protected fun assertStratumSummary(
      prefix: String,
      allResults: List<ObservationRollupResultsModel>,
  ) {
    val rowKeys = stratumIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (stratumName), results ->
              val stratum = results.strata.firstOrNull { it.stratumId == stratumIds[stratumName] }
              listOf(
                  stratum?.totalPlants.toStringOrBlank(),
                  stratum?.plantingDensity.toStringOrBlank(),
                  stratum?.plantingDensityStdDev.toStringOrBlank(),
                  stratum?.totalSpecies.toStringOrBlank(),
                  stratum?.mortalityRate.toStringOrBlank("%"),
                  stratum?.mortalityRateStdDev.toStringOrBlank("%"),
                  stratum?.survivalRate.toStringOrBlank("%"),
                  stratum?.survivalRateStdDev.toStringOrBlank("%"),
                  stratum?.estimatedPlants.toStringOrBlank(),
              )
            },
        )

    assertResultsMatchCsv("$prefix/ZoneStatsSummary.csv", actual)
  }

  protected fun assertSubstratumSummary(
      prefix: String,
      allResults: List<ObservationRollupResultsModel>,
  ) {
    val rowKeys = substratumIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (substratumName), results ->
              val substratum =
                  results.strata
                      .flatMap { it.substrata }
                      .firstOrNull { it.substratumId == substratumIds[substratumName] }
              listOf(
                  substratum?.totalPlants.toStringOrBlank(),
                  substratum?.plantingDensity.toStringOrBlank(),
                  substratum?.plantingDensityStdDev.toStringOrBlank(),
                  substratum?.totalSpecies.toStringOrBlank(),
                  substratum?.mortalityRate.toStringOrBlank("%"),
                  substratum?.mortalityRateStdDev.toStringOrBlank("%"),
                  substratum?.survivalRate.toStringOrBlank("%"),
                  substratum?.survivalRateStdDev.toStringOrBlank("%"),
                  substratum?.estimatedPlants.toStringOrBlank(),
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
              results.strata
                  .flatMap { stratum -> stratum.substrata }
                  .flatMap { substratum -> substratum.monitoringPlots }
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
        val positionInColumnGroup = (index - 1) % 11
        positionInColumnGroup !in 4..9
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

  protected fun assertSubstratumSpeciesResults(
      prefix: String,
      allResults: List<ObservationResultsModel>,
  ) {
    val rowKeys =
        substratumIds.keys.flatMap { stratumName ->
          allSpeciesNames.map { speciesName -> listOf(stratumName, speciesName) }
        }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (substratumName, speciesName), results ->
              results.strata
                  .flatMap { it.substrata }
                  .firstOrNull { it.substratumId == substratumIds[substratumName] }
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

  protected fun assertStratumSpeciesResults(
      prefix: String,
      allResults: List<ObservationResultsModel>,
  ) {
    val rowKeys =
        stratumIds.keys.flatMap { stratumName ->
          allSpeciesNames.map { speciesName -> listOf(stratumName, speciesName) }
        }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (stratumName, speciesName), results ->
              results.strata
                  .firstOrNull { stratumNames[it.stratumId] == stratumName }
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
              results.strata
                  .flatMap { stratum -> stratum.substrata }
                  .flatMap { substratum -> substratum.monitoringPlots }
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

  protected fun assertStratumSpeciesSummary(
      prefix: String,
      numSpecies: Int,
      allResults: List<ObservationRollupResultsModel>,
  ) {
    val rowKeys = stratumIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (stratumName), results ->
              results.strata
                  .firstOrNull { it.stratumId == stratumIds[stratumName] }
                  ?.let { makeCsvColumnsFromSpeciesSummary(numSpecies, it.species) }
                  ?: List(numSpecies * 3 + 3) { "" }
            },
        )

    assertResultsMatchCsv("$prefix/ZoneStatsPerSpeciesSummary.csv", actual, skipRows = 3)
  }

  protected fun assertSubstratumSpeciesSummary(
      prefix: String,
      numSpecies: Int,
      allResults: List<ObservationRollupResultsModel>,
  ) {
    val rowKeys = substratumIds.keys.map { listOf(it) }

    val actual =
        makeActualCsv(
            allResults,
            rowKeys,
            { (substratumName), results ->
              results.strata
                  .flatMap { stratum -> stratum.substrata }
                  .firstOrNull { substratum ->
                    substratum.substratumId == substratumIds[substratumName]
                  }
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
              results.strata
                  .flatMap { stratum -> stratum.substrata }
                  .flatMap { substratum -> substratum.monitoringPlots }
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
    importStrataCsv(prefix)
    importSubstrataCsv(prefix)
    plotIds = importPlotsCsv(prefix, sizeMeters)
  }

  protected fun importStrataCsv(prefix: String) {
    val newStratumIds = mutableMapOf<String, StratumId>()
    val newStratumHistoryIds = mutableMapOf<StratumId, StratumHistoryId>()

    mapCsv("$prefix/Zones.csv", 2) { cols ->
      val stratumName = cols[1]
      val areaHa = BigDecimal(cols[2])

      val stratumId =
          insertPlantingZone(areaHa = areaHa, boundary = squareWithArea(areaHa), name = stratumName)
      newStratumIds[stratumName] = stratumId
      newStratumHistoryIds[stratumId] = inserted.plantingZoneHistoryId
    }

    stratumIds = newStratumIds
    stratumHistoryIds = newStratumHistoryIds
  }

  protected fun importSubstrataCsv(prefix: String) {
    val newSubstratumIds = mutableMapOf<String, SubstratumId>()
    val newSubstratumHistoryIds = mutableMapOf<SubstratumId, SubstratumHistoryId>()

    mapCsv("$prefix/Subzones.csv", 2) { cols ->
      val stratumName = cols[0]
      val substratumName = cols[1]
      val substratumArea = BigDecimal(cols[2])
      val stratumId = stratumIds[stratumName]!!
      val stratumHistoryId = stratumHistoryIds[stratumId]!!

      // Find the first observation where the substratum is marked as completed planting, if any.
      val plantingCompletedColumn = cols.drop(3).indexOfFirst { it == "Yes" }
      val plantingCompletedTime =
          if (plantingCompletedColumn >= 0) {
            Instant.ofEpochSecond(plantingCompletedColumn.toLong())
          } else {
            null
          }

      val substratumId =
          insertPlantingSubzone(
              areaHa = substratumArea,
              boundary = squareWithArea(substratumArea),
              plantingCompletedTime = plantingCompletedTime,
              fullName = substratumName,
              insertHistory = false,
              name = substratumName,
              plantingZoneId = stratumId,
          )
      insertPlantingSubzoneHistory(plantingZoneHistoryId = stratumHistoryId)

      newSubstratumIds[substratumName] = substratumId
      newSubstratumHistoryIds[substratumId] = inserted.plantingSubzoneHistoryId
    }

    substratumIds = newSubstratumIds
    substratumHistoryIds = newSubstratumHistoryIds
  }

  protected fun importPlotsCsv(
      prefix: String,
      sizeMeters: Int,
  ): MutableMap<String, MonitoringPlotId> {
    return associateCsv("$prefix/Plots.csv") { cols ->
      val substratumName = cols[0]
      val plotNumber = cols[1]
      val substratumId = substratumIds[substratumName]!!
      val substratumHistoryId = substratumHistoryIds[substratumId]!!
      val isPermanent = cols[2] == "Permanent"

      val plotId =
          insertMonitoringPlot(
              insertHistory = false,
              plantingSubzoneId = substratumId,
              plotNumber = plotNumber.toLong(),
              sizeMeters = sizeMeters,
              permanentIndex = if (isPermanent) plotNumber.toInt() else null,
          )
      plotHistoryIds[plotId] =
          insertMonitoringPlotHistory(
              plantingSubzoneId = substratumId,
              plantingSubzoneHistoryId = substratumHistoryId,
          )

      if (isPermanent) {
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
        val density = cols[speciesIndex + 1].toBigDecimal()
        val speciesId =
            speciesIds.computeIfAbsent("Species $speciesIndex") { _ ->
              insertSpecies(scientificName = "Species $speciesIndex")
            }
        insertPlotT0Density(
            speciesId = speciesId,
            monitoringPlotId = plotId,
            plotDensity = density.toPlantsPerHectare(),
        )
        speciesIndex++
      }
    }
    return true
  }

  fun importT0ZoneDensitiesCsv(prefix: String): Boolean {
    val filePath = "$prefix/T0ZoneDensities.csv"
    if (javaClass.getResource(filePath) == null) {
      return false
    }
    mapCsv(filePath, 1) { cols ->
      val stratumName = cols[0]
      val stratumId = stratumIds[stratumName]!!

      var speciesIndex = 0
      while (true) {
        if (cols.size <= speciesIndex + 1) {
          break
        }
        val density = cols[speciesIndex + 1].toBigDecimal()
        val speciesId =
            speciesIds.computeIfAbsent("Species $speciesIndex") { _ ->
              insertSpecies(scientificName = "Species $speciesIndex")
            }
        insertPlantingZoneT0TempDensity(
            speciesId = speciesId,
            plantingZoneId = stratumId,
            zoneDensity = density.toPlantsPerHectare(),
        )
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
                  monitoringPlotHistoryId = plotHistoryIds[plotId]!!,
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

      with(OBSERVATION_REQUESTED_SUBSTRATA) {
        dslContext
            .insertInto(OBSERVATION_REQUESTED_SUBSTRATA, OBSERVATION_ID, SUBSTRATUM_ID)
            .select(
                DSL.selectDistinct(
                        DSL.value(observationId),
                        MONITORING_PLOTS.SUBSTRATUM_ID,
                    )
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
                        plotDensity = count.toBigDecimal().toPlantsPerHectare(),
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

    val observationId = insertObservation(completedTime = observationTime)
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
                    monitoringPlotHistoryId = plotHistoryIds[plotId]!!,
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

    with(OBSERVATION_REQUESTED_SUBSTRATA) {
      dslContext
          .insertInto(OBSERVATION_REQUESTED_SUBSTRATA, OBSERVATION_ID, SUBSTRATUM_ID)
          .select(
              DSL.selectDistinct(DSL.value(observationId), MONITORING_PLOTS.SUBSTRATUM_ID)
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
                      plotDensity = count.toBigDecimal().toPlantsPerHectare(),
                  )
                }
          }

          observationStore.completePlot(
              observationId,
              plotId,
              emptySet(),
              "Notes",
              observationTime,
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
  ): MutableMap<String, T> {
    return mapCsv(path, skipRows, func).toMap().toMutableMap()
  }

  /**
   * Returns a CSV representation of the results of one or more observations.
   *
   * @param rowKeys The leftmost column(s) of all the rows that could appear in the CSV. The values
   *   in these columns act as unique keys: they identify which specific set of numbers are included
   *   in the rest of the row. For example, for the "per stratum per species" CSV, the key would be
   *   a stratum name column and a species name column, with one element for each possible
   *   permutation of stratum name and species name.
   * @param columnsFromResult Returns a group of columns for the row with a particular key from a
   *   particular observation. If the observation doesn't have any data for the row, this must
   *   return a list of empty strings. If none of the observations have any data for the row, e.g.,
   *   it's a "per stratum per species" CSV and a particular species wasn't present in a particular
   *   stratum, the row is not included in the generated CSV.
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
