package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.records.PlantingSiteSurvivalRateCalculationsRecord
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SURVIVAL_RATE_CALCULATIONS
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.event.T0PlotDataAssignedEvent
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import java.util.UUID
import org.jobrunr.jobs.JobId
import org.jobrunr.jobs.lambdas.IocJobLambda
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class ObservationStoreSurvivalRateConcurrencyTest : BaseObservationStoreTest() {
  private lateinit var stratumId: StratumId
  private lateinit var substratumId: SubstratumId
  private lateinit var plotId: MonitoringPlotId

  @BeforeEach
  fun setUpHierarchy() {
    stratumId = insertStratum()
    substratumId = insertSubstratum()
    plotId = insertMonitoringPlot()
  }

  @Test
  fun `first event inserts marker and enqueues exactly one job`() {
    every { jobScheduler.enqueue<ObservationStore>(any()) } returns JobId(UUID.randomUUID())

    store.on(T0PlotDataAssignedEvent(monitoringPlotId = plotId))

    assertTableEquals(PlantingSiteSurvivalRateCalculationsRecord(plantingSiteId, false))
    verify(exactly = 1) { jobScheduler.enqueue<ObservationStore>(any()) }
  }

  @Test
  fun `second event while in progress sets flag and does not enqueue again`() {
    every { jobScheduler.enqueue<ObservationStore>(any()) } returns JobId(UUID.randomUUID())

    store.on(T0PlotDataAssignedEvent(monitoringPlotId = plotId))
    store.on(T0PlotDataAssignedEvent(monitoringPlotId = plotId))

    assertTableEquals(PlantingSiteSurvivalRateCalculationsRecord(plantingSiteId, true))
    verify(exactly = 1) { jobScheduler.enqueue<ObservationStore>(any()) }
  }

  @Test
  fun `job with no pending edits deletes the marker`() {
    val slot: CapturingSlot<IocJobLambda<ObservationStore>> = slot()
    every { jobScheduler.enqueue(capture(slot)) } returns JobId(UUID.randomUUID())

    store.on(T0PlotDataAssignedEvent(monitoringPlotId = plotId))
    slot.captured.accept(store)

    assertTableEmpty(PLANTING_SITE_SURVIVAL_RATE_CALCULATIONS)
  }

  @Test
  fun `job with pending edits clears flag, keeps marker, and enqueues a rerun`() {
    val slot: CapturingSlot<IocJobLambda<ObservationStore>> = slot()
    every { jobScheduler.enqueue(capture(slot)) } returns JobId(UUID.randomUUID())

    store.on(T0PlotDataAssignedEvent(monitoringPlotId = plotId)) // inserts marker, enqueues job 1
    val firstJob = slot.captured
    store.on(T0PlotDataAssignedEvent(monitoringPlotId = plotId)) // in progress -> sets flag

    firstJob.accept(store) // maybeRerun sees flag -> clears it, enqueues rerun

    assertTableEquals(PlantingSiteSurvivalRateCalculationsRecord(plantingSiteId, false))
    verify(exactly = 2) { jobScheduler.enqueue<ObservationStore>(any()) }
  }

  @Test
  fun `marker is resolved even when the recalculation throws`() {
    val spyStore = spyk(store)
    val slot: CapturingSlot<IocJobLambda<ObservationStore>> = slot()
    every { jobScheduler.enqueue(capture(slot)) } returns JobId(UUID.randomUUID())
    every { spyStore.recalculateSurvivalRates(any<MonitoringPlotId>()) } throws
        RuntimeException("boom")

    spyStore.on(T0PlotDataAssignedEvent(monitoringPlotId = plotId))
    assertTableEquals(PlantingSiteSurvivalRateCalculationsRecord(plantingSiteId, false))

    assertDoesNotThrow { slot.captured.accept(spyStore) }
    assertTableEmpty(PLANTING_SITE_SURVIVAL_RATE_CALCULATIONS)
  }
}
