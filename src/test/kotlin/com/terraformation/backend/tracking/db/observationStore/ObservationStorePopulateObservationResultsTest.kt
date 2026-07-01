package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.tables.records.ObservationPlotResultsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationSiteResultsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationStratumResultsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationSubstratumResultsRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
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

    insertObservationRequestedSubstratum(substratumId = substratumId)

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
            observedDensity = 150,
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
            observedDensity = 150,
        ),
        "observation_site_results",
    )
  }

  @Test
  fun `backfill rolls an unobserved substratum forward into the stratum totals`() {
    val stratumId = insertStratum()
    val substratumA = insertSubstratum()
    val plotA = insertMonitoringPlot()
    val plotAHistory = inserted.monitoringPlotHistoryId
    val substratumB = insertSubstratum()
    val plotB = insertMonitoringPlot()
    val plotBHistory = inserted.monitoringPlotHistoryId
    val speciesId = insertSpecies()

    // Observation 1 (earlier) observed substratum B with a completed plot.
    val observation1 =
        insertObservation(state = ObservationState.Completed, completedTime = Instant.EPOCH)
    insertObservationPlot(
        observationId = observation1,
        monitoringPlotId = plotB,
        monitoringPlotHistoryId = plotBHistory,
        isPermanent = true,
        completedBy = user.userId,
        completedTime = Instant.EPOCH,
        statusId = ObservationPlotStatus.Completed,
    )
    insertObservedPlotSpeciesTotals(
        observationId = observation1,
        monitoringPlotId = plotB,
        speciesId = speciesId,
        totalLive = 10,
        permanentLive = 10,
    )
    insertObservedSubstratumSpeciesTotals(
        observationId = observation1,
        substratumId = substratumB,
        speciesId = speciesId,
        totalLive = 10,
        permanentLive = 10,
    )

    // Observation 2 (later) observed only substratum A, leaving B unobserved.
    val observation2 =
        insertObservation(
            state = ObservationState.Completed,
            completedTime = Instant.ofEpochSecond(10),
        )
    insertObservationPlot(
        observationId = observation2,
        monitoringPlotId = plotA,
        monitoringPlotHistoryId = plotAHistory,
        isPermanent = true,
        completedBy = user.userId,
        completedTime = Instant.ofEpochSecond(10),
        statusId = ObservationPlotStatus.Completed,
    )
    insertObservedPlotSpeciesTotals(
        observationId = observation2,
        monitoringPlotId = plotA,
        speciesId = speciesId,
        totalLive = 9,
        permanentLive = 9,
    )
    insertObservedSubstratumSpeciesTotals(
        observationId = observation2,
        substratumId = substratumA,
        speciesId = speciesId,
        totalLive = 9,
        permanentLive = 9,
    )
    // Stale stratum totals reflecting only A, as if computed before the dependency feature existed.
    insertObservedStratumSpeciesTotals(
        observationId = observation2,
        stratumId = stratumId,
        speciesId = speciesId,
        totalLive = 9,
        permanentLive = 9,
    )

    store.populateObservationResults(observation2)

    // The backfill records B's dependency on observation 1 and re-aggregates the stratum totals.
    val (totalLive, permanentLive) =
        dslContext
            .select(
                OBSERVED_STRATUM_SPECIES_TOTALS.TOTAL_LIVE,
                OBSERVED_STRATUM_SPECIES_TOTALS.PERMANENT_LIVE,
            )
            .from(OBSERVED_STRATUM_SPECIES_TOTALS)
            .where(OBSERVED_STRATUM_SPECIES_TOTALS.OBSERVATION_ID.eq(observation2))
            .and(OBSERVED_STRATUM_SPECIES_TOTALS.SPECIES_ID.eq(speciesId))
            .fetchOne { it.value1() to it.value2() }!!
    assertEquals(
        19,
        permanentLive,
        "Backfill rolls observation 1's substratum B forward into observation 2's permanent_live",
    )
    assertEquals(9, totalLive, "total_live stays observed-only and excludes the rolled-forward B")
  }

  @Test
  fun `throws AccessDeniedException when user cannot manage observation`() {
    val observationId = insertObservation()

    every { user.canManageObservation(observationId) } returns false

    assertThrows<AccessDeniedException> { store.populateObservationResults(observationId) }
  }
}
