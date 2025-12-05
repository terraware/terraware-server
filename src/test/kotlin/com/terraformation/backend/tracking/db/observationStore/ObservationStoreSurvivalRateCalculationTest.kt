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
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_T0_TEMP_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.ObservationScenarioTest
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.util.toPlantsPerHectare
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.math.roundToInt
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
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
    plotId = insertMonitoringPlot(permanentIndex = 1)
    observationId = insertObservation()
    insertObservationRequestedSubzone()
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
        SurvivalRates(
            mapOf(plotId to mapOf(speciesId to null)),
            mapOf(subzoneId to mapOf(speciesId to null)),
            mapOf(zoneId to mapOf(speciesId to null)),
            mapOf(plantingSiteId to mapOf(speciesId to null)),
        ),
        "All survival rates should be null",
    )
  }

  @Test
  fun `survival rate is 0 if plot density is 0`() {
    val speciesId = insertSpecies()
    insertPlotT0Density(plotDensity = BigDecimal.ZERO)
    val plotId2 = insertMonitoringPlot(permanentIndex = 2)
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)
    insertPlotT0Density(plotDensity = BigDecimal.ZERO)
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
        SurvivalRates(
            mapOf(
                plotId to mapOf(speciesId to BigDecimal.ZERO),
                plotId2 to mapOf(speciesId to BigDecimal.ZERO),
            ),
            mapOf(subzoneId to mapOf(speciesId to BigDecimal.ZERO)),
            mapOf(zoneId to mapOf(speciesId to BigDecimal.ZERO)),
            mapOf(plantingSiteId to mapOf(speciesId to BigDecimal.ZERO)),
        ),
        "All survival rates should be 0",
    )

    // ensure that updating rates also works correctly with 0 for density
    observationStore.completePlot(
        observationId,
        plotId2,
        emptySet(),
        "Notes",
        observedTime,
        recordedPlants,
    )

    assertSurvivalRates(
        SurvivalRates(
            mapOf(
                plotId to mapOf(speciesId to BigDecimal.ZERO),
                plotId2 to mapOf(speciesId to BigDecimal.ZERO),
            ),
            mapOf(subzoneId to mapOf(speciesId to BigDecimal.ZERO)),
            mapOf(zoneId to mapOf(speciesId to BigDecimal.ZERO)),
            mapOf(plantingSiteId to mapOf(speciesId to BigDecimal.ZERO)),
        ),
        "Updated rates should be 0",
    )
  }

  @Test
  fun `survival rate is null if plot is no longer permanent`() {
    val observation2 = insertObservation()
    insertObservationPlot(claimedBy = user.userId, isPermanent = false)

    val speciesId = insertSpecies()
    insertPlotT0Density(plotDensity = BigDecimal.TEN.toPlantsPerHectare())
    val recordedPlants =
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live,
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
    observationStore.completePlot(
        observation2,
        plotId,
        emptySet(),
        "Notes",
        observedTime.plusSeconds(30),
        recordedPlants,
    )

    assertSurvivalRates(
        SurvivalRates(
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
        SurvivalRates(
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
    val plot2 = insertMonitoringPlot(permanentIndex = 3)
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
        )

    assertSurvivalRates(
        SurvivalRates(
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
        SurvivalRates(
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
    val plantingSiteId = insertPlantingSite(survivalRateIncludesTempPlots = true, x = 0, y = 0)
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
            null to 100.0 * (10 + 15) / (15 + 30),
        )
    assertSurvivalRates(
        SurvivalRates(
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
            null to 100.0 * (4 + 9) / (15 + 30),
        )
    val survivalRates1And2: Map<SpeciesId?, Double?> =
        mapOf(
            speciesId1 to (100.0 * (10 + 4) / (15 + 15)),
            speciesId2 to (100.0 * (15 + 9) / (30 + 30)),
            null to 100.0 * (10 + 4 + 15 + 9) / (15 + 15 + 30 + 30),
        )
    assertSurvivalRates(
        SurvivalRates(
            // excludes plot1 because it's in a different observation
            mapOf(tempPlot2 to plot2SurvivalRates),
            mapOf(subzone2 to plot2SurvivalRates),
            // but include plot1 at the zone and site level
            mapOf(zoneId to survivalRates1And2),
            mapOf(plantingSiteId to survivalRates1And2),
        ),
        "Plots 1 and 2 survival rates",
    )
  }

  @Test
  fun `survival rate is recalculated for all plots in a zone correctly after geometry and t0 changes`() {
    // includes plots that are no longer part of their subzones
    with(PLANTING_SITES) {
      dslContext
          .update(this)
          .set(SURVIVAL_RATE_INCLUDES_TEMP_PLOTS, true)
          .where(ID.eq(plantingSiteId))
          .execute()
    }
    val speciesId = insertSpecies()
    insertPlotT0Density(plotDensity = BigDecimal.valueOf(10).toPlantsPerHectare())

    val permanentPlotRemoved = insertMonitoringPlot(permanentIndex = 2)
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)
    insertPlotT0Density(plotDensity = BigDecimal.valueOf(5).toPlantsPerHectare())
    val tempPlot = insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId, isPermanent = false)
    val tempPlotRemoved = insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId, isPermanent = false)
    insertPlantingZoneT0TempDensity(zoneDensity = BigDecimal.valueOf(20).toPlantsPerHectare())

    val obs1Plots = listOf(plotId, permanentPlotRemoved, tempPlot, tempPlotRemoved)
    val obs2Plots = listOf(plotId, tempPlot)
    val removedPlots = listOf(permanentPlotRemoved, tempPlotRemoved)

    val plantsList =
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

    obs1Plots.forEach {
      observationStore.completePlot(
          observationId,
          it,
          emptySet(),
          "Notes1",
          observedTime,
          plantsList,
      )
    }

    val permPlot1Rates = mapOf(speciesId to 100.0 * 1 / 10, null to 100.0 * 1 / 10)
    val permPlot2Rates = mapOf(speciesId to 100.0 * 1 / 5, null to 100.0 * 1 / 5)
    val tempPlotRates = mapOf(speciesId to 100.0 * 1 / 20, null to 100.0 * 1 / 20)
    val allPlotRates = mapOf(speciesId to 100.0 * 4 / 55, null to 100.0 * 4 / 55)
    assertSurvivalRates(
        SurvivalRates(
            mapOf(
                plotId to permPlot1Rates,
                permanentPlotRemoved to permPlot2Rates,
                tempPlot to tempPlotRates,
                tempPlotRemoved to tempPlotRates,
            ),
            mapOf(subzoneId to allPlotRates),
            mapOf(zoneId to allPlotRates),
            mapOf(plantingSiteId to allPlotRates),
        ),
        "Before geometry change, Observation 1",
    )

    // update planting site
    insertPlantingSiteHistory()
    insertPlantingZoneHistory()
    insertPlantingSubzoneHistory()
    dslContext
        .update(MONITORING_PLOTS)
        .set(MONITORING_PLOTS.PERMANENT_INDEX, DSL.castNull(SQLDataType.INTEGER))
        .set(MONITORING_PLOTS.PLANTING_SUBZONE_ID, DSL.castNull(PlantingSubzoneId::class.java))
        .where(MONITORING_PLOTS.ID.`in`(removedPlots))
        .execute()
    val newPlotHistory = insertMonitoringPlotHistory(monitoringPlotId = plotId)
    val newTempPlotHistory = insertMonitoringPlotHistory(monitoringPlotId = tempPlot)
    removedPlots.forEach {
      insertMonitoringPlotHistory(
          monitoringPlotId = it,
          plantingSubzoneId = null,
          plantingSubzoneHistoryId = null,
      )
    }
    // end planting site update

    val observationId2 = insertObservation()
    insertObservationRequestedSubzone()
    insertObservationPlot(
        claimedBy = user.userId,
        monitoringPlotId = plotId,
        isPermanent = true,
        monitoringPlotHistoryId = newPlotHistory,
    )
    insertObservationPlot(
        claimedBy = user.userId,
        monitoringPlotId = tempPlot,
        isPermanent = false,
        monitoringPlotHistoryId = newTempPlotHistory,
    )

    obs2Plots.forEach {
      observationStore.completePlot(
          observationId2,
          it,
          emptySet(),
          "Notes2",
          observedTime.plusSeconds(10),
          plantsList,
      )
    }

    val obs2AllPlotsRates = mapOf(speciesId to 100.0 * 2 / 30, null to 100.0 * 2 / 30)
    assertSurvivalRates(
        SurvivalRates(
            mapOf(plotId to permPlot1Rates, tempPlot to tempPlotRates),
            mapOf(subzoneId to obs2AllPlotsRates),
            mapOf(zoneId to obs2AllPlotsRates),
            mapOf(plantingSiteId to obs2AllPlotsRates),
        ),
        "After geometry change, Observation 2",
    )

    with(PLANTING_ZONE_T0_TEMP_DENSITIES) {
      dslContext
          .update(this)
          .set(ZONE_DENSITY, BigDecimal.valueOf(30).toPlantsPerHectare())
          .where(PLANTING_ZONE_ID.eq(zoneId))
          .and(SPECIES_ID.eq(speciesId))
          .execute()
    }
    observationStore.recalculateSurvivalRates(zoneId)

    val actualResults = resultsStore.fetchByPlantingSiteId(inserted.plantingSiteId, limit = 2)
    val obs1UpdatedResults = ratesObjectFromResults(actualResults[1], plantingSiteId)
    val obs2UpdatedResults = ratesObjectFromResults(actualResults[0], plantingSiteId)

    val tempPlotRatesUpdated = mapOf(speciesId to 100.0 * 1 / 30, null to 100.0 * 1 / 30)
    val obs2AllPlotsRatesUpdated = mapOf(speciesId to 100.0 * 2 / 40, null to 100.0 * 2 / 40)
    val allPlotRatesUpdated = mapOf(speciesId to 100.0 * 4 / 75, null to 100.0 * 4 / 75)
    val obs1Expected =
        SurvivalRates(
            mapOf(
                plotId to permPlot1Rates,
                permanentPlotRemoved to permPlot2Rates,
                tempPlot to tempPlotRatesUpdated,
                tempPlotRemoved to tempPlotRatesUpdated,
            ),
            mapOf(subzoneId to allPlotRatesUpdated),
            mapOf(zoneId to allPlotRatesUpdated),
            mapOf(plantingSiteId to allPlotRatesUpdated),
        )
    val obs2Expected =
        SurvivalRates(
            mapOf(plotId to permPlot1Rates, tempPlot to tempPlotRatesUpdated),
            mapOf(subzoneId to obs2AllPlotsRatesUpdated),
            mapOf(zoneId to obs2AllPlotsRatesUpdated),
            mapOf(plantingSiteId to obs2AllPlotsRatesUpdated),
        )
    assertSurvivalRates(obs1Expected, obs1UpdatedResults, "Observation 1 is updated correctly")
    assertSurvivalRates(obs2Expected, obs2UpdatedResults, "Observation 2 is updated correctly")
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
    runSurvivalRateScenario("/tracking/observation/SurvivalRateT0DensityChange", numSpecies = 2) {
      val species1 = speciesIds["Species 0"]!!
      val plot1 = plotIds["111"]!!

      with(PLOT_T0_DENSITIES) {
        dslContext
            .update(this)
            .set(PLOT_DENSITY, BigDecimal.valueOf(20).toPlantsPerHectare())
            .where(MONITORING_PLOT_ID.eq(plot1))
            .and(SPECIES_ID.eq(species1))
            .execute()
      }
      observationStore.recalculateSurvivalRates(plot1)
    }
  }

  @Test
  fun `survival rate with temp plots is updated when t0 zone density changes`() {
    val newPlantingSiteId =
        insertPlantingSite(x = 0, areaHa = BigDecimal(2500), survivalRateIncludesTempPlots = true)

    every { user.canReadPlantingSite(newPlantingSiteId) } returns true

    runSurvivalRateScenario(
        "/tracking/observation/SurvivalRateT0ZoneDensityChange",
        numSpecies = 2,
    ) {
      val species1 = speciesIds["Species 0"]!!
      val zone1 = zoneIds["Zone1"]!!

      with(PLANTING_ZONE_T0_TEMP_DENSITIES) {
        dslContext
            .update(this)
            .set(ZONE_DENSITY, BigDecimal.valueOf(20).toPlantsPerHectare())
            .where(PLANTING_ZONE_ID.eq(zone1))
            .and(SPECIES_ID.eq(species1))
            .execute()
      }
      observationStore.recalculateSurvivalRates(zone1)
    }
  }

  @Test
  fun `survival rate with geometry change between observations`() {
    val newPlantingSiteId =
        insertPlantingSite(x = 0, areaHa = BigDecimal(2500), survivalRateIncludesTempPlots = false)
    every { user.canReadPlantingSite(newPlantingSiteId) } returns true

    fun updatePlantingSite() {
      // moves plot 112 to subzone2 (from subzone1), adds plot 312 to site, changes plot 212 from
      // permanent to temporary, removes plot 213 from subzone
      // adds all history objects that would occur with this edit
      insertPlantingSiteHistory()
      val zone1 = zoneIds["Zone1"]!!
      val zone2 = zoneIds["Zone2"]!!
      val newZone1History = insertPlantingZoneHistory(plantingZoneId = zone1)
      val newZone2History = insertPlantingZoneHistory(plantingZoneId = zone2)
      val subzone1 = subzoneIds["Subzone1"]!!
      val subzone2 = subzoneIds["Subzone2"]!!
      val subzone3 = subzoneIds["Subzone3"]!!
      val newSubzone1History =
          insertPlantingSubzoneHistory(
              plantingSubzoneId = subzone1,
              plantingZoneHistoryId = newZone1History,
          )
      val newSubzone2History =
          insertPlantingSubzoneHistory(
              plantingSubzoneId = subzone2,
              plantingZoneHistoryId = newZone1History,
          )
      val newSubzone3History =
          insertPlantingSubzoneHistory(
              plantingSubzoneId = subzone3,
              plantingZoneHistoryId = newZone2History,
          )
      plotHistoryIds[plotIds["111"]!!] =
          insertMonitoringPlotHistory(
              monitoringPlotId = plotIds["111"]!!,
              plantingSubzoneId = subzone1,
              plantingSubzoneHistoryId = newSubzone1History,
          )
      val plot112 = plotIds["112"]!!
      dslContext
          .update(MONITORING_PLOTS)
          .set(MONITORING_PLOTS.PLANTING_SUBZONE_ID, subzone2)
          .where(MONITORING_PLOTS.ID.eq(plot112))
          .execute()
      plotHistoryIds[plot112] =
          insertMonitoringPlotHistory(
              monitoringPlotId = plot112,
              plantingSubzoneId = subzone2,
              plantingSubzoneHistoryId = newSubzone2History,
          )
      plotHistoryIds[plotIds["211"]!!] =
          insertMonitoringPlotHistory(
              monitoringPlotId = plotIds["211"]!!,
              plantingSubzoneId = subzone2,
              plantingSubzoneHistoryId = newSubzone2History,
          )
      val plot212 = plotIds["212"]!!
      dslContext
          .update(MONITORING_PLOTS)
          .set(MONITORING_PLOTS.PERMANENT_INDEX, DSL.castNull(SQLDataType.INTEGER))
          .where(MONITORING_PLOTS.ID.eq(plot212))
          .execute()
      plotHistoryIds[plot212] =
          insertMonitoringPlotHistory(
              monitoringPlotId = plot212,
              plantingSubzoneId = subzone2,
              plantingSubzoneHistoryId = newSubzone2History,
          )
      permanentPlotNumbers.remove("212")
      permanentPlotIds.remove(plot212)
      val plot213 = plotIds["213"]!!
      dslContext
          .update(MONITORING_PLOTS)
          .set(MONITORING_PLOTS.PERMANENT_INDEX, DSL.castNull(SQLDataType.INTEGER))
          .set(MONITORING_PLOTS.PLANTING_SUBZONE_ID, DSL.castNull(PlantingSubzoneId::class.java))
          .where(MONITORING_PLOTS.ID.eq(plot213))
          .execute()
      plotHistoryIds[plot213] =
          insertMonitoringPlotHistory(
              monitoringPlotId = plot213,
              plantingSubzoneId = null,
              plantingSubzoneHistoryId = null,
          )
      permanentPlotNumbers.remove("213")
      permanentPlotIds.remove(plot213)
      plotHistoryIds[plotIds["311"]!!] =
          insertMonitoringPlotHistory(
              monitoringPlotId = plotIds["311"]!!,
              plantingSubzoneId = subzone3,
              plantingSubzoneHistoryId = newSubzone3History,
          )

      val newPlotId =
          insertMonitoringPlot(
              insertHistory = false,
              plantingSubzoneId = subzone3,
              plotNumber = 312,
              sizeMeters = 30,
              permanentIndex = 312,
          )
      plotHistoryIds[newPlotId] =
          insertMonitoringPlotHistory(
              plantingSubzoneId = subzone3,
              plantingSubzoneHistoryId = newSubzone3History,
          )
      permanentPlotNumbers.add("312")
      permanentPlotIds.add(newPlotId)
      plotIds["312"] = newPlotId
      val species1 = speciesIds["Species 0"]!!
      val species2 = speciesIds["Species 1"]!!
      insertPlotT0Density(
          speciesId = species1,
          plotDensity = BigDecimal.valueOf(90).toPlantsPerHectare(),
      )
      insertPlotT0Density(
          speciesId = species2,
          plotDensity = BigDecimal.valueOf(100).toPlantsPerHectare(),
      )
    }

    val prefix = "/tracking/observation/SurvivalRateSiteGeometryChange"
    val numSpecies = 2

    runSurvivalRateScenario(
        prefix,
        numSpecies,
    ) {
      updatePlantingSite()

      importObservationsCsv(prefix, numSpecies, 1, Instant.EPOCH.plusSeconds(10), false)
    }

    // ensure that observation1 didn't change
    val observation1Expected = loadExpectedSurvivalRates(prefix, numSpecies)
    val observation1Actual =
        ratesObjectFromResults(
            resultsStore.fetchByPlantingSiteId(inserted.plantingSiteId, limit = 2)[1],
            inserted.plantingSiteId,
        )
    assertSurvivalRates(observation1Expected, observation1Actual, "Observation 1 shouldn't change")
  }

  @Test
  fun `survival rate with geometry change between observations, includes temp`() {
    val newPlantingSiteId =
        insertPlantingSite(x = 0, areaHa = BigDecimal(2500), survivalRateIncludesTempPlots = true)
    every { user.canReadPlantingSite(newPlantingSiteId) } returns true

    fun updatePlantingSite() {
      // change plot 112 to permanent and adds plot 312 to site (also temp)
      // adds all history objects that would occur with this edit
      insertPlantingSiteHistory()
      val zone1 = zoneIds["Zone1"]!!
      val zone2 = zoneIds["Zone2"]!!
      val newZone1History = insertPlantingZoneHistory(plantingZoneId = zone1)
      val newZone2History = insertPlantingZoneHistory(plantingZoneId = zone2)
      val subzone1 = subzoneIds["Subzone1"]!!
      val subzone2 = subzoneIds["Subzone2"]!!
      val subzone3 = subzoneIds["Subzone3"]!!
      val newSubzone1History =
          insertPlantingSubzoneHistory(
              plantingSubzoneId = subzone1,
              plantingZoneHistoryId = newZone1History,
          )
      val newSubzone2History =
          insertPlantingSubzoneHistory(
              plantingSubzoneId = subzone2,
              plantingZoneHistoryId = newZone1History,
          )
      val newSubzone3History =
          insertPlantingSubzoneHistory(
              plantingSubzoneId = subzone3,
              plantingZoneHistoryId = newZone2History,
          )
      plotHistoryIds[plotIds["111"]!!] =
          insertMonitoringPlotHistory(
              monitoringPlotId = plotIds["111"]!!,
              plantingSubzoneId = subzone1,
              plantingSubzoneHistoryId = newSubzone1History,
          )
      val plot2 = plotIds["112"]!!
      dslContext
          .update(MONITORING_PLOTS)
          .set(MONITORING_PLOTS.PERMANENT_INDEX, 112)
          .where(MONITORING_PLOTS.ID.eq(plot2))
          .execute()
      plotHistoryIds[plot2] =
          insertMonitoringPlotHistory(
              monitoringPlotId = plot2,
              plantingSubzoneId = subzone1,
              plantingSubzoneHistoryId = newSubzone1History,
          )
      permanentPlotNumbers.add("112")
      permanentPlotIds.add(plot2)
      val species1 = speciesIds["Species 0"]!!
      val species2 = speciesIds["Species 1"]!!
      insertPlotT0Density(
          monitoringPlotId = plot2,
          speciesId = species1,
          plotDensity = BigDecimal.valueOf(30).toPlantsPerHectare(),
      )
      insertPlotT0Density(
          monitoringPlotId = plot2,
          speciesId = species2,
          plotDensity = BigDecimal.valueOf(40).toPlantsPerHectare(),
      )
      plotHistoryIds[plotIds["211"]!!] =
          insertMonitoringPlotHistory(
              monitoringPlotId = plotIds["211"]!!,
              plantingSubzoneId = subzone2,
              plantingSubzoneHistoryId = newSubzone2History,
          )
      val plot212 = plotIds["212"]!!
      dslContext
          .update(MONITORING_PLOTS)
          .set(MONITORING_PLOTS.PLANTING_SUBZONE_ID, DSL.castNull(PlantingSubzoneId::class.java))
          .where(MONITORING_PLOTS.ID.eq(plot212))
          .execute()
      plotHistoryIds[plot212] =
          insertMonitoringPlotHistory(
              monitoringPlotId = plot212,
              plantingSubzoneId = null,
              plantingSubzoneHistoryId = null,
          )
      plotHistoryIds[plotIds["311"]!!] =
          insertMonitoringPlotHistory(
              monitoringPlotId = plotIds["311"]!!,
              plantingSubzoneId = subzone3,
              plantingSubzoneHistoryId = newSubzone3History,
          )

      val newPlotId =
          insertMonitoringPlot(
              insertHistory = false,
              plantingSubzoneId = subzone3,
              plotNumber = 312,
              sizeMeters = 30,
              permanentIndex = null,
          )
      plotHistoryIds[newPlotId] =
          insertMonitoringPlotHistory(
              plantingSubzoneId = subzone3,
              plantingSubzoneHistoryId = newSubzone3History,
          )
      plotIds["312"] = newPlotId
    }

    val prefix = "/tracking/observation/SurvivalRateSiteGeometryChangeTemp"
    val numSpecies = 2

    runSurvivalRateScenario(
        prefix,
        numSpecies,
    ) {
      updatePlantingSite()

      importObservationsCsv(prefix, numSpecies, 1, Instant.EPOCH.plusSeconds(10), false)
    }

    // ensure that observation1 didn't change
    val observation1Expected = loadExpectedSurvivalRates(prefix, numSpecies)
    val observation1Actual =
        ratesObjectFromResults(
            resultsStore.fetchByPlantingSiteId(inserted.plantingSiteId, limit = 2)[1],
            inserted.plantingSiteId,
        )
    assertSurvivalRates(observation1Expected, observation1Actual, "Observation 1 shouldn't change")
  }

  private var lastCoord: Int = 1

  private fun runSurvivalRateScenario(
      prefix: String,
      numSpecies: Int,
      changeFunction: (() -> Unit)? = null,
  ) {
    importSiteFromCsvFile(prefix, 30)
    observationId = insertObservation()
    importT0DensitiesCsv(prefix)
    importT0ZoneDensitiesCsv(prefix)
    importObservationsCsv(prefix, numSpecies, 0, Instant.EPOCH, false)

    val expectedRates = loadExpectedSurvivalRates(prefix, numSpecies)
    assertSurvivalRates(expectedRates, "$prefix - Survival Rate")

    if (changeFunction == null) return

    changeFunction()

    val ratesAfterChange = loadExpectedSurvivalRates("$prefix/AfterUpdate", numSpecies)
    assertSurvivalRates(ratesAfterChange, "$prefix - Survival Rate After Change")
  }

  private fun loadExpectedSurvivalRates(
      prefix: String,
      numSpecies: Int,
  ): SurvivalRates {
    val speciesIds = (0 until numSpecies).map { "Species $it" to this.speciesIds["Species $it"]!! }

    val plotRates = mutableMapOf<Any, Map<SpeciesId?, Number?>>()
    mapCsv("$prefix/PlotRates.csv", 1) { cols ->
      val plotName = cols[0]
      val plotId = plotIds[plotName] ?: throw IllegalArgumentException("Plot $plotName not found")
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
      val subzoneId =
          subzoneIds[subzoneName]
              ?: throw IllegalArgumentException("Subzone $subzoneName not found")
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
      val zoneId = zoneIds[zoneName] ?: throw IllegalArgumentException("Zone $zoneName not found")
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

    return SurvivalRates(plotRates, subzoneRates, zoneRates, siteRates)
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
      expected: SurvivalRates,
      message: String,
  ) {
    val actual = fetchSurvivalRates(inserted.plantingSiteId)
    assertSurvivalRates(expected, actual, message)
  }

  private fun assertSurvivalRates(
      expected: SurvivalRates,
      actual: SurvivalRates,
      message: String,
  ) {
    assertAll(
        assertSurvivalRates(expected.plotRates, actual.plotRates, "Plot", message),
        assertSurvivalRates(expected.subzoneRates, actual.subzoneRates, "Subzone", message),
        assertSurvivalRates(expected.zoneRates, actual.zoneRates, "Zone", message),
        assertSurvivalRates(expected.siteRates, actual.siteRates, "Site", message),
    )
  }

  private fun assertSurvivalRates(
      expected: Map<Any, Map<SpeciesId?, Number?>>,
      actual: Map<Any, Map<SpeciesId?, Number?>>,
      level: String,
      message: String,
  ): () -> Unit = {
    val allIdsMatch =
        actual.all { (id, actualSpeciesMap) ->
          val expectedSpeciesMap = expected[id] ?: return@all false

          actualSpeciesMap.all { (speciesId, actualRate) ->
            val expectedRate = expectedSpeciesMap[speciesId]
            when {
              expectedRate == null -> actualRate == null
              actualRate == null -> false
              expectedRate is Double -> expectedRate.roundToInt() == actualRate
              else -> expectedRate == actualRate
            }
          } && expectedSpeciesMap.all { (speciesId, _) -> actualSpeciesMap.containsKey(speciesId) }
        } && expected.all { (id, _) -> actual.containsKey(id) }

    if (!allIdsMatch) {
      val expectedString =
          expected.entries.joinToString("\n") { (id, speciesMap) ->
            "$level $id: {${speciesMap.entries.joinToString(", ") { "SpeciesId: ${it.key} -> SurvivalRate: ${it.value?.let { value -> if (value is Double) value.roundToInt() else value }}" }}}"
          }
      val actualString =
          actual.entries.joinToString("\n") { (id, speciesMap) ->
            "$level $id: {${speciesMap.entries.joinToString(", ") { "SpeciesId: ${it.key} -> SurvivalRate: ${it.value}" }}}"
          }
      assertEquals(expectedString, actualString, "$message - $level")
    }
  }

  private fun fetchSurvivalRates(plantingSiteId: PlantingSiteId): SurvivalRates {
    val siteResults = resultsStore.fetchByPlantingSiteId(plantingSiteId, limit = 1)[0]

    return ratesObjectFromResults(siteResults, plantingSiteId)
  }

  private fun ratesObjectFromResults(
      siteResults: ObservationResultsModel,
      plantingSiteId: PlantingSiteId,
  ): SurvivalRates {
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

    return SurvivalRates(allPlotsMap, allSubzonesMap, allZonesMap, mapOf(plantingSiteId to siteMap))
  }

  data class SurvivalRates(
      val plotRates: Map<Any, Map<SpeciesId?, Number?>>,
      val subzoneRates: Map<Any, Map<SpeciesId?, Number?>>,
      val zoneRates: Map<Any, Map<SpeciesId?, Number?>>,
      val siteRates: Map<Any, Map<SpeciesId?, Number?>>,
  )
}
