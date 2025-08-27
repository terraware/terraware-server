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
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
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
  fun `survival rate is calculated for a plot using plot density`() {
    val speciesId1 = insertSpecies()
    insertPlotT0Density(plotDensity = 11)

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
    store.completePlot(observationId, plotId, emptySet(), "Notes", observedTime, recordedPlants)

    helper.assertTotals(
        setOf(
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
            baseSubzoneTotals.copy(
                speciesId = speciesId1,
                totalLive = 1,
                permanentLive = 1,
                survivalRate = 100 * 1 / 11,
            ),
            baseSubzoneTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
            ),
            baseZoneTotals.copy(
                speciesId = speciesId1,
                totalLive = 1,
                permanentLive = 1,
                survivalRate = 100 * 1 / 11,
            ),
            baseZoneTotals.copy(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                totalDead = 1,
                mortalityRate = 100,
                cumulativeDead = 1,
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
        )
    )
  }

  @Test
  fun `survival rate is calculated for a subzone using all plots`() {
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()
    val speciesId3 = insertSpecies()
    insertPlotT0Density(speciesId = speciesId1, plotDensity = 15)
    insertPlotT0Density(speciesId = speciesId2, plotDensity = 23)
    insertPlotT0Density(speciesId = speciesId3, plotDensity = 31)

    // plot with no density for species 3
    val plot2 = insertMonitoringPlot()
    insertObservationPlot(
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        isPermanent = true,
    )
    insertPlotT0Density(speciesId = speciesId1, plotDensity = 9)
    insertPlotT0Density(speciesId = speciesId2, plotDensity = 19)

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

    val plot1Totals =
        setOf(
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

    helper.assertTotals(
        plot1Totals +
            setOf(
                baseSubzoneTotals.copy(
                    speciesId = speciesId1,
                    totalLive = 9,
                    totalDead = 1,
                    mortalityRate = 10,
                    cumulativeDead = 1,
                    permanentLive = 9,
                    survivalRate = 100 * 9 / (15 + 9),
                ),
                baseSubzoneTotals.copy(
                    speciesId = speciesId2,
                    totalLive = 18,
                    totalDead = 1,
                    mortalityRate = 5,
                    cumulativeDead = 1,
                    permanentLive = 18,
                    survivalRate = 100 * 18 / (19 + 23),
                ),
                baseSubzoneTotals.copy(
                    speciesId = speciesId3,
                    totalLive = 27,
                    totalDead = 2,
                    mortalityRate = 7,
                    cumulativeDead = 2,
                    permanentLive = 27,
                    survivalRate = 100 * 27 / 31,
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
                    totalLive = 9,
                    totalDead = 1,
                    mortalityRate = 10,
                    cumulativeDead = 1,
                    permanentLive = 9,
                    survivalRate = 100 * 9 / (15 + 9),
                ),
                baseZoneTotals.copy(
                    speciesId = speciesId2,
                    totalLive = 18,
                    totalDead = 1,
                    mortalityRate = 5,
                    cumulativeDead = 1,
                    permanentLive = 18,
                    survivalRate = 100 * 18 / (19 + 23),
                ),
                baseZoneTotals.copy(
                    speciesId = speciesId3,
                    totalLive = 27,
                    totalDead = 2,
                    mortalityRate = 7,
                    cumulativeDead = 2,
                    permanentLive = 27,
                    survivalRate = 100 * 27 / 31,
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
                    totalLive = 9,
                    totalDead = 1,
                    mortalityRate = 10,
                    cumulativeDead = 1,
                    permanentLive = 9,
                ),
                baseSiteTotals.copy(
                    speciesId = speciesId2,
                    totalLive = 18,
                    totalDead = 1,
                    mortalityRate = 5,
                    cumulativeDead = 1,
                    permanentLive = 18,
                ),
                baseSiteTotals.copy(
                    speciesId = speciesId3,
                    totalLive = 27,
                    totalDead = 2,
                    mortalityRate = 7,
                    cumulativeDead = 2,
                    permanentLive = 27,
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
    store.completePlot(observationId, plot2, emptySet(), "Notes2", observedTime, plot2Plants)

    val plot2Totals =
        setOf(
            baseSpeciesTotals.copy(
                monitoringPlotId = plot2,
                speciesId = speciesId1,
                totalLive = 10,
                totalDead = 0,
                mortalityRate = 0,
                cumulativeDead = 0,
                permanentLive = 10,
                survivalRate = 100 * 10 / 9,
            ),
            baseSpeciesTotals.copy(
                monitoringPlotId = plot2,
                speciesId = speciesId2,
                totalLive = 17,
                totalDead = 1,
                mortalityRate = 6,
                cumulativeDead = 1,
                permanentLive = 17,
                survivalRate = 100 * 17 / 19,
            ),
            baseSpeciesTotals.copy(
                monitoringPlotId = plot2,
                speciesId = speciesId3,
                totalLive = 25,
                totalDead = 1,
                mortalityRate = 4,
                cumulativeDead = 1,
                permanentLive = 25,
                // no survival rate because no t0 data
            ),
            baseSpeciesTotals.copy(
                monitoringPlotId = plot2,
                certaintyId = RecordedSpeciesCertainty.Other,
                speciesName = "Who knows",
                totalExisting = 1,
            ),
        )

    helper.assertTotals(
        plot1Totals +
            plot2Totals +
            setOf(
                baseSubzoneTotals.copy(
                    speciesId = speciesId1,
                    totalLive = 9 + 10,
                    totalDead = 1,
                    mortalityRate = 100 * 1 / 19,
                    cumulativeDead = 1,
                    permanentLive = 9 + 10,
                    survivalRate = 100 * (9 + 10) / (10 + 5 + 9),
                ),
                baseSubzoneTotals.copy(
                    speciesId = speciesId2,
                    totalLive = 18 + 17,
                    totalDead = 2,
                    mortalityRate = 5,
                    cumulativeDead = 2,
                    permanentLive = 18 + 17,
                    survivalRate = 100 * (17 + 18) / (23 + 19),
                ),
                baseSubzoneTotals.copy(
                    speciesId = speciesId3,
                    totalLive = 27 + 25,
                    totalDead = 3,
                    mortalityRate = 5,
                    cumulativeDead = 3,
                    permanentLive = 27 + 25,
                    survivalRate = 100 * (27 + 25) / 31,
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
                    totalExisting = 2,
                ),
                baseZoneTotals.copy(
                    speciesId = speciesId1,
                    totalLive = 9 + 10,
                    totalDead = 1,
                    mortalityRate = 5,
                    cumulativeDead = 1,
                    permanentLive = 9 + 10,
                    survivalRate = 100 * (9 + 10) / (10 + 5 + 9),
                ),
                baseZoneTotals.copy(
                    speciesId = speciesId2,
                    totalLive = 18 + 17,
                    totalDead = 2,
                    mortalityRate = 5,
                    cumulativeDead = 2,
                    permanentLive = 18 + 17,
                    survivalRate = 100 * (17 + 18) / (23 + 19),
                ),
                baseZoneTotals.copy(
                    speciesId = speciesId3,
                    totalLive = 27 + 25,
                    totalDead = 3,
                    mortalityRate = 5,
                    cumulativeDead = 3,
                    permanentLive = 27 + 25,
                    survivalRate = 100 * (27 + 25) / 31,
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
                    totalExisting = 2,
                ),
                baseSiteTotals.copy(
                    speciesId = speciesId1,
                    totalLive = 9 + 10,
                    totalDead = 1,
                    mortalityRate = 5,
                    cumulativeDead = 1,
                    permanentLive = 9 + 10,
                ),
                baseSiteTotals.copy(
                    speciesId = speciesId2,
                    totalLive = 18 + 17,
                    totalDead = 2,
                    mortalityRate = 5,
                    cumulativeDead = 2,
                    permanentLive = 18 + 17,
                ),
                baseSiteTotals.copy(
                    speciesId = speciesId3,
                    totalLive = 27 + 25,
                    totalDead = 3,
                    mortalityRate = 5,
                    cumulativeDead = 3,
                    permanentLive = 27 + 25,
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
                    totalExisting = 2,
                ),
            )
    )
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
