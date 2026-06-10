package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.ObservationScenarioTest
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.event.MonitoringSpeciesTotalsEditedEvent
import com.terraformation.backend.tracking.event.MonitoringSpeciesTotalsEditedEventValues
import com.terraformation.backend.tracking.event.T0PlotDataAssignedEvent
import io.mockk.every
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jobrunr.jobs.JobId
import org.jobrunr.jobs.lambdas.IocJobLambda
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies how plant density reacts to the edit events handled by [ObservationStore]: editing
 * species totals recomputes density, while assigning T0 density (which only affects survival rate)
 * leaves it untouched.
 */
class ObservationStorePlantDensityRecalculationTest : ObservationScenarioTest() {
  override val user = mockUser()

  private lateinit var observationId: ObservationId
  private lateinit var plotId: MonitoringPlotId
  private lateinit var stratumId: StratumId
  private lateinit var substratumId: SubstratumId
  private lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setUpCompletedObservation() {
    every { user.canManageObservation(any()) } returns true

    stratumId = insertStratum()
    substratumId = insertSubstratum()
    plotId = insertMonitoringPlot(permanentIndex = 1)
    observationId = insertObservation()
    insertObservationRequestedSubstratum()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)
    speciesId = insertSpecies()

    // 9 live plants in a 30m (900 m²) plot -> 9 * 10000 / 900 = 100 plants/ha.
    completePlotWithLivePlants(observationId, plotId, liveCount = 9)

    assertEquals(
        100,
        resultsStoreV2.fetchOneById(observationId).plantingDensity,
        "Baseline site planting density",
    )
  }

  @Test
  fun `editing species totals recalculates plant density at all levels`() {
    // Simulate a species-totals edit that doubles the live plant count.
    dslContext
        .update(OBSERVED_PLOT_SPECIES_TOTALS)
        .set(OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_LIVE, 18)
        .where(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
        .and(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(plotId))
        .execute()

    runEnqueuedJobs { observationStore.on(speciesTotalsEditedEvent(observationId, plotId)) }

    val results = resultsStoreV2.fetchOneById(observationId)
    val stratum = results.strata.single()
    val substratum = stratum.substrata.single()
    val plot = substratum.monitoringPlots.single()

    // 18 * 10000 / 900 = 200 plants/ha.
    assertEquals(200, plot.plantingDensity, "Plot planting density")
    assertEquals(200, substratum.plantingDensity, "Substratum planting density")
    assertEquals(200, stratum.plantingDensity, "Stratum planting density")
    assertEquals(200, results.plantingDensity, "Site planting density")
  }

  @Test
  fun `assigning T0 density does not change plant density`() {
    runEnqueuedJobs { observationStore.on(T0PlotDataAssignedEvent(monitoringPlotId = plotId)) }

    val results = resultsStoreV2.fetchOneById(observationId)

    assertEquals(100, results.plantingDensity, "Site planting density")
    assertEquals(100, results.strata.single().plantingDensity, "Stratum planting density")
    assertEquals(
        100,
        results.strata.single().substrata.single().plantingDensity,
        "Substratum planting density",
    )
  }

  @Test
  fun `editing an earlier observation recomputes later observations that roll its data forward`() {
    // setUp left observation 1 observing substratum A (density 100). Add observation 2 observing a
    // second substratum B in the same stratum; A is unobserved in observation 2, so its stratum and
    // site density roll observation 1's data for A forward.
    insertSubstratum()
    val observation2 = insertObservation()
    insertObservationRequestedSubstratum()
    val plotB = insertMonitoringPlot(permanentIndex = 1)
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)

    // Complete observation 2 after observation 1 so its reach-back picks up A from observation 1,
    // but observation 1's does not pick up B from observation 2.
    clock.instant = clock.instant.plus(1, ChronoUnit.DAYS)
    completePlotWithLivePlants(observation2, plotB, liveCount = 9)

    // avg(A from observation 1 = 100, B from observation 2 = 100) = 100.
    assertEquals(
        100,
        resultsStoreV2.fetchOneById(observation2).strata.single().plantingDensity,
        "Baseline later-observation density",
    )

    // Double observation 1's live count for plot A, then fire the edit event for observation 1.
    dslContext
        .update(OBSERVED_PLOT_SPECIES_TOTALS)
        .set(OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_LIVE, 18)
        .where(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
        .and(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(plotId))
        .execute()

    runEnqueuedJobs { observationStore.on(speciesTotalsEditedEvent(observationId, plotId)) }

    assertEquals(
        200,
        resultsStoreV2.fetchOneById(observationId).strata.single().plantingDensity,
        "Edited-observation density",
    )
    // Observation 2 rolls A forward from observation 1: avg(200, 100) = 150.
    assertEquals(
        150,
        resultsStoreV2.fetchOneById(observation2).strata.single().plantingDensity,
        "Later-observation density",
    )
  }

  private fun completePlotWithLivePlants(
      observationId: ObservationId,
      plotId: MonitoringPlotId,
      liveCount: Int,
  ) {
    observationStore.completePlot(
        observationId,
        plotId,
        emptySet(),
        "Notes",
        Instant.EPOCH,
        (1..liveCount).map {
          RecordedPlantsRow(
              certaintyId = RecordedSpeciesCertainty.Known,
              gpsCoordinates = point(it),
              speciesId = speciesId,
              statusId = RecordedPlantStatus.Live,
          )
        },
    )
  }

  private fun speciesTotalsEditedEvent(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
  ) =
      MonitoringSpeciesTotalsEditedEvent(
          certainty = RecordedSpeciesCertainty.Known,
          changedFrom = MonitoringSpeciesTotalsEditedEventValues(totalLive = 9),
          changedTo = MonitoringSpeciesTotalsEditedEventValues(totalLive = 18),
          monitoringPlotId = monitoringPlotId,
          observationId = observationId,
          organizationId = organizationId,
          plantingSiteId = plantingSiteId,
          speciesId = speciesId,
          speciesName = null,
      )

  /** Captures the jobs enqueued while [block] runs, then runs each one. */
  private fun runEnqueuedJobs(block: () -> Unit) {
    val jobs = mutableListOf<IocJobLambda<ObservationStore>>()
    every { jobScheduler.enqueue(capture(jobs)) } returns JobId(UUID.randomUUID())

    block()

    jobs.forEach { it.accept(observationStore) }
  }
}
