package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSiteSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSubzoneSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedZoneSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.point
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStorePopulateCumulativeDeadTest : BaseObservationStoreTest() {
  private lateinit var plotId: MonitoringPlotId

  @BeforeEach
  fun insertDetailedSite() {
    insertPlantingZone()
    insertPlantingSubzone()
    plotId = insertMonitoringPlot()
  }

  @Test
  fun `does not insert anything if this is the first observation of a site`() {
    insertPlantingSite(x = 0)
    insertPlantingZone()
    insertPlantingSubzone()
    insertMonitoringPlot()
    val otherSiteObservationId = insertObservation()
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)
    store.completePlot(
        otherSiteObservationId,
        inserted.monitoringPlotId,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = point(1),
                speciesName = "Species name",
                statusId = RecordedPlantStatus.Dead,
            )
        ),
    )

    val totalsForOtherSite = helper.fetchAllTotals()

    insertPlantingSite(x = 0)
    insertPlantingZone()
    insertPlantingSubzone()
    insertMonitoringPlot()
    val observationId = insertObservation()
    insertObservationPlot(isPermanent = true)

    store.populateCumulativeDead(observationId)

    assertEquals(totalsForOtherSite, helper.fetchAllTotals())
  }

  @Test
  fun `populates totals from permanent monitoring plots`() {
    val deadPlant =
        RecordedPlantsRow(
            certaintyId = RecordedSpeciesCertainty.Other,
            gpsCoordinates = point(1),
            speciesName = "Species name",
            statusId = RecordedPlantStatus.Dead,
        )

    insertPlantingSite(x = 0)
    insertPlantingZone()
    insertPlantingSubzone()
    val plotId1 = insertMonitoringPlot()
    val plotId2 = insertMonitoringPlot()

    val previousObservationId = insertObservation()

    insertObservationPlot(
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        isPermanent = true,
        monitoringPlotId = plotId1,
    )
    insertObservationPlot(
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        isPermanent = true,
        monitoringPlotId = plotId2,
    )
    store.completePlot(
        previousObservationId,
        plotId1,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(deadPlant),
    )
    store.completePlot(
        previousObservationId,
        plotId2,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(deadPlant),
    )

    val totalsFromPreviousObservation = helper.fetchAllTotals()

    // In the next observation, plot 2 is no longer permanent.
    val observationId = insertObservation()
    insertObservationPlot(isPermanent = true, monitoringPlotId = plotId1)
    insertObservationPlot(isPermanent = false, monitoringPlotId = plotId2)

    store.populateCumulativeDead(observationId)

    val totalsForThisObservation = helper.fetchAllTotals() - totalsFromPreviousObservation

    assertEquals(
        setOf(
            ObservedPlotSpeciesTotalsRow(
                observationId = observationId,
                monitoringPlotId = plotId1,
                speciesName = "Species name",
                certaintyId = RecordedSpeciesCertainty.Other,
                totalLive = 0,
                totalDead = 0,
                totalExisting = 0,
                mortalityRate = 100,
                cumulativeDead = 1,
                permanentLive = 0,
            ),
            ObservedSiteSpeciesTotalsRow(
                observationId = observationId,
                plantingSiteId = inserted.plantingSiteId,
                speciesName = "Species name",
                certaintyId = RecordedSpeciesCertainty.Other,
                totalLive = 0,
                totalDead = 0,
                totalExisting = 0,
                mortalityRate = 100,
                cumulativeDead = 1,
                permanentLive = 0,
            ),
            ObservedZoneSpeciesTotalsRow(
                observationId = observationId,
                plantingZoneId = inserted.plantingZoneId,
                speciesName = "Species name",
                certaintyId = RecordedSpeciesCertainty.Other,
                totalLive = 0,
                totalDead = 0,
                totalExisting = 0,
                mortalityRate = 100,
                cumulativeDead = 1,
                permanentLive = 0,
            ),
            ObservedSubzoneSpeciesTotalsRow(
                observationId = observationId,
                plantingSubzoneId = inserted.plantingSubzoneId,
                speciesName = "Species name",
                certaintyId = RecordedSpeciesCertainty.Other,
                totalLive = 0,
                totalDead = 0,
                totalExisting = 0,
                mortalityRate = 100,
                cumulativeDead = 1,
                permanentLive = 0,
            ),
        ),
        totalsForThisObservation,
    )
  }

  @Test
  fun `only populates totals if there were dead plants`() {
    val livePlant =
        RecordedPlantsRow(
            certaintyId = RecordedSpeciesCertainty.Other,
            gpsCoordinates = point(1),
            speciesName = "Species name",
            statusId = RecordedPlantStatus.Live,
        )

    insertPlantingSite(x = 0)
    insertPlantingZone()
    insertPlantingSubzone()
    insertMonitoringPlot()

    val previousObservationId = insertObservation()

    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)
    store.completePlot(
        previousObservationId,
        inserted.monitoringPlotId,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(livePlant),
    )

    val totalsFromPreviousObservation = helper.fetchAllTotals()

    val observationId = insertObservation()
    insertObservationPlot(isPermanent = true)

    store.populateCumulativeDead(observationId)

    assertEquals(totalsFromPreviousObservation, helper.fetchAllTotals())
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    val observationId = insertObservation()

    every { user.canUpdateObservation(observationId) } returns false

    assertThrows<AccessDeniedException> { store.populateCumulativeDead(observationId) }
  }
}
