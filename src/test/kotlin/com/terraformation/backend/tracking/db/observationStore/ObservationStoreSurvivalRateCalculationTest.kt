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
}
