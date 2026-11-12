package com.terraformation.backend.tracking.db.observationPlotStore

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.tracking.model.AffectedStrata
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ObservationPlotStoreRemoveUnobservedPlotsTest : BaseObservationPlotStoreTest() {
  @BeforeEach
  fun allowPlantingSiteUpdates() {
    every { user.canUpdatePlantingSite(any()) } returns true
  }

  @Test
  fun `removes plots in given strata and keeps others`() {
    val affectedStratumId = insertStratum()
    insertSubstratum()
    val affectedPermanentPlotId = insertMonitoringPlot(permanentIndex = 1)
    val affectedTemporaryPlotId = insertMonitoringPlot()
    insertStratum()
    insertSubstratum()
    val otherPermanentPlotId = insertMonitoringPlot(permanentIndex = 1)
    val otherTemporaryPlotId = insertMonitoringPlot()

    val observationId = insertObservation(state = ObservationState.Upcoming)
    insertObservationPlot(monitoringPlotId = affectedPermanentPlotId, isPermanent = true)
    insertObservationPlot(monitoringPlotId = affectedTemporaryPlotId)
    insertObservationPlot(monitoringPlotId = otherPermanentPlotId, isPermanent = true)
    insertObservationPlot(monitoringPlotId = otherTemporaryPlotId)

    val otherObservationId = insertObservation(state = ObservationState.Upcoming)
    insertObservationPlot(monitoringPlotId = affectedPermanentPlotId, isPermanent = true)

    store.removeUnobservedPlots(observationId, AffectedStrata(setOf(affectedStratumId)))

    assertEquals(
        mapOf(
            observationId to setOf(otherPermanentPlotId, otherTemporaryPlotId),
            otherObservationId to setOf(affectedPermanentPlotId),
        ),
        fetchPlotIdsByObservation(),
    )
  }

  @Test
  fun `never removes completed plots`() {
    val stratumId = insertStratum()
    insertSubstratum()
    val completedPlotId = insertMonitoringPlot(permanentIndex = 1)
    val claimedPlotId = insertMonitoringPlot()
    val unclaimedPlotId = insertMonitoringPlot()

    val observationId = insertObservation(state = ObservationState.InProgress)
    insertObservationPlot(
        monitoringPlotId = completedPlotId,
        isPermanent = true,
        completedBy = user.userId,
    )
    insertObservationPlot(monitoringPlotId = claimedPlotId, claimedBy = user.userId)
    insertObservationPlot(monitoringPlotId = unclaimedPlotId)

    store.removeUnobservedPlots(observationId, AffectedStrata(setOf(stratumId)))

    assertEquals(mapOf(observationId to setOf(completedPlotId)), fetchPlotIdsByObservation())
  }

  @Test
  fun `removes plots whose substratum was deleted`() {
    val affectedStratumId = insertStratum()
    insertSubstratum()
    val affectedPlotId = insertMonitoringPlot(permanentIndex = 1)
    insertStratum()
    insertSubstratum()
    val orphanedPlotId = insertMonitoringPlot(permanentIndex = 1)
    val otherPlotId = insertMonitoringPlot(permanentIndex = 2)

    val observationId = insertObservation(state = ObservationState.Upcoming)
    insertObservationPlot(monitoringPlotId = affectedPlotId, isPermanent = true)
    insertObservationPlot(monitoringPlotId = orphanedPlotId, isPermanent = true)
    insertObservationPlot(monitoringPlotId = otherPlotId, isPermanent = true)

    dslContext
        .update(MONITORING_PLOTS)
        .setNull(MONITORING_PLOTS.SUBSTRATUM_ID)
        .where(MONITORING_PLOTS.ID.eq(orphanedPlotId))
        .execute()

    store.removeUnobservedPlots(observationId, AffectedStrata(setOf(affectedStratumId)))

    assertEquals(mapOf(observationId to setOf(otherPlotId)), fetchPlotIdsByObservation())
  }

  @Test
  fun `deletes orphaned temporary plots but keeps temporary plots used by another observation`() {
    val stratumId = insertStratum()
    insertSubstratum()
    val orphanedPlotId = insertMonitoringPlot()
    val sharedPlotId = insertMonitoringPlot()
    val previouslyOrphanedPlotId = insertMonitoringPlot()

    val observationId = insertObservation(state = ObservationState.Upcoming)
    insertObservationPlot(monitoringPlotId = orphanedPlotId)
    insertObservationPlot(monitoringPlotId = sharedPlotId)

    val otherObservationId = insertObservation(state = ObservationState.Upcoming)
    insertObservationPlot(monitoringPlotId = sharedPlotId)

    store.removeUnobservedPlots(observationId, AffectedStrata(setOf(stratumId)))

    assertEquals(
        mapOf(otherObservationId to setOf(sharedPlotId)),
        fetchPlotIdsByObservation(),
        "Observation plots",
    )
    assertEquals(
        setOf(sharedPlotId, previouslyOrphanedPlotId),
        monitoringPlotsDao.findAll().map { it.id }.toSet(),
        "Only the plot removed from the observation should be deleted",
    )
  }

  private fun fetchPlotIdsByObservation(): Map<ObservationId, Set<MonitoringPlotId>> =
      observationPlotsDao
          .findAll()
          .groupBy({ it.observationId!! }, { it.monitoringPlotId!! })
          .mapValues { it.value.toSet() }
}
