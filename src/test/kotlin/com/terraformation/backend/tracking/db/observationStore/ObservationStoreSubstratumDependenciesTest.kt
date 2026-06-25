package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.records.DependentSubstratumObservationRecord
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.ObservationScenarioTest
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ObservationStoreSubstratumDependenciesTest : ObservationScenarioTest() {
  override val user = mockUser()

  private lateinit var recordedPlants: List<RecordedPlantsRow>

  @BeforeEach
  fun insertRecordedSpecies() {
    val speciesId = insertSpecies()
    recordedPlants =
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live,
            )
        )
  }

  @Test
  fun `records self-reference for an observed substratum`() {
    insertStratum()
    insertSubstratum()
    val substratumHistoryId = inserted.substratumHistoryId
    val plotId = insertMonitoringPlot(permanentIndex = 1)
    val observationId = insertObservation()
    insertObservationRequestedSubstratum()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)

    observationStore.completePlot(
        observationId,
        plotId,
        emptySet(),
        "Notes",
        Instant.ofEpochSecond(1),
        recordedPlants,
    )

    assertTableEquals(
        DependentSubstratumObservationRecord(
            observationId = observationId,
            substratumHistoryId = substratumHistoryId,
            dependsOnObservationId = observationId,
            dependsOnSubstratumHistoryId = substratumHistoryId,
        ),
        "Observed substratum self-references this observation",
    )
  }

  @Test
  fun `records prior observation for a substratum not observed in the later observation`() {
    insertStratum()
    val substratumIdA = insertSubstratum()
    val substratumHistoryIdA = inserted.substratumHistoryId
    val plotIdA = insertMonitoringPlot(permanentIndex = 1, substratumId = substratumIdA)
    val plotHistoryIdA = inserted.monitoringPlotHistoryId

    val substratumIdB = insertSubstratum()
    val substratumHistoryIdB = inserted.substratumHistoryId
    val plotIdB = insertMonitoringPlot(permanentIndex = 2, substratumId = substratumIdB)
    val plotHistoryIdB = inserted.monitoringPlotHistoryId

    clock.instant = Instant.ofEpochSecond(1)
    val observationId1 = insertObservation()
    insertObservationRequestedSubstratum(substratumId = substratumIdA)
    insertObservationRequestedSubstratum(substratumId = substratumIdB)
    insertObservationPlot(
        claimedBy = user.userId,
        isPermanent = true,
        monitoringPlotId = plotIdA,
        monitoringPlotHistoryId = plotHistoryIdA,
    )
    insertObservationPlot(
        claimedBy = user.userId,
        isPermanent = true,
        monitoringPlotId = plotIdB,
        monitoringPlotHistoryId = plotHistoryIdB,
    )

    observationStore.completePlot(
        observationId1,
        plotIdA,
        emptySet(),
        "Notes",
        clock.instant,
        recordedPlants,
    )
    observationStore.completePlot(
        observationId1,
        plotIdB,
        emptySet(),
        "Notes",
        clock.instant,
        recordedPlants,
    )

    clock.instant = Instant.ofEpochSecond(2)
    val observationId2 = insertObservation()
    insertObservationRequestedSubstratum(substratumId = substratumIdA)
    val plotIdA2 = insertMonitoringPlot(permanentIndex = 3, substratumId = substratumIdA)
    val plotHistoryIdA2 = inserted.monitoringPlotHistoryId
    insertObservationPlot(
        claimedBy = user.userId,
        isPermanent = true,
        monitoringPlotId = plotIdA2,
        monitoringPlotHistoryId = plotHistoryIdA2,
    )

    observationStore.completePlot(
        observationId2,
        plotIdA2,
        emptySet(),
        "Notes",
        clock.instant,
        recordedPlants,
    )

    assertTableEquals(
        listOf(
            // observationId1 observed both substrata -> self-references.
            DependentSubstratumObservationRecord(
                observationId = observationId1,
                substratumHistoryId = substratumHistoryIdA,
                dependsOnObservationId = observationId1,
                dependsOnSubstratumHistoryId = substratumHistoryIdA,
            ),
            DependentSubstratumObservationRecord(
                observationId = observationId1,
                substratumHistoryId = substratumHistoryIdB,
                dependsOnObservationId = observationId1,
                dependsOnSubstratumHistoryId = substratumHistoryIdB,
            ),
            // observationId2 observed A -> self-reference; B was not observed -> points back to
            // observationId1.
            DependentSubstratumObservationRecord(
                observationId = observationId2,
                substratumHistoryId = substratumHistoryIdA,
                dependsOnObservationId = observationId2,
                dependsOnSubstratumHistoryId = substratumHistoryIdA,
            ),
            DependentSubstratumObservationRecord(
                observationId = observationId2,
                substratumHistoryId = substratumHistoryIdB,
                dependsOnObservationId = observationId1,
                dependsOnSubstratumHistoryId = substratumHistoryIdB,
            ),
        ),
        "Later observation references prior observation for unobserved substratum",
    )
  }

  @Test
  fun `records prior observation for a requested but unobserved substratum`() {
    insertStratum()
    val substratumIdA = insertSubstratum()
    val substratumHistoryIdA = inserted.substratumHistoryId
    val plotIdA = insertMonitoringPlot(permanentIndex = 1, substratumId = substratumIdA)
    val plotHistoryIdA = inserted.monitoringPlotHistoryId

    val substratumIdB = insertSubstratum()
    val substratumHistoryIdB = inserted.substratumHistoryId
    val plotIdB = insertMonitoringPlot(permanentIndex = 2, substratumId = substratumIdB)
    val plotHistoryIdB = inserted.monitoringPlotHistoryId

    // observationId1 observes both substrata.
    clock.instant = Instant.ofEpochSecond(1)
    val observationId1 = insertObservation()
    insertObservationRequestedSubstratum(substratumId = substratumIdA)
    insertObservationRequestedSubstratum(substratumId = substratumIdB)
    insertObservationPlot(
        claimedBy = user.userId,
        isPermanent = true,
        monitoringPlotId = plotIdA,
        monitoringPlotHistoryId = plotHistoryIdA,
    )
    insertObservationPlot(
        claimedBy = user.userId,
        isPermanent = true,
        monitoringPlotId = plotIdB,
        monitoringPlotHistoryId = plotHistoryIdB,
    )

    observationStore.completePlot(
        observationId1,
        plotIdA,
        emptySet(),
        "Notes",
        clock.instant,
        recordedPlants,
    )
    observationStore.completePlot(
        observationId1,
        plotIdB,
        emptySet(),
        "Notes",
        clock.instant,
        recordedPlants,
    )

    // observationId2 requests B but completes no plot in it (only A is observed), so B was
    // requested yet never actually observed in this observation.
    clock.instant = Instant.ofEpochSecond(2)
    val observationId2 = insertObservation()
    insertObservationRequestedSubstratum(substratumId = substratumIdA)
    insertObservationRequestedSubstratum(substratumId = substratumIdB)
    val plotIdA2 = insertMonitoringPlot(permanentIndex = 3, substratumId = substratumIdA)
    val plotHistoryIdA2 = inserted.monitoringPlotHistoryId
    insertObservationPlot(
        claimedBy = user.userId,
        isPermanent = true,
        monitoringPlotId = plotIdA2,
        monitoringPlotHistoryId = plotHistoryIdA2,
    )

    observationStore.completePlot(
        observationId2,
        plotIdA2,
        emptySet(),
        "Notes",
        clock.instant,
        recordedPlants,
    )

    assertTableEquals(
        listOf(
            DependentSubstratumObservationRecord(
                observationId = observationId1,
                substratumHistoryId = substratumHistoryIdA,
                dependsOnObservationId = observationId1,
                dependsOnSubstratumHistoryId = substratumHistoryIdA,
            ),
            DependentSubstratumObservationRecord(
                observationId = observationId1,
                substratumHistoryId = substratumHistoryIdB,
                dependsOnObservationId = observationId1,
                dependsOnSubstratumHistoryId = substratumHistoryIdB,
            ),
            DependentSubstratumObservationRecord(
                observationId = observationId2,
                substratumHistoryId = substratumHistoryIdA,
                dependsOnObservationId = observationId2,
                dependsOnSubstratumHistoryId = substratumHistoryIdA,
            ),
            // B was requested but not observed in observationId2, so it rolls forward to the prior
            // observation that actually observed it rather than self-referencing.
            DependentSubstratumObservationRecord(
                observationId = observationId2,
                substratumHistoryId = substratumHistoryIdB,
                dependsOnObservationId = observationId1,
                dependsOnSubstratumHistoryId = substratumHistoryIdB,
            ),
        ),
        "Requested but unobserved substratum rolls forward instead of self-referencing",
    )
  }

  @Test
  fun `records dependencies for a site-wide observation with no requested substrata`() {
    insertStratum()
    insertSubstratum()
    val substratumHistoryId = inserted.substratumHistoryId
    val plotId = insertMonitoringPlot(permanentIndex = 1)
    val observationId = insertObservation()
    // No requested substrata: the observation covers the entire site.
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)

    observationStore.completePlot(
        observationId,
        plotId,
        emptySet(),
        "Notes",
        Instant.ofEpochSecond(1),
        recordedPlants,
    )

    assertTableEquals(
        DependentSubstratumObservationRecord(
            observationId = observationId,
            substratumHistoryId = substratumHistoryId,
            dependsOnObservationId = observationId,
            dependsOnSubstratumHistoryId = substratumHistoryId,
        ),
        "Site-wide observation self-references its observed substratum",
    )
  }

  @Test
  fun `records no row for a substratum never observed at or before the observation`() {
    insertStratum()
    val substratumIdA = insertSubstratum()
    val substratumHistoryIdA = inserted.substratumHistoryId
    val plotIdA = insertMonitoringPlot(permanentIndex = 1, substratumId = substratumIdA)
    val plotHistoryIdA = inserted.monitoringPlotHistoryId

    // Substratum B is present in the snapshot but never requested/observed.
    val substratumIdB = insertSubstratum()
    insertMonitoringPlot(permanentIndex = 2, substratumId = substratumIdB)

    val observationId = insertObservation()
    insertObservationRequestedSubstratum(substratumId = substratumIdA)
    insertObservationPlot(
        claimedBy = user.userId,
        isPermanent = true,
        monitoringPlotId = plotIdA,
        monitoringPlotHistoryId = plotHistoryIdA,
    )

    observationStore.completePlot(
        observationId,
        plotIdA,
        emptySet(),
        "Notes",
        Instant.ofEpochSecond(1),
        recordedPlants,
    )

    assertTableEquals(
        DependentSubstratumObservationRecord(
            observationId = observationId,
            substratumHistoryId = substratumHistoryIdA,
            dependsOnObservationId = observationId,
            dependsOnSubstratumHistoryId = substratumHistoryIdA,
        ),
        "Never-observed substratum has no dependency row",
    )
  }
}
