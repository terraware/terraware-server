package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.tables.records.ObservationPlotResultsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationSiteResultsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationStratumResultsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationSubstratumResultsRecord
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStorePopulateObservationResultsTest : BaseObservationStoreTest() {
  @Test
  fun `rolls up plot, substratum, stratum, and site results from observed species totals`() {
    val stratumId = insertStratum()
    val stratumHistoryId = inserted.stratumHistoryId
    val substratumId = insertSubstratum()
    val substratumHistoryId = inserted.substratumHistoryId
    val plotId1 = insertMonitoringPlot()
    val plotHistoryId1 = inserted.monitoringPlotHistoryId
    val plotId2 = insertMonitoringPlot()
    val plotHistoryId2 = inserted.monitoringPlotHistoryId
    val speciesId = insertSpecies()

    val observationId =
        insertObservation(
            state = ObservationState.Completed,
            completedTime = Instant.EPOCH,
        )
    val plantingSiteHistoryId = inserted.plantingSiteHistoryId

    insertObservationPlot(
        monitoringPlotId = plotId1,
        monitoringPlotHistoryId = plotHistoryId1,
        isPermanent = true,
        completedBy = user.userId,
        completedTime = Instant.EPOCH,
        statusId = ObservationPlotStatus.Completed,
    )
    insertObservationPlot(
        monitoringPlotId = plotId2,
        monitoringPlotHistoryId = plotHistoryId2,
        isPermanent = true,
        completedBy = user.userId,
        completedTime = Instant.EPOCH,
        statusId = ObservationPlotStatus.Completed,
    )

    // Plot 1: 9 live → density = round(9 * 10000 / 30^2) = 100
    insertObservedPlotSpeciesTotals(
        observationId = observationId,
        monitoringPlotId = plotId1,
        speciesId = speciesId,
        totalLive = 9,
        totalDead = 1,
        totalExisting = 2,
        permanentLive = 9,
    )
    // Plot 2: 18 live → density = round(18 * 10000 / 30^2) = 200
    insertObservedPlotSpeciesTotals(
        observationId = observationId,
        monitoringPlotId = plotId2,
        speciesId = speciesId,
        totalLive = 18,
        totalDead = 2,
        totalExisting = 3,
        permanentLive = 18,
    )
    insertObservedSubstratumSpeciesTotals(
        observationId = observationId,
        substratumId = substratumId,
        speciesId = speciesId,
        totalLive = 27,
        totalDead = 3,
        totalExisting = 5,
        permanentLive = 27,
    )
    insertObservedStratumSpeciesTotals(
        observationId = observationId,
        stratumId = stratumId,
        speciesId = speciesId,
        totalLive = 27,
        totalDead = 3,
        totalExisting = 5,
        permanentLive = 27,
    )
    insertObservedSiteSpeciesTotals(
        observationId = observationId,
        speciesId = speciesId,
        totalLive = 27,
        totalDead = 3,
        totalExisting = 5,
        permanentLive = 27,
    )

    store.populateObservationResults(observationId)

    assertTableEquals(
        listOf(
            ObservationPlotResultsRecord(
                observationId = observationId,
                monitoringPlotId = plotId1,
                monitoringPlotHistoryId = plotHistoryId1,
                totalLive = 9,
                totalDead = 1,
                totalExisting = 2,
                permanentLive = 9,
                plantDensity = 100,
                survivalRate = null,
            ),
            ObservationPlotResultsRecord(
                observationId = observationId,
                monitoringPlotId = plotId2,
                monitoringPlotHistoryId = plotHistoryId2,
                totalLive = 18,
                totalDead = 2,
                totalExisting = 3,
                permanentLive = 18,
                plantDensity = 200,
                survivalRate = null,
            ),
        ),
        "observation_plot_results",
    )

    // stddev_samp(100, 200) = |200 - 100| / sqrt(2) ≈ 70.71, cast-to-int rounds to 71.
    assertTableEquals(
        ObservationSubstratumResultsRecord(
            observationId = observationId,
            substratumId = substratumId,
            substratumHistoryId = substratumHistoryId,
            totalLive = 27,
            totalDead = 3,
            totalExisting = 5,
            permanentLive = 27,
            survivalRate = null,
            survivalRateStdDev = null,
            survivalRateArea = null,
            plantDensity = 150,
            plantDensityStdDev = 71,
        ),
        "observation_substratum_results",
    )

    assertTableEquals(
        ObservationStratumResultsRecord(
            observationId = observationId,
            stratumId = stratumId,
            stratumHistoryId = stratumHistoryId,
            totalLive = 27,
            totalDead = 3,
            totalExisting = 5,
            permanentLive = 27,
            survivalRate = null,
            survivalRateStdDev = null,
            plantDensity = 150,
            plantDensityStdDev = 71,
        ),
        "observation_stratum_results",
    )

    assertTableEquals(
        ObservationSiteResultsRecord(
            observationId = observationId,
            plantingSiteId = plantingSiteId,
            plantingSiteHistoryId = plantingSiteHistoryId,
            totalLive = 27,
            totalDead = 3,
            totalExisting = 5,
            permanentLive = 27,
            survivalRate = null,
            survivalRateStdDev = null,
            plantDensity = 150,
            plantDensityStdDev = 71,
        ),
        "observation_site_results",
    )
  }

  @Test
  fun `throws AccessDeniedException when user cannot manage observation`() {
    val observationId = insertObservation()

    every { user.canManageObservation(observationId) } returns false

    assertThrows<AccessDeniedException> { store.populateObservationResults(observationId) }
  }
}
