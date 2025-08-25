package com.terraformation.backend.tracking.db.observationStore

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
  fun `survival rate is calculated for a t0 plot by observation`() {
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
  fun `survival rate is calculated for a t0 plot by species density`() {
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
}
