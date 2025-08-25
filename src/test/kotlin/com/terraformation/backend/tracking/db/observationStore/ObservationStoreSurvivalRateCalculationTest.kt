package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSiteSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSubzoneSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedZoneSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.point
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class ObservationStoreSurvivalRateCalculationTest : BaseObservationStoreTest() {
  private lateinit var observationId: ObservationId
  private lateinit var plotId: MonitoringPlotId
  private val observedTime = Instant.ofEpochSecond(1)

  private lateinit var baseSpeciesTotals: ObservedPlotSpeciesTotalsRow
  private lateinit var baseSubzoneTotals: ObservedSubzoneSpeciesTotalsRow
  private lateinit var baseZoneTotals: ObservedZoneSpeciesTotalsRow
  private lateinit var baseSiteTotals: ObservedSiteSpeciesTotalsRow

  @BeforeEach
  fun insertDetailedSiteAndObservation() {
    insertPlantingZone()
    insertPlantingSubzone()
    plotId = insertMonitoringPlot()
    observationId = insertObservation()
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)

    baseSpeciesTotals =
        ObservedPlotSpeciesTotalsRow(
            observationId = observationId,
            monitoringPlotId = plotId,
            certaintyId = RecordedSpeciesCertainty.Known,
            totalLive = 0,
            totalDead = 0,
            totalExisting = 0,
            mortalityRate = 0,
            cumulativeDead = 0,
            permanentLive = 0,
        )

    baseSubzoneTotals =
        ObservedSubzoneSpeciesTotalsRow(
            observationId = observationId,
            plantingSubzoneId = inserted.plantingSubzoneId,
            certaintyId = RecordedSpeciesCertainty.Known,
            totalLive = 0,
            totalDead = 0,
            totalExisting = 0,
            mortalityRate = 0,
            cumulativeDead = 0,
            permanentLive = 0,
        )

    baseZoneTotals =
        ObservedZoneSpeciesTotalsRow(
            observationId = observationId,
            plantingZoneId = inserted.plantingZoneId,
            certaintyId = RecordedSpeciesCertainty.Known,
            totalLive = 0,
            totalDead = 0,
            totalExisting = 0,
            mortalityRate = 0,
            cumulativeDead = 0,
            permanentLive = 0,
        )

    baseSiteTotals =
        ObservedSiteSpeciesTotalsRow(
            observationId = observationId,
            plantingSiteId = inserted.plantingSiteId,
            certaintyId = RecordedSpeciesCertainty.Known,
            totalLive = 0,
            totalDead = 0,
            totalExisting = 0,
            mortalityRate = 0,
            cumulativeDead = 0,
            permanentLive = 0,
        )
  }

  @Test
  fun `does not calculate survival rate if no t0 plot`() {
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
    store.completePlot(observationId, plotId, emptySet(), "Notes", observedTime, recordedPlants)

    helper.assertTotals(
        setOf(
            baseSpeciesTotals.copy(
                speciesId = speciesId,
                totalLive = 1,
                permanentLive = 1,
            ),
            baseSpeciesTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
            ),
            baseSpeciesTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Other,
                speciesName = "Who knows",
                totalExisting = 1,
            ),
            baseSubzoneTotals.copy(
                speciesId = speciesId,
                totalLive = 1,
                permanentLive = 1,
            ),
            baseSubzoneTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
            ),
            baseSubzoneTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Other,
                speciesName = "Who knows",
                totalExisting = 1,
            ),
            baseZoneTotals.copy(
                speciesId = speciesId,
                totalLive = 1,
                permanentLive = 1,
            ),
            baseZoneTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
            ),
            baseZoneTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Other,
                speciesName = "Who knows",
                totalExisting = 1,
            ),
            baseSiteTotals.copy(
                speciesId = speciesId,
                totalLive = 1,
                permanentLive = 1,
            ),
            baseSiteTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
            ),
            baseSiteTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Other,
                speciesName = "Who knows",
                totalExisting = 1,
            ),
        )
    )
  }

  @Test
  fun `survival rate is calculated for a plot using a t0 observation and ignores estimated density`() {
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()
    val t0Observation = insertObservation()
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)
    insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 5, totalDead = 6)
    insertObservedPlotSpeciesTotals(speciesId = speciesId2, totalLive = 2, totalDead = 4)
    insertObservedPlotSpeciesTotals(
        certainty = RecordedSpeciesCertainty.Unknown,
        totalLive = 1,
        totalDead = 2,
    )
    // should be ignored:
    insertT0Plot(speciesId = speciesId1, estimatedPlantingDensity = BigDecimal.valueOf(150))
    insertT0Plot(observationId = t0Observation)

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
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = point(3),
                speciesName = "Who knows",
                statusId = RecordedPlantStatus.Existing,
            ),
        )
    store.completePlot(observationId, plotId, emptySet(), "Notes", observedTime, recordedPlants)

    helper.assertTotals(
        setOf(
            // t0 totals
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                speciesId = speciesId1,
                totalLive = 5,
                totalDead = 6,
            ),
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                speciesId = speciesId2,
                totalLive = 2,
                totalDead = 4,
            ),
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalLive = 1,
                totalDead = 2,
            ),
            // observation totals
            baseSpeciesTotals.copy(
                speciesId = speciesId1,
                totalLive = 1,
                permanentLive = 1,
                survivalRate = 9,
            ),
            baseSpeciesTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
            ),
            baseSpeciesTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Other,
                speciesName = "Who knows",
                totalExisting = 1,
            ),
            baseSubzoneTotals.copy(
                speciesId = speciesId1,
                totalLive = 1,
                permanentLive = 1,
            ),
            baseSubzoneTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
            ),
            baseSubzoneTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Other,
                speciesName = "Who knows",
                totalExisting = 1,
            ),
            baseZoneTotals.copy(
                speciesId = speciesId1,
                totalLive = 1,
                permanentLive = 1,
            ),
            baseZoneTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
            ),
            baseZoneTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Other,
                speciesName = "Who knows",
                totalExisting = 1,
            ),
            baseSiteTotals.copy(
                speciesId = speciesId1,
                totalLive = 1,
                permanentLive = 1,
            ),
            baseSiteTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
            ),
            baseSiteTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Other,
                speciesName = "Who knows",
                totalExisting = 1,
            ),
        )
    )
  }

  @Test
  fun `survival rate is calculated for a plot using estimated species density`() {
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()
    val t0Observation = insertObservation()
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)
    insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 10, totalDead = 12)
    insertObservedPlotSpeciesTotals(speciesId = speciesId2, totalLive = 2, totalDead = 4)
    insertObservedPlotSpeciesTotals(
        certainty = RecordedSpeciesCertainty.Unknown,
        totalLive = 1,
        totalDead = 2,
    )
    insertT0Plot(speciesId = speciesId1, estimatedPlantingDensity = BigDecimal.TWO)

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
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = point(3),
                speciesName = "Who knows",
                statusId = RecordedPlantStatus.Existing,
            ),
        )
    store.completePlot(observationId, plotId, emptySet(), "Notes", observedTime, recordedPlants)

    helper.assertTotals(
        setOf(
            // t0 totals
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                speciesId = speciesId1,
                totalLive = 10,
                totalDead = 12,
            ),
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                speciesId = speciesId2,
                totalLive = 2,
                totalDead = 4,
            ),
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalLive = 1,
                totalDead = 2,
            ),
            // observation totals
            baseSpeciesTotals.copy(
                speciesId = speciesId1,
                totalLive = 1,
                permanentLive = 1,
                survivalRate = 556,
            ),
            baseSpeciesTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
            ),
            baseSpeciesTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Other,
                speciesName = "Who knows",
                totalExisting = 1,
            ),
            baseSubzoneTotals.copy(
                speciesId = speciesId1,
                totalLive = 1,
                permanentLive = 1,
            ),
            baseSubzoneTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
            ),
            baseSubzoneTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Other,
                speciesName = "Who knows",
                totalExisting = 1,
            ),
            baseZoneTotals.copy(
                speciesId = speciesId1,
                totalLive = 1,
                permanentLive = 1,
            ),
            baseZoneTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
            ),
            baseZoneTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Other,
                speciesName = "Who knows",
                totalExisting = 1,
            ),
            baseSiteTotals.copy(
                speciesId = speciesId1,
                totalLive = 1,
                permanentLive = 1,
            ),
            baseSiteTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
            ),
            baseSiteTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Other,
                speciesName = "Who knows",
                totalExisting = 1,
            ),
        )
    )
  }

  @Test
  @Disabled("WIP")
  fun `survival rate is calculated for a subzone using all plots`() {
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()
    val speciesId3 = insertSpecies()
    val t0Observation = insertObservation()
    // plot with t0 observation
    insertObservationPlot(
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        isPermanent = true,
    )
    insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 10, totalDead = 5)
    insertObservedPlotSpeciesTotals(speciesId = speciesId2, totalLive = 20, totalDead = 3)
    insertObservedPlotSpeciesTotals(speciesId = speciesId3, totalLive = 30, totalDead = 1)
    insertObservedPlotSpeciesTotals(
        certainty = RecordedSpeciesCertainty.Unknown,
        totalLive = 1,
        totalDead = 2,
    )
    // plot with estimated density
    val plot2 = insertMonitoringPlot()
    insertObservationPlot(
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        isPermanent = true,
    )
    insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 9, totalDead = 0)
    insertObservedPlotSpeciesTotals(speciesId = speciesId2, totalLive = 18, totalDead = 2)
    insertObservedPlotSpeciesTotals(speciesId = speciesId3, totalLive = 25, totalDead = 3)
    insertObservedPlotSpeciesTotals(
        certainty = RecordedSpeciesCertainty.Unknown,
        totalLive = 2,
        totalDead = 4,
    )
    // plot with no t0 data
    val plot3 = insertMonitoringPlot()
    insertObservationPlot(
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        isPermanent = true,
    )
    insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 9, totalDead = 0)
    insertObservedPlotSpeciesTotals(speciesId = speciesId2, totalLive = 19, totalDead = 0)
    insertObservedPlotSpeciesTotals(speciesId = speciesId3, totalLive = 23, totalDead = 2)
    insertObservedPlotSpeciesTotals(
        certainty = RecordedSpeciesCertainty.Unknown,
        totalLive = 4,
        totalDead = 5,
    )
    insertT0Plot(
        monitoringPlotId = plotId,
        observationId = t0Observation,
    )
    insertT0Plot(
        monitoringPlotId = plotId,
        speciesId = speciesId1,
        estimatedPlantingDensity = BigDecimal.TWO, // should get overwritten
    )

    insertT0Plot(
        monitoringPlotId = plot2,
        speciesId = speciesId1,
        estimatedPlantingDensity = BigDecimal.valueOf(100),
    )
    insertT0Plot(
        monitoringPlotId = plot2,
        speciesId = speciesId2,
        estimatedPlantingDensity = BigDecimal.valueOf(200),
    )
    // no t0 plot for species 3 to test calculations of partial data

    val plot1Plants =
        createPlantsRows(
            mapOf(
                speciesId1 to 9,
                speciesId2 to 18,
                speciesId3 to 27,
            ),
            RecordedPlantStatus.Live,
        ) +
            createPlantsRows(
                mapOf(
                    speciesId1 to 1,
                    speciesId2 to 1,
                    speciesId3 to 2,
                ),
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
    store.completePlot(observationId, plotId, emptySet(), "Notes1", observedTime, plot1Plants)

    helper.assertTotals(
        setOf(
            // t0 totals
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                speciesId = speciesId1,
                totalLive = 10,
                totalDead = 5,
            ),
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                speciesId = speciesId2,
                totalLive = 20,
                totalDead = 3,
            ),
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                speciesId = speciesId3,
                totalLive = 30,
                totalDead = 1,
            ),
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                monitoringPlotId = plot2,
                speciesId = speciesId1,
                totalLive = 9,
                totalDead = 0,
            ),
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                monitoringPlotId = plot2,
                speciesId = speciesId2,
                totalLive = 18,
                totalDead = 2,
            ),
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                monitoringPlotId = plot2,
                speciesId = speciesId3,
                totalLive = 25,
                totalDead = 3,
            ),
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                monitoringPlotId = plot3,
                speciesId = speciesId1,
                totalLive = 9,
                totalDead = 0,
            ),
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                monitoringPlotId = plot3,
                speciesId = speciesId2,
                totalLive = 19,
                totalDead = 0,
            ),
            baseSpeciesTotals.copy(
                observationId = t0Observation,
                monitoringPlotId = plot3,
                speciesId = speciesId3,
                totalLive = 23,
                totalDead = 2,
            ),
            baseSpeciesTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                observationId = t0Observation,
                totalLive = 1,
                totalDead = 2,
            ),
            baseSpeciesTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                observationId = t0Observation,
                monitoringPlotId = plot2,
                totalLive = 2,
                totalDead = 4,
            ),
            baseSpeciesTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                observationId = t0Observation,
                monitoringPlotId = plot3,
                totalLive = 4,
                totalDead = 5,
            ),
            // observation totals
            baseSpeciesTotals.copy(
                speciesId = speciesId1,
                totalLive = 9,
                totalDead = 1,
                mortalityRate = 10,
                cumulativeDead = 1,
                permanentLive = 9,
                survivalRate = 100 * 9 / 15,
            ),
            baseSpeciesTotals.copy(
                speciesId = speciesId2,
                totalLive = 18,
                totalDead = 1,
                mortalityRate = 5,
                cumulativeDead = 1,
                permanentLive = 18,
                survivalRate = 100 * 18 / 23,
            ),
            baseSpeciesTotals.copy(
                speciesId = speciesId3,
                totalLive = 27,
                totalDead = 2,
                mortalityRate = 7,
                cumulativeDead = 2,
                permanentLive = 27,
                survivalRate = 100 * 27 / 31,
            ),
            baseSpeciesTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
            ),
            baseSpeciesTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Other,
                speciesName = "Who knows",
                totalExisting = 1,
            ),
        )
    )

    //    val plot2Plants = createPlantsRows(emptyMap(), RecordedPlantStatus.Live)
    //    store.completePlot(observationId, plot2, emptySet(), "Notes2", observedTime, plot2Plants)
    //    helper.assertTotals()
    //
    //    val plot3Plants = createPlantsRows(emptyMap(), RecordedPlantStatus.Live)
    //    store.completePlot(observationId, plot3, emptySet(), "Notes3", observedTime, plot3Plants)
    //    helper.assertTotals()
  }

  private var lastCoord: Int = 1

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
}
