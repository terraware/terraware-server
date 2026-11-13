package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.TestSingletons
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.EntityLocker
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculator
import com.terraformation.backend.tracking.model.AffectedObservations
import com.terraformation.backend.tracking.model.AffectedStrata
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.util.GeometrySimplifier
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ObservationStoreClassifyAffectedObservationsTest : BaseObservationStoreTest() {
  private val NONE_AFFECTED = AffectedObservations(emptyList(), emptyList())

  private val plantingSiteStore: PlantingSiteStore by lazy {
    val geometrySimplifier = mockk<GeometrySimplifier>()
    every { geometrySimplifier.simplify(any(), any()) } answers { firstArg() }

    PlantingSiteStore(
        clock,
        TestSingletons.countryDetector,
        dslContext,
        EntityLocker(dslContext),
        eventPublisher,
        geometrySimplifier,
        IdentifierGenerator(clock, dslContext),
        monitoringPlotsDao,
        ParentStore(dslContext),
        plantingSitesDao,
        eventPublisher,
        strataDao,
        substrataDao,
    )
  }

  private lateinit var keptStratumId: StratumId
  private lateinit var keptPlotId: MonitoringPlotId
  private lateinit var deletedStratumId: StratumId
  private lateinit var deletedPlotId: MonitoringPlotId

  @Test
  fun `upcoming observation with plots in affected stratum is reassigned`() {
    val stratumId = insertStratum()
    insertSubstratum()
    insertMonitoringPlot(permanentIndex = 1)
    val observationId = insertObservation(state = ObservationState.Upcoming)
    insertObservationPlot(isPermanent = true)
    insertMonitoringPlot()
    insertObservationPlot()

    assertEquals(
        AffectedObservations(toAbandon = emptyList(), toReassign = listOf(observationId)),
        store.classifyAffectedObservations(plantingSiteId, AffectedStrata(setOf(stratumId))),
    )
  }

  @Test
  fun `in-progress observation with no completed plots is reassigned`() {
    val stratumId = insertStratum()
    insertSubstratum()
    insertMonitoringPlot()
    val inProgressId = insertObservation(state = ObservationState.InProgress)
    insertObservationPlot(claimedBy = user.userId)
    val overdueId = insertObservation(state = ObservationState.Overdue)
    insertObservationPlot()

    assertEquals(
        AffectedObservations(toAbandon = emptyList(), toReassign = listOf(inProgressId, overdueId)),
        store.classifyAffectedObservations(plantingSiteId, AffectedStrata(setOf(stratumId))),
    )
  }

  @Test
  fun `in-progress observation with a completed plot is abandoned`() {
    val affectedStratumId = insertStratum()
    insertSubstratum()
    val affectedPlotId = insertMonitoringPlot()
    insertStratum()
    insertSubstratum()
    val otherPlotId = insertMonitoringPlot()

    // The completed plot is in a different stratum than the incomplete one.
    val inProgressId = insertObservation(state = ObservationState.InProgress)
    insertObservationPlot(monitoringPlotId = affectedPlotId)
    insertObservationPlot(monitoringPlotId = otherPlotId, completedBy = user.userId)
    val overdueId = insertObservation(state = ObservationState.Overdue)
    insertObservationPlot(monitoringPlotId = affectedPlotId, claimedBy = user.userId)
    insertObservationPlot(monitoringPlotId = otherPlotId, completedBy = user.userId)

    assertEquals(
        AffectedObservations(toAbandon = listOf(inProgressId, overdueId), toReassign = emptyList()),
        store.classifyAffectedObservations(
            plantingSiteId,
            AffectedStrata(setOf(affectedStratumId)),
        ),
    )
  }

  @Test
  fun `observations with no plots in affected strata are ignored`() {
    val affectedStratumId = insertStratum()
    val affectedSubstratumId = insertSubstratum()
    val affectedPlotId = insertMonitoringPlot()
    insertStratum()
    insertSubstratum()
    val otherPlotId = insertMonitoringPlot()

    // Upcoming observation with plots only in another stratum
    insertObservation(state = ObservationState.Upcoming)
    insertObservationPlot(monitoringPlotId = otherPlotId)

    // Upcoming observation without any plots that requested a substratum in the affected stratum
    insertObservation(state = ObservationState.Upcoming)
    insertObservationRequestedSubstratum(substratumId = affectedSubstratumId)

    // In-progress observation whose only plot in the affected stratum is completed
    insertObservation(state = ObservationState.InProgress)
    insertObservationPlot(monitoringPlotId = affectedPlotId, completedBy = user.userId)
    insertObservationPlot(monitoringPlotId = otherPlotId)

    // Completed and abandoned observations
    insertObservation(completedTime = Instant.EPOCH, state = ObservationState.Completed)
    insertObservationPlot(monitoringPlotId = affectedPlotId, completedBy = user.userId)
    insertObservation(completedTime = Instant.EPOCH, state = ObservationState.Abandoned)
    insertObservationPlot(
        monitoringPlotId = affectedPlotId,
        statusId = ObservationPlotStatus.NotObserved,
    )

    assertEquals(
        NONE_AFFECTED,
        store.classifyAffectedObservations(
            plantingSiteId,
            AffectedStrata(setOf(affectedStratumId)),
        ),
    )
  }

  @Test
  fun `ad-hoc observations are ignored`() {
    val stratumId = insertStratum()
    insertSubstratum()
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, state = ObservationState.InProgress)
    insertObservationPlot()

    assertEquals(
        NONE_AFFECTED,
        store.classifyAffectedObservations(plantingSiteId, AffectedStrata(setOf(stratumId))),
    )
  }

  @Test
  fun `only includes observations of requested types`() {
    val stratumId = insertStratum()
    insertSubstratum()
    insertMonitoringPlot()
    insertObservation(state = ObservationState.Upcoming)
    insertObservationPlot()
    val biomassId =
        insertObservation(
            observationType = ObservationType.BiomassMeasurements,
            state = ObservationState.Upcoming,
        )
    insertObservationPlot()

    assertEquals(
        AffectedObservations(toAbandon = emptyList(), toReassign = listOf(biomassId)),
        store.classifyAffectedObservations(
            plantingSiteId,
            AffectedStrata(setOf(stratumId)),
            setOf(ObservationType.BiomassMeasurements),
        ),
    )
  }

  @Test
  fun `classification is unchanged after edit deletes the stratum`() {
    val siteId = insertTwoStratumSite()

    val upcomingId = insertObservation(state = ObservationState.Upcoming)
    insertObservationPlot(monitoringPlotId = keptPlotId)
    insertObservationPlot(monitoringPlotId = deletedPlotId)
    val inProgressWithCompletedPlotId = insertObservation(state = ObservationState.InProgress)
    insertObservationPlot(monitoringPlotId = keptPlotId, completedBy = user.userId)
    insertObservationPlot(monitoringPlotId = deletedPlotId)
    insertObservation(state = ObservationState.Upcoming)
    insertObservationPlot(monitoringPlotId = keptPlotId)

    val expected =
        AffectedObservations(
            toAbandon = listOf(inProgressWithCompletedPlotId),
            toReassign = listOf(upcomingId),
        )

    val edit = calculateEditDeletingStratum(siteId)
    val deletedStableId = strataDao.fetchOneById(deletedStratumId)!!.stableId!!

    // The calculated edit also touches the kept stratum; only look at the deleted one here.
    val affectedStrata = AffectedStrata(setOf(deletedStratumId), setOf(deletedStableId))

    val before = store.classifyAffectedObservations(siteId, affectedStrata)
    assertEquals(expected, before, "Classification before edit")

    plantingSiteStore.applyPlantingSiteEdit(edit)

    assertNull(strataDao.fetchOneById(deletedStratumId), "Stratum should have been deleted")
    assertEquals(
        before,
        store.classifyAffectedObservations(siteId, affectedStrata),
        "Classification after edit",
    )
  }

  @Test
  fun `does not include plots in strata deleted by earlier edits`() {
    val siteId = insertTwoStratumSite()

    // Left over from before plots were reassigned on map edits: an in-progress observation with an
    // incomplete plot in a stratum that has since been deleted.
    insertObservation(state = ObservationState.InProgress)
    insertObservationPlot(monitoringPlotId = deletedPlotId)
    insertObservationPlot(monitoringPlotId = keptPlotId, completedBy = user.userId)

    plantingSiteStore.applyPlantingSiteEdit(calculateEditDeletingStratum(siteId))

    assertEquals(
        NONE_AFFECTED,
        store.classifyAffectedObservations(siteId, AffectedStrata(setOf(keptStratumId))),
    )
  }

  /** Inserts a site with two side-by-side strata, each with one substratum and one plot. */
  private fun insertTwoStratumSite(): PlantingSiteId {
    every { user.canUpdatePlantingSite(any()) } returns true

    val siteId = insertPlantingSite(width = 2, height = 1, gridOrigin = point(0))
    keptStratumId = insertStratum(width = 1, height = 1)
    insertSubstratum(width = 1, height = 1)
    keptPlotId = insertMonitoringPlot(x = 0, y = 0)
    deletedStratumId = insertStratum(x = 1, width = 1, height = 1)
    insertSubstratum(x = 1, width = 1, height = 1)
    deletedPlotId = insertMonitoringPlot(x = 1, y = 0)

    return siteId
  }

  private fun calculateEditDeletingStratum(siteId: PlantingSiteId): PlantingSiteEdit {
    val existing = plantingSiteStore.fetchSiteById(siteId, PlantingSiteDepth.Plot)
    val desired = existing.copy(strata = existing.strata.filter { it.id == keptStratumId })
    return PlantingSiteEditCalculator(existing, desired).calculateSiteEdit()
  }
}
