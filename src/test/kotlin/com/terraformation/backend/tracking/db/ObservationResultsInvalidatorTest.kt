package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.records.ObservationPlotResultsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationSiteResultsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationStratumResultsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationSubstratumResultsRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.event.T0PlotDataAssignedEvent
import com.terraformation.backend.tracking.event.T0StratumDataAssignedEvent
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ObservationResultsInvalidatorTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val invalidator: ObservationResultsInvalidator by lazy {
    ObservationResultsInvalidator(dslContext)
  }

  private lateinit var stratumId1: StratumId
  private lateinit var stratumHistoryId1: StratumHistoryId
  private lateinit var substratumIdA: SubstratumId
  private lateinit var substratumHistoryIdA: SubstratumHistoryId
  private lateinit var plotIdA: MonitoringPlotId
  private lateinit var substratumIdB: SubstratumId
  private lateinit var substratumHistoryIdB: SubstratumHistoryId
  private lateinit var plotIdB: MonitoringPlotId
  private lateinit var stratumId2: StratumId
  private lateinit var stratumHistoryId2: StratumHistoryId
  private lateinit var substratumIdC: SubstratumId
  private lateinit var substratumHistoryIdC: SubstratumHistoryId
  private lateinit var plotIdC: MonitoringPlotId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertPlantingSite()

    stratumId1 = insertStratum()
    stratumHistoryId1 = inserted.stratumHistoryId
    substratumIdA = insertSubstratum()
    substratumHistoryIdA = inserted.substratumHistoryId
    plotIdA = insertMonitoringPlot(permanentIndex = 1)
    substratumIdB = insertSubstratum()
    substratumHistoryIdB = inserted.substratumHistoryId
    plotIdB = insertMonitoringPlot(permanentIndex = 2)

    stratumId2 = insertStratum()
    stratumHistoryId2 = inserted.stratumHistoryId
    substratumIdC = insertSubstratum()
    substratumHistoryIdC = inserted.substratumHistoryId
    plotIdC = insertMonitoringPlot(permanentIndex = 3)
  }

  @Nested
  inner class InvalidateObservationPlots {
    @Test
    fun `inserts placeholder rows for every level when no results exist`() {
      val observationId = insertCompletedObservation(1, plotIdA)

      invalidator.invalidateObservationPlots(observationId, listOf(plotIdA))

      assertTableEquals(
          ObservationPlotResultsRecord(
              observationId = observationId,
              monitoringPlotId = plotIdA,
              monitoringPlotHistoryId =
                  inserted.monitoringPlotHistoryIdsByMonitoringPlotId[plotIdA]!!.last(),
              totalLive = 0,
              totalDead = 0,
              totalExisting = 0,
              permanentLive = 0,
              needsRecalculation = true,
          )
      )
      assertTableEquals(
          ObservationSubstratumResultsRecord(
              observationId = observationId,
              substratumId = substratumIdA,
              substratumHistoryId = substratumHistoryIdA,
              totalLive = 0,
              totalDead = 0,
              totalExisting = 0,
              permanentLive = 0,
              needsRecalculation = true,
          )
      )
      assertTableEquals(
          ObservationStratumResultsRecord(
              observationId = observationId,
              stratumId = stratumId1,
              stratumHistoryId = stratumHistoryId1,
              totalLive = 0,
              totalDead = 0,
              totalExisting = 0,
              permanentLive = 0,
              needsRecalculation = true,
          )
      )
      assertTableEquals(
          ObservationSiteResultsRecord(
              observationId = observationId,
              plantingSiteId = inserted.plantingSiteId,
              plantingSiteHistoryId = inserted.plantingSiteHistoryId,
              totalLive = 0,
              totalDead = 0,
              totalExisting = 0,
              permanentLive = 0,
              needsRecalculation = true,
          )
      )
    }

    @Test
    fun `flags existing rows without changing their values`() {
      val observationId = insertCompletedObservation(1, plotIdA)
      insertObservationPlotResult(monitoringPlotId = plotIdA, totalLive = 5, survivalRate = 50)
      insertObservationSubstratumResult(
          substratumId = substratumIdA,
          substratumHistoryId = substratumHistoryIdA,
          totalLive = 5,
          survivalRate = 50,
      )

      invalidator.invalidateObservationPlots(observationId, listOf(plotIdA))

      assertEquals(
          5 to 50,
          dslContext
              .select(OBSERVATION_PLOT_RESULTS.TOTAL_LIVE, OBSERVATION_PLOT_RESULTS.SURVIVAL_RATE)
              .from(OBSERVATION_PLOT_RESULTS)
              .fetchOne { it.value1() to it.value2() },
          "Plot result values",
      )
      assertEquals(
          5 to 50,
          dslContext
              .select(
                  OBSERVATION_SUBSTRATUM_RESULTS.TOTAL_LIVE,
                  OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE,
              )
              .from(OBSERVATION_SUBSTRATUM_RESULTS)
              .fetchOne { it.value1() to it.value2() },
          "Substratum result values",
      )
      assertFlagged(
          plots = setOf(observationId to plotIdA),
          substrata = setOf(observationId to substratumHistoryIdA),
          strata = setOf(observationId to stratumHistoryId1),
          sites = setOf(observationId),
      )
    }

    @Test
    fun `only flags rows for the requested plots`() {
      val observationId = insertCompletedObservation(1, plotIdA, plotIdB, plotIdC)
      insertAllResults(observationId, plotIdA, plotIdB, plotIdC)

      invalidator.invalidateObservationPlots(observationId, listOf(plotIdB))

      assertFlagged(
          plots = setOf(observationId to plotIdB),
          substrata = setOf(observationId to substratumHistoryIdB),
          strata = setOf(observationId to stratumHistoryId1),
          sites = setOf(observationId),
      )
    }

    @Test
    fun `ignores plots that are not completed`() {
      val observationId = insertObservation()
      insertObservationPlot(monitoringPlotId = plotIdA, claimedBy = user.userId)

      invalidator.invalidateObservationPlots(observationId, listOf(plotIdA))

      assertFlagged()
    }

    @Test
    fun `does not flag non-plot rows for ad-hoc observations`() {
      val observationId =
          insertObservation(completedTime = Instant.ofEpochSecond(1), isAdHoc = true)
      insertObservationPlot(monitoringPlotId = plotIdA, completedBy = user.userId)

      invalidator.invalidateObservationPlots(observationId, listOf(plotIdA))

      assertFlagged(plots = setOf(observationId to plotIdA))
    }

    @Test
    fun `flags stratum rows of later observations that roll forward a changed substratum`() {
      val observationId1 = insertCompletedObservation(1, plotIdA, plotIdB)
      insertAllResults(observationId1, plotIdA, plotIdB)
      insertObservationDependentSubstrata(observationId1, substratumHistoryIdA)
      insertObservationDependentSubstrata(observationId1, substratumHistoryIdB)
      insertObservationDependentSubstrata(observationId1, substratumHistoryIdC)

      // Observation 2 observes B and C, and rolls A forward from observation 1.
      val observationId2 = insertCompletedObservation(2, plotIdB, plotIdC)
      insertAllResults(observationId2, plotIdB, plotIdC)
      insertObservationDependentSubstrata(
          observationId2,
          substratumHistoryIdA,
          dependsOnObservationId = observationId1,
      )
      insertObservationDependentSubstrata(observationId2, substratumHistoryIdB)
      insertObservationDependentSubstrata(observationId2, substratumHistoryIdC)

      invalidator.invalidateObservationPlots(observationId1, listOf(plotIdA))

      assertFlagged(
          plots = setOf(observationId1 to plotIdA),
          substrata = setOf(observationId1 to substratumHistoryIdA),
          strata = setOf(observationId1 to stratumHistoryId1, observationId2 to stratumHistoryId1),
          sites = setOf(observationId1, observationId2),
      )
    }

    @Test
    fun `does not flag stratum rows of later observations that observed the substratum themselves`() {
      val observationId1 = insertCompletedObservation(1, plotIdA)
      insertAllResults(observationId1, plotIdA)
      insertObservationDependentSubstrata(observationId1, substratumHistoryIdA)

      val observationId2 = insertCompletedObservation(2, plotIdA)
      insertAllResults(observationId2, plotIdA)
      insertObservationDependentSubstrata(observationId2, substratumHistoryIdA)

      invalidator.invalidateObservationPlots(observationId1, listOf(plotIdA))

      assertFlagged(
          plots = setOf(observationId1 to plotIdA),
          substrata = setOf(observationId1 to substratumHistoryIdA),
          strata = setOf(observationId1 to stratumHistoryId1),
          sites = setOf(observationId1),
      )
    }

    @Test
    fun `flags site rows of transitively dependent observations`() {
      val observationId1 = insertCompletedObservation(1, plotIdA, plotIdB, plotIdC)
      insertAllResults(observationId1, plotIdA, plotIdB, plotIdC)
      insertObservationDependentSubstrata(observationId1, substratumHistoryIdA)
      insertObservationDependentSubstrata(observationId1, substratumHistoryIdB)
      insertObservationDependentSubstrata(observationId1, substratumHistoryIdC)

      // Observation 2 observes B and rolls A and C forward from observation 1.
      val observationId2 = insertCompletedObservation(2, plotIdB)
      insertAllResults(observationId2, plotIdB)
      insertObservationDependentSubstrata(
          observationId2,
          substratumHistoryIdA,
          dependsOnObservationId = observationId1,
      )
      insertObservationDependentSubstrata(observationId2, substratumHistoryIdB)
      insertObservationDependentSubstrata(
          observationId2,
          substratumHistoryIdC,
          dependsOnObservationId = observationId1,
      )

      // Observation 3 observes C and A and rolls B forward from observation 2, so it depends on
      // observation 1 only through observation 2.
      val observationId3 = insertCompletedObservation(3, plotIdA, plotIdC)
      insertAllResults(observationId3, plotIdA, plotIdC)
      insertObservationDependentSubstrata(observationId3, substratumHistoryIdA)
      insertObservationDependentSubstrata(
          observationId3,
          substratumHistoryIdB,
          dependsOnObservationId = observationId2,
      )
      insertObservationDependentSubstrata(observationId3, substratumHistoryIdC)

      invalidator.invalidateObservationPlots(observationId1, listOf(plotIdC))

      assertFlagged(
          plots = setOf(observationId1 to plotIdC),
          substrata = setOf(observationId1 to substratumHistoryIdC),
          strata = setOf(observationId1 to stratumHistoryId2),
          sites = setOf(observationId1, observationId2, observationId3),
      )
    }
  }

  @Nested
  inner class InvalidatePlot {
    @Test
    fun `flags the plot in every observation where it was completed`() {
      val observationId1 = insertCompletedObservation(1, plotIdA, plotIdB)
      insertAllResults(observationId1, plotIdA, plotIdB)
      val observationId2 = insertCompletedObservation(2, plotIdA)
      insertAllResults(observationId2, plotIdA)
      val observationId3 = insertCompletedObservation(3, plotIdB)
      insertAllResults(observationId3, plotIdB)

      invalidator.invalidatePlot(plotIdA)

      assertFlagged(
          plots = setOf(observationId1 to plotIdA, observationId2 to plotIdA),
          substrata =
              setOf(observationId1 to substratumHistoryIdA, observationId2 to substratumHistoryIdA),
          strata = setOf(observationId1 to stratumHistoryId1, observationId2 to stratumHistoryId1),
          sites = setOf(observationId1, observationId2),
      )
    }
  }

  @Nested
  inner class InvalidateObservation {
    @Test
    fun `flags every row of the observation`() {
      val observationId1 = insertCompletedObservation(1, plotIdA, plotIdC)
      insertAllResults(observationId1, plotIdA, plotIdC)
      val observationId2 = insertCompletedObservation(2, plotIdA)
      insertAllResults(observationId2, plotIdA)

      invalidator.invalidateObservation(observationId1)

      assertFlagged(
          plots = setOf(observationId1 to plotIdA, observationId1 to plotIdC),
          substrata =
              setOf(observationId1 to substratumHistoryIdA, observationId1 to substratumHistoryIdC),
          strata = setOf(observationId1 to stratumHistoryId1, observationId1 to stratumHistoryId2),
          sites = setOf(observationId1),
      )
    }
  }

  @Nested
  inner class InvalidateStratum {
    @Test
    fun `flags rows for plots in the stratum in every observation`() {
      val observationId1 = insertCompletedObservation(1, plotIdA, plotIdC)
      insertAllResults(observationId1, plotIdA, plotIdC)
      val observationId2 = insertCompletedObservation(2, plotIdC)
      insertAllResults(observationId2, plotIdC)
      val observationId3 = insertCompletedObservation(3, plotIdB)
      insertAllResults(observationId3, plotIdB)

      invalidator.invalidateStratum(stratumId2)

      assertFlagged(
          plots = setOf(observationId1 to plotIdC, observationId2 to plotIdC),
          substrata =
              setOf(observationId1 to substratumHistoryIdC, observationId2 to substratumHistoryIdC),
          strata = setOf(observationId1 to stratumHistoryId2, observationId2 to stratumHistoryId2),
          sites = setOf(observationId1, observationId2),
      )
    }
  }

  @Nested
  inner class InvalidateSite {
    @Test
    fun `flags every row of every observation of the site and nothing in other sites`() {
      val observationId1 = insertCompletedObservation(1, plotIdA, plotIdC)
      insertAllResults(observationId1, plotIdA, plotIdC)
      val observationId2 = insertCompletedObservation(2, plotIdB)
      insertAllResults(observationId2, plotIdB)
      val plantingSiteId = inserted.plantingSiteId

      insertPlantingSite()
      insertStratum()
      insertSubstratum()
      val otherSitePlotId = insertMonitoringPlot(permanentIndex = 1)
      val otherSiteObservationId = insertCompletedObservation(1, otherSitePlotId)
      insertObservationPlotResult(
          observationId = otherSiteObservationId,
          monitoringPlotId = otherSitePlotId,
      )

      invalidator.invalidateSite(plantingSiteId)

      assertFlagged(
          plots =
              setOf(
                  observationId1 to plotIdA,
                  observationId1 to plotIdC,
                  observationId2 to plotIdB,
              ),
          substrata =
              setOf(
                  observationId1 to substratumHistoryIdA,
                  observationId1 to substratumHistoryIdC,
                  observationId2 to substratumHistoryIdB,
              ),
          strata =
              setOf(
                  observationId1 to stratumHistoryId1,
                  observationId1 to stratumHistoryId2,
                  observationId2 to stratumHistoryId1,
              ),
          sites = setOf(observationId1, observationId2),
      )
    }
  }

  @Nested
  inner class EventListeners {
    @Test
    fun `t0 plot data assignment flags the plot in every observation`() {
      val observationId1 = insertCompletedObservation(1, plotIdA, plotIdB)
      insertAllResults(observationId1, plotIdA, plotIdB)
      val observationId2 = insertCompletedObservation(2, plotIdA)
      insertAllResults(observationId2, plotIdA)

      invalidator.on(T0PlotDataAssignedEvent(plotIdA))

      assertFlagged(
          plots = setOf(observationId1 to plotIdA, observationId2 to plotIdA),
          substrata =
              setOf(observationId1 to substratumHistoryIdA, observationId2 to substratumHistoryIdA),
          strata = setOf(observationId1 to stratumHistoryId1, observationId2 to stratumHistoryId1),
          sites = setOf(observationId1, observationId2),
      )
    }

    @Test
    fun `t0 stratum data assignment flags plots in the stratum`() {
      val observationId = insertCompletedObservation(1, plotIdA, plotIdC)
      insertAllResults(observationId, plotIdA, plotIdC)

      invalidator.on(T0StratumDataAssignedEvent(stratumId2))

      assertFlagged(
          plots = setOf(observationId to plotIdC),
          substrata = setOf(observationId to substratumHistoryIdC),
          strata = setOf(observationId to stratumHistoryId2),
          sites = setOf(observationId),
      )
    }
  }

  @Nested
  inner class PlantingSiteNeedsRecalculation {
    @Test
    fun `returns true only for sites with flagged results`() {
      val observationId = insertCompletedObservation(1, plotIdA)
      insertAllResults(observationId, plotIdA)
      val plantingSiteId = inserted.plantingSiteId
      val otherPlantingSiteId = insertPlantingSite()

      assertEquals(
          false,
          invalidator.plantingSiteNeedsRecalculation(plantingSiteId),
          "Before invalidation",
      )

      invalidator.invalidatePlot(plotIdA)

      assertEquals(
          true to false,
          invalidator.plantingSiteNeedsRecalculation(plantingSiteId) to
              invalidator.plantingSiteNeedsRecalculation(otherPlantingSiteId),
          "After invalidation (flagged site, other site)",
      )
    }
  }

  private fun insertCompletedObservation(
      completedSeconds: Long,
      vararg plotIds: MonitoringPlotId,
  ): ObservationId {
    val observationId = insertObservation(completedTime = Instant.ofEpochSecond(completedSeconds))
    plotIds.forEach { plotId ->
      insertObservationPlot(
          monitoringPlotId = plotId,
          completedBy = user.userId,
          isPermanent = true,
      )
    }
    return observationId
  }

  /** Inserts unflagged results rows at every level for the given plots of an observation. */
  private fun insertAllResults(observationId: ObservationId, vararg plotIds: MonitoringPlotId) {
    val substratumHistoryIds =
        mapOf(
            plotIdA to substratumHistoryIdA,
            plotIdB to substratumHistoryIdB,
            plotIdC to substratumHistoryIdC,
        )
    val substratumIds =
        mapOf(plotIdA to substratumIdA, plotIdB to substratumIdB, plotIdC to substratumIdC)
    val stratumHistoryIds =
        mapOf(
            plotIdA to stratumHistoryId1,
            plotIdB to stratumHistoryId1,
            plotIdC to stratumHistoryId2,
        )
    val stratumIds = mapOf(plotIdA to stratumId1, plotIdB to stratumId1, plotIdC to stratumId2)

    plotIds.forEach { plotId ->
      insertObservationPlotResult(observationId = observationId, monitoringPlotId = plotId)
      insertObservationSubstratumResult(
          observationId = observationId,
          substratumId = substratumIds[plotId],
          substratumHistoryId = substratumHistoryIds[plotId]!!,
      )
    }
    plotIds
        .map { stratumIds[it]!! to stratumHistoryIds[it]!! }
        .distinct()
        .forEach { (stratumId, stratumHistoryId) ->
          insertObservationStratumResult(
              observationId = observationId,
              stratumId = stratumId,
              stratumHistoryId = stratumHistoryId,
          )
        }
    insertObservationSiteResult(observationId = observationId)
  }

  private fun assertFlagged(
      plots: Set<Pair<ObservationId, MonitoringPlotId>> = emptySet(),
      substrata: Set<Pair<ObservationId, SubstratumHistoryId>> = emptySet(),
      strata: Set<Pair<ObservationId, StratumHistoryId>> = emptySet(),
      sites: Set<ObservationId> = emptySet(),
  ) {
    assertEquals(
        mapOf(
            "plots" to plots,
            "substrata" to substrata,
            "strata" to strata,
            "sites" to sites,
        ),
        mapOf(
            "plots" to
                with(OBSERVATION_PLOT_RESULTS) {
                  dslContext
                      .select(OBSERVATION_ID, MONITORING_PLOT_ID)
                      .from(this)
                      .where(NEEDS_RECALCULATION)
                      .fetchSet { it.value1()!! to it.value2()!! }
                },
            "substrata" to
                with(OBSERVATION_SUBSTRATUM_RESULTS) {
                  dslContext
                      .select(OBSERVATION_ID, SUBSTRATUM_HISTORY_ID)
                      .from(this)
                      .where(NEEDS_RECALCULATION)
                      .fetchSet { it.value1()!! to it.value2()!! }
                },
            "strata" to
                with(OBSERVATION_STRATUM_RESULTS) {
                  dslContext
                      .select(OBSERVATION_ID, STRATUM_HISTORY_ID)
                      .from(this)
                      .where(NEEDS_RECALCULATION)
                      .fetchSet { it.value1()!! to it.value2()!! }
                },
            "sites" to
                with(OBSERVATION_SITE_RESULTS) {
                  dslContext.select(OBSERVATION_ID).from(this).where(NEEDS_RECALCULATION).fetchSet {
                    it.value1()!!
                  }
                },
        ),
        "Flagged results rows",
    )
  }
}
