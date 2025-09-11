package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesIdConverter
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_ZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITY
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.ObservationScenarioTest
import java.math.BigDecimal
import java.time.Instant
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.containsKey
import kotlin.math.roundToInt
import org.jooq.TableField
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ObservationStoreSurvivalRateCalculationTest : ObservationScenarioTest() {
  override val user = mockUser()

  private lateinit var observationId: ObservationId
  private lateinit var plotId: MonitoringPlotId
  private lateinit var subzoneId: PlantingSubzoneId
  private lateinit var zoneId: PlantingZoneId
  private val observedTime = Instant.ofEpochSecond(1)

  @BeforeEach
  fun setUp() {
    zoneId = insertPlantingZone()
    subzoneId = insertPlantingSubzone()
    plotId = insertMonitoringPlot()
    observationId = insertObservation()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)
  }

  @Test
  fun `does not calculate survival rate if no plot density`() {
    val speciesId = insertSpecies()
    val recordedPlants =
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                gpsCoordinates = point(2),
                statusId = RecordedPlantStatus.Dead,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = point(3),
                speciesName = "Who knows",
                statusId = RecordedPlantStatus.Existing,
            ),
        )
    observationStore.completePlot(
        observationId,
        plotId,
        emptySet(),
        "Notes",
        observedTime,
        recordedPlants,
    )

    assertSurvivalRates(
        listOf(
            mapOf(plotId to mapOf(speciesId to null)),
            mapOf(subzoneId to mapOf(speciesId to null)),
            mapOf(zoneId to mapOf(speciesId to null)),
            mapOf(plantingSiteId to mapOf(speciesId to null)),
        ),
        "All survival rates should be null",
    )
  }

  @Test
  fun `survival rate is calculated for a plot using plot density`() {
    val speciesId1 = insertSpecies()
    insertPlotT0Density(plotDensity = BigDecimal.valueOf(11))

    val recordedPlants =
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId1,
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                gpsCoordinates = point(2),
                statusId = RecordedPlantStatus.Dead,
            ),
        )
    observationStore.completePlot(
        observationId,
        plotId,
        emptySet(),
        "Notes",
        observedTime,
        recordedPlants,
    )

    assertSurvivalRates(
        listOf(
            mapOf(plotId to mapOf(speciesId1 to (100.0 * 1 / 11))),
            mapOf(subzoneId to mapOf(speciesId1 to (100.0 * 1 / 11))),
            mapOf(zoneId to mapOf(speciesId1 to (100.0 * 1 / 11))),
            mapOf(plantingSiteId to mapOf(speciesId1 to (100.0 * 1 / 11))),
        ),
        "Plot survival rates",
    )
  }

  @Test
  fun `survival rate is calculated for a subzone using all plots`() {
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()
    val speciesId3 = insertSpecies()
    insertPlotT0Density(speciesId = speciesId1, plotDensity = BigDecimal.valueOf(15))
    insertPlotT0Density(speciesId = speciesId2, plotDensity = BigDecimal.valueOf(23))
    insertPlotT0Density(speciesId = speciesId3, plotDensity = BigDecimal.valueOf(31))

    // plot with no density for species 3
    val plot2 = insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)
    insertPlotT0Density(speciesId = speciesId1, plotDensity = BigDecimal.valueOf(9))
    insertPlotT0Density(speciesId = speciesId2, plotDensity = BigDecimal.valueOf(19))

    val plot1Plants =
        createPlantsRows(
            mapOf(speciesId1 to 9, speciesId2 to 18, speciesId3 to 27),
            RecordedPlantStatus.Live,
        ) +
            createPlantsRows(
                mapOf(speciesId1 to 1, speciesId2 to 1, speciesId3 to 2),
                RecordedPlantStatus.Dead,
            ) +
            listOf(
                RecordedPlantsRow(
                    certaintyId = RecordedSpeciesCertainty.Unknown,
                    gpsCoordinates = point(100),
                    statusId = RecordedPlantStatus.Dead,
                ),
                RecordedPlantsRow(
                    certaintyId = RecordedSpeciesCertainty.Other,
                    gpsCoordinates = point(101),
                    speciesName = "Who knows",
                    statusId = RecordedPlantStatus.Existing,
                ),
            )
    observationStore.completePlot(
        observationId,
        plotId,
        emptySet(),
        "Notes1",
        observedTime,
        plot1Plants,
    )

    val plot1SurvivalRates: Map<Any, Map<SpeciesId, Double?>> =
        mapOf(
            plotId to
                mapOf(
                    speciesId1 to (100.0 * 9 / 15),
                    speciesId2 to (100.0 * 18 / 23),
                    speciesId3 to (100.0 * 27 / 31),
                )
        )
    val survivalRates1 =
        mapOf(
            speciesId1 to (100.0 * 9 / (15 + 9)),
            speciesId2 to (100.0 * 18 / (23 + 19)),
            speciesId3 to (100.0 * 27 / 31),
        )

    assertSurvivalRates(
        listOf(
            plot1SurvivalRates,
            mapOf(subzoneId to survivalRates1),
            mapOf(zoneId to survivalRates1),
            mapOf(plantingSiteId to survivalRates1),
        ),
        "Plot 1 survival rates",
    )

    val plot2Plants =
        createPlantsRows(
            mapOf(speciesId1 to 10, speciesId2 to 17, speciesId3 to 25),
            RecordedPlantStatus.Live,
        ) +
            createPlantsRows(
                mapOf(speciesId1 to 0, speciesId2 to 1, speciesId3 to 1),
                RecordedPlantStatus.Dead,
            ) +
            listOf(
                RecordedPlantsRow(
                    certaintyId = RecordedSpeciesCertainty.Other,
                    gpsCoordinates = point(102),
                    speciesName = "Who knows",
                    statusId = RecordedPlantStatus.Existing,
                )
            )
    observationStore.completePlot(
        observationId,
        plot2,
        emptySet(),
        "Notes2",
        observedTime,
        plot2Plants,
    )

    val plot2SurvivalRates: Map<Any, Map<SpeciesId, Double?>> =
        mapOf(
            plot2 to
                mapOf(
                    speciesId1 to (100.0 * 10 / 9),
                    speciesId2 to (100.0 * 17 / 19),
                    speciesId3 to null,
                )
        )
    val survivalRates1And2 =
        mapOf(
            speciesId1 to (100.0 * (9 + 10) / (15 + 9)),
            speciesId2 to (100.0 * (17 + 18) / (23 + 19)),
            speciesId3 to (100.0 * (27 + 25) / 31),
        )
    assertSurvivalRates(
        listOf(
            plot1SurvivalRates + plot2SurvivalRates,
            mapOf(subzoneId to survivalRates1And2),
            mapOf(zoneId to survivalRates1And2),
            mapOf(plantingSiteId to survivalRates1And2),
        ),
        "Plots 1 and 2 survival rates",
    )
  }

  @Test
  fun `survival rate is calculated for a site using all data`() {
    runSurvivalRateScenario("/tracking/observation/SurvivalRateSiteData", numSpecies = 3)
  }

  @Test
  fun `survival rate calculation excludes t0 densities for plots that have no observations`() {
    runSurvivalRateScenario("/tracking/observation/SurvivalRateNoObservations", numSpecies = 1)
  }

  @Test
  fun `survival rate is updated when t0 density changes`() {
    runSurvivalRateScenario("/tracking/observation/SurvivalRateT0DensityChange", numSpecies = 2)

    val species1 = speciesIds["Species 0"]!!
    val plot1 = plotIds["111"]!!

    with(PLOT_T0_DENSITY) {
      dslContext
          .update(this)
          .set(PLOT_DENSITY, BigDecimal.valueOf(20.0))
          .where(MONITORING_PLOT_ID.eq(plot1))
          .and(SPECIES_ID.eq(species1))
          .execute()
    }
    observationStore.recalculateSurvivalRates(plot1)

    val ratesAfterUpdate =
        loadExpectedSurvivalRates(
            "/tracking/observation/SurvivalRateT0DensityChange/AfterUpdate",
            2,
        )
    assertSurvivalRates(ratesAfterUpdate, "Rates After Update")
  }

  private var lastCoord: Int = 1

  private fun runSurvivalRateScenario(prefix: String, numSpecies: Int) {
    importSiteFromCsvFile(prefix, 30)
    observationId = insertObservation()
    importT0DensitiesCsv(prefix)
    importObservationsCsv(prefix, numSpecies, 0, Instant.EPOCH, false)

    val expectedRates = loadExpectedSurvivalRates(prefix, numSpecies)
    assertSurvivalRates(expectedRates, "$prefix - Survival Rate")
  }

  private fun loadExpectedSurvivalRates(
      prefix: String,
      numSpecies: Int,
  ): List<Map<Any, Map<SpeciesId, Number?>>> {
    val speciesIds = (0 until numSpecies).map { "Species $it" to this.speciesIds["Species $it"]!! }

    val plotRates = mutableMapOf<Any, Map<SpeciesId, Number?>>()
    mapCsv("$prefix/PlotRates.csv", 1) { cols ->
      val plotName = cols[0]
      val plotId = plotIds[plotName]!!
      val ratesMap = mutableMapOf<SpeciesId, Number?>()

      for ((index, speciesPair) in speciesIds.withIndex()) {
        val rateStr = cols[index + 2].takeIf { it.isNotEmpty() }
        val rate = rateStr?.removeSuffix("%")?.toDoubleOrNull()
        if (rate != null) {
          ratesMap[speciesPair.second] = rate
        }
      }
      plotRates[plotId] = ratesMap
    }

    val subzoneRates = mutableMapOf<Any, Map<SpeciesId, Number?>>()
    mapCsv("$prefix/SubzoneRates.csv", 1) { cols ->
      val subzoneName = cols[0]
      val subzoneId = subzoneIds[subzoneName]!!
      val ratesMap = mutableMapOf<SpeciesId, Number?>()

      for ((index, speciesPair) in speciesIds.withIndex()) {
        val rateStr = cols[index + 2].takeIf { it.isNotEmpty() }
        val rate = rateStr?.removeSuffix("%")?.toDoubleOrNull()
        if (rate != null) {
          ratesMap[speciesPair.second] = rate
        }
      }
      subzoneRates[subzoneId] = ratesMap
    }

    val zoneRates = mutableMapOf<Any, Map<SpeciesId, Number?>>()
    mapCsv("$prefix/ZoneRates.csv", 1) { cols ->
      val zoneName = cols[0]
      val zoneId = zoneIds[zoneName]!!
      val ratesMap = mutableMapOf<SpeciesId, Number?>()

      for ((index, speciesPair) in speciesIds.withIndex()) {
        val rateStr = cols[index + 2].takeIf { it.isNotEmpty() }
        val rate = rateStr?.removeSuffix("%")?.toDoubleOrNull()
        if (rate != null) {
          ratesMap[speciesPair.second] = rate
        }
      }
      zoneRates[zoneId] = ratesMap
    }

    val siteRates = mutableMapOf<Any, Map<SpeciesId, Number?>>()
    mapCsv("$prefix/SiteRates.csv", 1) { cols ->
      val ratesMap = mutableMapOf<SpeciesId, Number?>()

      for ((index, speciesPair) in speciesIds.withIndex()) {
        val rateStr = cols[index + 2].takeIf { it.isNotEmpty() }
        val rate = rateStr?.removeSuffix("%")?.toDoubleOrNull()
        if (rate != null) {
          ratesMap[speciesPair.second] = rate
        }
      }
      siteRates[plantingSiteId] = ratesMap
    }

    return listOf(plotRates, subzoneRates, zoneRates, siteRates)
  }

  private fun createPlantsRows(
      counts: Map<SpeciesId, Int>,
      status: RecordedPlantStatus,
  ): List<RecordedPlantsRow> {
    return counts.flatMap { (speciesId, count) ->
      (1..count).map {
        RecordedPlantsRow(
            certaintyId = RecordedSpeciesCertainty.Known,
            gpsCoordinates = point(lastCoord++),
            speciesId = speciesId,
            statusId = status,
        )
      }
    }
  }

  private fun assertSurvivalRates(
      expected: List<Map<Any, Map<SpeciesId, Number?>>>,
      message: String,
  ) {
    val idFields =
        listOf(
            "Plot" to OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID,
            "Subzone" to OBSERVED_SUBZONE_SPECIES_TOTALS.PLANTING_SUBZONE_ID,
            "Zone" to OBSERVED_ZONE_SPECIES_TOTALS.PLANTING_ZONE_ID,
            "Site" to OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID,
        )

    expected.forEachIndexed { index, expectedByIdMap ->
      val level = idFields[index].first
      val idField = idFields[index].second
      val actualByIdMap = fetchSurvivalRatesPerSpecies(idField)

      val allIdsMatch =
          actualByIdMap.all { (id, actualSpeciesMap) ->
            val expectedSpeciesMap = expectedByIdMap[id] ?: return@all false

            actualSpeciesMap.all { (speciesId, actualRate) ->
              val expectedRate = expectedSpeciesMap[speciesId]
              when {
                expectedRate == null -> actualRate == null
                actualRate == null -> false
                expectedRate is Double -> expectedRate.roundToInt() == actualRate
                else -> expectedRate == actualRate
              }
            } &&
                expectedSpeciesMap.all { (speciesId, _) -> actualSpeciesMap.containsKey(speciesId) }
          } && expectedByIdMap.all { (id, _) -> actualByIdMap.containsKey(id) }

      if (!allIdsMatch) {
        val expectedString =
            expectedByIdMap.entries.joinToString("\n") { (id, speciesMap) ->
              "$level $id: {${speciesMap.entries.joinToString(", ") { "SpeciesId: ${it.key} -> SurvivalRate: ${it.value?.let { value -> if (value is Double) value.roundToInt() else value }}" }}}"
            }
        val actualString =
            actualByIdMap.entries.joinToString("\n") { (id, speciesMap) ->
              "$level $id: {${speciesMap.entries.joinToString(", ") { "SpeciesId: ${it.key} -> SurvivalRate: ${it.value}" }}}"
            }
        assertEquals(expectedString, actualString, "$message - $level")
      }
    }
  }

  private fun <ID> fetchSurvivalRatesPerSpecies(
      idField: TableField<*, ID>
  ): Map<ID, Map<SpeciesId, Int?>> {
    val table = idField.table!!
    val speciesIdField =
        table.field("species_id", SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()))!!
    val survivalRateField = table.field("survival_rate", Int::class.java)!!

    return dslContext
        .select(idField, speciesIdField, survivalRateField)
        .from(table)
        .where(speciesIdField.isNotNull)
        .fetch()
        .filter { it[idField] != null }
        .groupBy { it[idField]!! }
        .mapValues { (_, records) ->
          records.associate { record -> record[speciesIdField]!! to record[survivalRateField] }
        }
  }
}
