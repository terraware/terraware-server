package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_T0_TEMP_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.ObservationScenarioTest
import com.terraformation.backend.util.toPlantsPerHectare
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

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
    insertPlotT0Density(plotDensity = BigDecimal.valueOf(11).toPlantsPerHectare())

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

    val allSurvivalRate = (100.0 * 1 / 11)
    assertSurvivalRates(
        listOf(
            mapOf(plotId to mapOf(speciesId1 to allSurvivalRate, null to allSurvivalRate)),
            mapOf(subzoneId to mapOf(speciesId1 to allSurvivalRate, null to allSurvivalRate)),
            mapOf(zoneId to mapOf(speciesId1 to allSurvivalRate, null to allSurvivalRate)),
            mapOf(plantingSiteId to mapOf(speciesId1 to allSurvivalRate, null to allSurvivalRate)),
        ),
        "Plot survival rates",
    )
  }

  @Test
  fun `survival rate is calculated for a subzone using all plots`() {
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()
    val speciesId3 = insertSpecies()
    insertPlotT0Density(
        speciesId = speciesId1,
        plotDensity = BigDecimal.valueOf(15).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = speciesId2,
        plotDensity = BigDecimal.valueOf(23).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = speciesId3,
        plotDensity = BigDecimal.valueOf(31).toPlantsPerHectare(),
    )

    // plot with no density for species 3
    val plot2 = insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)
    insertPlotT0Density(
        speciesId = speciesId1,
        plotDensity = BigDecimal.valueOf(9).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = speciesId2,
        plotDensity = BigDecimal.valueOf(19).toPlantsPerHectare(),
    )

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

    val plot1SurvivalRates: Map<Any, Map<SpeciesId?, Double?>> =
        mapOf(
            plotId to
                mapOf(
                    speciesId1 to (100.0 * 9 / 15),
                    speciesId2 to (100.0 * 18 / 23),
                    speciesId3 to (100.0 * 27 / 31),
                    null to 100.0 * (9 + 18 + 27) / (15 + 23 + 31),
                )
        )
    val survivalRates1: Map<SpeciesId?, Double?> =
        mapOf(
            speciesId1 to 100.0 * 9 / 15,
            speciesId2 to 100.0 * 18 / 23,
            speciesId3 to 100.0 * 27 / 31,
            null to 100.0 * (9 + 18 + 27) / (15 + 23 + 31),
        )

    assertSurvivalRates(
        listOf(
            plot1SurvivalRates + mapOf(plot2 to mapOf(speciesId1 to 0, speciesId2 to 0, null to 0)),
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

    val plot2SurvivalRates: Map<Any, Map<SpeciesId?, Double?>> =
        mapOf(
            plot2 to
                mapOf(
                    speciesId1 to (100.0 * 10 / 9),
                    speciesId2 to (100.0 * 17 / 19),
                    speciesId3 to null,
                    null to 100.0 * (10 + 17 + 25) / (9 + 19),
                )
        )
    val survivalRates1And2: Map<SpeciesId?, Double?> =
        mapOf(
            speciesId1 to (100.0 * (9 + 10) / (15 + 9)),
            speciesId2 to (100.0 * (17 + 18) / (23 + 19)),
            speciesId3 to (100.0 * (27 + 25) / 31),
            null to 100.0 * (9 + 10 + 17 + 18 + 27 + 25) / (15 + 9 + 23 + 19 + 31),
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
  fun `survival rate is recalculated correctly for a subzone that has only temp plots`() {
    val plantingSiteId = insertPlantingSite(survivalRateIncludesTempPlots = true)
    val zoneId = insertPlantingZone()
    val subzone1 = insertPlantingSubzone()
    val observationId1 = insertObservation()
    insertObservationRequestedSubzone()
    val tempPlot1 = insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId, isPermanent = false)
    val observationId2 = insertObservation()
    val subzone2 = insertPlantingSubzone()
    insertObservationRequestedSubzone()
    val tempPlot2 = insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId, isPermanent = false)
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()
    insertPlantingZoneT0TempDensity(
        speciesId = speciesId1,
        zoneDensity = BigDecimal.valueOf(15).toPlantsPerHectare(),
    )
    insertPlantingZoneT0TempDensity(
        speciesId = speciesId2,
        zoneDensity = BigDecimal.valueOf(30).toPlantsPerHectare(),
    )

    every { user.canReadObservation(any()) } returns true
    every { user.canUpdateObservation(any()) } returns true
    every { user.canReadPlantingSite(plantingSiteId) } returns true

    val plot1Plants =
        createPlantsRows(
            mapOf(speciesId1 to 10, speciesId2 to 15),
            RecordedPlantStatus.Live,
        )
    observationStore.completePlot(
        observationId1,
        tempPlot1,
        emptySet(),
        "NotesPlot2",
        observedTime,
        plot1Plants,
    )

    val plot1SurvivalRates: Map<SpeciesId?, Double?> =
        mapOf(
            speciesId1 to 100.0 * 10 / 15,
            speciesId2 to 100.0 * 15 / 30,
            // null to 100.0 * (10 + 15) / (15 + 30), // will be added later
        )
    assertSurvivalRates(
        listOf(
            mapOf(tempPlot1 to plot1SurvivalRates),
            mapOf(subzone1 to plot1SurvivalRates),
            mapOf(zoneId to plot1SurvivalRates),
            mapOf(plantingSiteId to plot1SurvivalRates),
        ),
        "Temp plot 1 survival rates",
    )

    val plot2Plants =
        createPlantsRows(mapOf(speciesId1 to 4, speciesId2 to 9), RecordedPlantStatus.Live)
    observationStore.completePlot(
        observationId2,
        tempPlot2,
        emptySet(),
        "NotesPlot2",
        observedTime,
        plot2Plants,
    )

    val plot2SurvivalRates: Map<SpeciesId?, Double?> =
        mapOf(
            speciesId1 to 100.0 * 4 / 15,
            speciesId2 to 100.0 * 9 / 30,
            // null to 100.0 * (4 + 9) / (15 + 30), // will be added later
        )
    val survivalRates1And2: Map<SpeciesId?, Double?> =
        mapOf(
            speciesId1 to (100.0 * (10 + 4) / (15 + 15)),
            speciesId2 to (100.0 * (15 + 9) / (30 + 30)),
            // null to 100.0 * (10 + 4 + 15 + 9) / (15 + 15 + 30 + 30), // will be added later
        )
    assertSurvivalRates(
        listOf(
            mapOf(
                // excludes plot1 because it's in a different observation
                tempPlot2 to plot2SurvivalRates,
            ),
            mapOf(
                subzone2 to plot2SurvivalRates,
            ),
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
  fun `survival rate is calculated for a site including temp plot data`() {
    val newPlantingSiteId =
        insertPlantingSite(x = 0, areaHa = BigDecimal(2500), survivalRateIncludesTempPlots = true)

    every { user.canReadPlantingSite(newPlantingSiteId) } returns true

    runSurvivalRateScenario("/tracking/observation/SurvivalRateSiteTempData", numSpecies = 3)
  }

  @Test
  fun `survival rate calculation excludes t0 densities for plots that have no observations`() {
    runSurvivalRateScenario("/tracking/observation/SurvivalRateNoObservations", numSpecies = 2)
  }

  @Test
  fun `survival rate calculation includes t0 densities for species that have no recorded plants`() {
    runSurvivalRateScenario("/tracking/observation/SurvivalRateNoRecorded", numSpecies = 2)
  }

  @Test
  fun `survival rate is updated when t0 density changes`() {
    runSurvivalRateScenario("/tracking/observation/SurvivalRateT0DensityChange", numSpecies = 2)

    val species1 = speciesIds["Species 0"]!!
    val plot1 = plotIds["111"]!!

    with(PLOT_T0_DENSITIES) {
      dslContext
          .update(this)
          .set(
              PLOT_DENSITY,
              BigDecimal.valueOf(20).toPlantsPerHectare(),
          )
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

  @Test
  fun `survival rate with temp plots is updated when t0 zone density changes`() {
    val newPlantingSiteId =
        insertPlantingSite(x = 0, areaHa = BigDecimal(2500), survivalRateIncludesTempPlots = true)

    every { user.canReadPlantingSite(newPlantingSiteId) } returns true

    runSurvivalRateScenario("/tracking/observation/SurvivalRateT0ZoneDensityChange", numSpecies = 2)

    val species1 = speciesIds["Species 0"]!!
    val zone1 = zoneIds["Zone1"]!!

    with(PLANTING_ZONE_T0_TEMP_DENSITIES) {
      dslContext
          .update(this)
          .set(
              ZONE_DENSITY,
              BigDecimal.valueOf(20).toPlantsPerHectare(),
          )
          .where(PLANTING_ZONE_ID.eq(zone1))
          .and(SPECIES_ID.eq(species1))
          .execute()
    }
    val plotsInUpdatedZone = listOf("111", "112")
    plotsInUpdatedZone.forEach { observationStore.recalculateSurvivalRates(plotIds[it]!!) }

    val ratesAfterUpdate =
        loadExpectedSurvivalRates(
            "/tracking/observation/SurvivalRateT0ZoneDensityChange/AfterUpdate",
            2,
        )
    assertSurvivalRates(ratesAfterUpdate, "Rates After Update")
  }

  private var lastCoord: Int = 1

  private fun runSurvivalRateScenario(prefix: String, numSpecies: Int) {
    importSiteFromCsvFile(prefix, 30)
    observationId = insertObservation()
    importT0DensitiesCsv(prefix)
    importT0ZoneDensitiesCsv(prefix)
    importObservationsCsv(prefix, numSpecies, 0, Instant.EPOCH, false)

    val expectedRates = loadExpectedSurvivalRates(prefix, numSpecies)
    assertSurvivalRates(expectedRates, "$prefix - Survival Rate")
  }

  private fun loadExpectedSurvivalRates(
      prefix: String,
      numSpecies: Int,
  ): List<Map<Any, Map<SpeciesId?, Number?>>> {
    val speciesIds = (0 until numSpecies).map { "Species $it" to this.speciesIds["Species $it"]!! }

    val plotRates = mutableMapOf<Any, Map<SpeciesId?, Number?>>()
    mapCsv("$prefix/PlotRates.csv", 1) { cols ->
      val plotName = cols[0]
      val plotId = plotIds[plotName]!!
      val ratesMap = mutableMapOf<SpeciesId?, Number?>()

      for ((index, speciesPair) in speciesIds.withIndex()) {
        val rateStr = cols[index + 2].takeIf { it.isNotEmpty() }
        val rate = rateStr?.removeSuffix("%")?.toDoubleOrNull()
        if (rate != null) {
          ratesMap[speciesPair.second] = rate
        }
      }
      cols[1]
          .takeIf { it.isNotEmpty() }
          ?.let { ratesMap[null] = it.removeSuffix("%").toDoubleOrNull() }
      plotRates[plotId] = ratesMap
    }

    val subzoneRates = mutableMapOf<Any, Map<SpeciesId?, Number?>>()
    mapCsv("$prefix/SubzoneRates.csv", 1) { cols ->
      val subzoneName = cols[0]
      val subzoneId = subzoneIds[subzoneName]!!
      val ratesMap = mutableMapOf<SpeciesId?, Number?>()

      for ((index, speciesPair) in speciesIds.withIndex()) {
        val rateStr = cols[index + 2].takeIf { it.isNotEmpty() }
        val rate = rateStr?.removeSuffix("%")?.toDoubleOrNull()
        if (rate != null) {
          ratesMap[speciesPair.second] = rate
        }
      }
      cols[1]
          .takeIf { it.isNotEmpty() }
          ?.let { ratesMap[null] = it.removeSuffix("%").toDoubleOrNull() }
      subzoneRates[subzoneId] = ratesMap
    }

    val zoneRates = mutableMapOf<Any, Map<SpeciesId?, Number?>>()
    mapCsv("$prefix/ZoneRates.csv", 1) { cols ->
      val zoneName = cols[0]
      val zoneId = zoneIds[zoneName]!!
      val ratesMap = mutableMapOf<SpeciesId?, Number?>()

      for ((index, speciesPair) in speciesIds.withIndex()) {
        val rateStr = cols[index + 2].takeIf { it.isNotEmpty() }
        val rate = rateStr?.removeSuffix("%")?.toDoubleOrNull()
        if (rate != null) {
          ratesMap[speciesPair.second] = rate
        }
      }
      cols[1]
          .takeIf { it.isNotEmpty() }
          ?.let { ratesMap[null] = it.removeSuffix("%").toDoubleOrNull() }
      zoneRates[zoneId] = ratesMap
    }

    val siteRates = mutableMapOf<Any, Map<SpeciesId?, Number?>>()
    mapCsv("$prefix/SiteRates.csv", 1) { cols ->
      val ratesMap = mutableMapOf<SpeciesId?, Number?>()

      for ((index, speciesPair) in speciesIds.withIndex()) {
        val rateStr = cols[index + 2].takeIf { it.isNotEmpty() }
        val rate = rateStr?.removeSuffix("%")?.toDoubleOrNull()
        if (rate != null) {
          ratesMap[speciesPair.second] = rate
        }
      }
      cols[1]
          .takeIf { it.isNotEmpty() }
          ?.let { ratesMap[null] = it.removeSuffix("%").toDoubleOrNull() }
      siteRates[inserted.plantingSiteId] = ratesMap
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

  /**
   * Asserts that the survival rates for each level of the planting site match the expected.
   *
   * The expected survival rates are specified as a list of maps, one for each level of the site, in
   * the order of Plot, Subzone, Zone, Site. Within the maps, a null SpeciesId represents the total
   * survival rate for that area, e.g. the Plot Survival Rate, across all species in that plot.
   */
  private fun assertSurvivalRates(
      expected: List<Map<Any, Map<SpeciesId?, Number?>>>,
      message: String,
  ) {
    val idFields = listOf("Plot", "Subzone", "Zone", "Site")
    val actualList = fetchSurvivalRates(inserted.plantingSiteId)

    assertAll(
        expected.mapIndexed { index, expectedByIdMap ->
          {
            val level = idFields[index]
            val actualByIdMap = actualList[index]

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
                      expectedSpeciesMap.all { (speciesId, _) ->
                        actualSpeciesMap.containsKey(speciesId)
                      }
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
    )
  }

  private fun fetchSurvivalRates(
      plantingSiteId: PlantingSiteId
  ): List<Map<Any, Map<SpeciesId?, Int?>>> {
    val siteResults = resultsStore.fetchByPlantingSiteId(plantingSiteId, limit = 1)[0]

    val siteMap = mutableMapOf<SpeciesId?, Int?>()
    val allZonesMap = mutableMapOf<Any, Map<SpeciesId?, Int?>>()
    val allSubzonesMap = mutableMapOf<Any, Map<SpeciesId?, Int?>>()
    val allPlotsMap = mutableMapOf<Any, Map<SpeciesId?, Int?>>()
    siteResults.species
        .filter { it.certainty == RecordedSpeciesCertainty.Known }
        .forEach { species -> siteMap[species.speciesId] = species.survivalRate }
    siteResults.survivalRate?.let { siteMap[null] = it }

    siteResults.plantingZones.forEach { zone ->
      val zoneMap = mutableMapOf<SpeciesId?, Int?>()
      zone.species
          .filter { it.certainty == RecordedSpeciesCertainty.Known }
          .forEach { species -> zoneMap[species.speciesId] = species.survivalRate }
      zone.survivalRate?.let { zoneMap[null] = it }

      zone.plantingSubzones.forEach { subzone ->
        val subzoneMap = mutableMapOf<SpeciesId?, Int?>()
        subzone.species
            .filter { it.certainty == RecordedSpeciesCertainty.Known }
            .forEach { species -> subzoneMap[species.speciesId] = species.survivalRate }
        subzone.survivalRate?.let { subzoneMap[null] = it }

        subzone.monitoringPlots.forEach { plot ->
          val plotMap = mutableMapOf<SpeciesId?, Int?>()
          plot.species
              .filter { it.certainty == RecordedSpeciesCertainty.Known }
              .forEach { species -> plotMap[species.speciesId] = species.survivalRate }
          plot.survivalRate?.let { plotMap[null] = it }

          if (plotMap.isNotEmpty()) {
            allPlotsMap[plot.monitoringPlotId] = plotMap
          }
        }

        if (subzoneMap.isNotEmpty()) {
          allSubzonesMap[subzone.plantingSubzoneId!!] = subzoneMap
        }
      }

      if (zoneMap.isNotEmpty()) {
        allZonesMap[zone.plantingZoneId!!] = zoneMap
      }
    }

    return listOf(allPlotsMap, allSubzonesMap, allZonesMap, mapOf(plantingSiteId to siteMap))
  }
}
