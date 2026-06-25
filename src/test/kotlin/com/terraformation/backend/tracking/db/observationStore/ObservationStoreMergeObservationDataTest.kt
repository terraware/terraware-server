package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.records.DependentSubstratumObservationRecord
import com.terraformation.backend.db.tracking.tables.references.DEPENDENT_SUBSTRATUM_OBSERVATION
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_CONDITIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_COORDINATES
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_PLANTS
import com.terraformation.backend.point
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ObservationStoreMergeObservationDataTest : BaseObservationStoreTest() {
  private lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setUpSite() {
    helper.insertPlantedSite(numPermanentPlots = 2)
    speciesId = inserted.speciesId
  }

  @Test
  fun `moves completed source plot data into the target and deletes the source`() {
    val plotA = insertMonitoringPlot()
    val plotB = insertMonitoringPlot()
    val sourceObservationId = insertObservation()
    val targetObservationId = insertObservation()

    completePlotWith(sourceObservationId, plotA, 1)
    completePlotWith(targetObservationId, plotB, 1)

    store.mergeObservationData(sourceObservationId, targetObservationId)

    assertEquals(
        setOf(plotA, plotB),
        dslContext
            .select(OBSERVATION_PLOTS.MONITORING_PLOT_ID)
            .from(OBSERVATION_PLOTS)
            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(targetObservationId))
            .fetchSet(OBSERVATION_PLOTS.MONITORING_PLOT_ID),
        "Target observation plots",
    )
    assertTableEmpty(OBSERVATIONS, where = OBSERVATIONS.ID.eq(sourceObservationId))
    assertTableEmpty(
        OBSERVATION_PLOTS,
        where = OBSERVATION_PLOTS.OBSERVATION_ID.eq(sourceObservationId),
    )
    assertTableEmpty(
        RECORDED_PLANTS,
        where = RECORDED_PLANTS.OBSERVATION_ID.eq(sourceObservationId),
    )
  }

  @Test
  fun `realigns the target completed time with a later merged-in source plot`() {
    val plotA = insertMonitoringPlot()
    val plotB = insertMonitoringPlot()
    val targetObservationId = insertObservation()
    val sourceObservationId = insertObservation()

    // The target completed earlier than the source it absorbs.
    clock.instant = Instant.ofEpochSecond(100)
    completePlotWith(targetObservationId, plotB, 1)
    clock.instant = Instant.ofEpochSecond(500)
    completePlotWith(sourceObservationId, plotA, 1)

    store.mergeObservationData(sourceObservationId, targetObservationId)

    // Dependency ordering ranks candidate sources by completed_time, so the target must adopt the
    // absorbed plot's later completion time; otherwise a later observation could roll forward an
    // observation completed between the target and the source instead of the merged data.
    val targetCompletedTime =
        dslContext
            .select(OBSERVATIONS.COMPLETED_TIME)
            .from(OBSERVATIONS)
            .where(OBSERVATIONS.ID.eq(targetObservationId))
            .fetchOne(OBSERVATIONS.COMPLETED_TIME)
    assertEquals(
        Instant.ofEpochSecond(500),
        targetCompletedTime,
        "Target completed_time after absorbing the later source plot",
    )
  }

  @Test
  fun `conflict plot overwrites target data and drops t0 when target was the t0 observation`() {
    val plotId = insertMonitoringPlot()
    val sourceObservationId = insertObservation()
    val targetObservationId = insertObservation()

    completePlotWith(sourceObservationId, plotId, 2)
    val expectedRecordedPlants =
        dslContext.fetch(RECORDED_PLANTS).onEach { it.observationId = targetObservationId }
    completePlotWith(targetObservationId, plotId, 1)

    insertPlotT0Density(monitoringPlotId = plotId)
    insertPlotT0Observation(monitoringPlotId = plotId, observationId = targetObservationId)

    store.mergeObservationData(sourceObservationId, targetObservationId)

    assertTableEmpty(PLOT_T0_OBSERVATIONS)
    assertTableEmpty(PLOT_T0_DENSITIES)
    assertTableEquals(expectedRecordedPlants)
  }

  @Test
  fun `repoints t0 to the target when the source was the t0 observation`() {
    val plotId = insertMonitoringPlot()
    val sourceObservationId = insertObservation()
    val targetObservationId = insertObservation()

    completePlotWith(sourceObservationId, plotId, 1)

    insertPlotT0Density()
    insertPlotT0Observation(observationId = sourceObservationId)

    // T0 density for plot shouldn't be changed by the merge, but T0 observation should.
    val expectedT0Density = dslContext.fetchSingle(PLOT_T0_DENSITIES)
    val expectedT0Observations =
        dslContext.fetchSingle(PLOT_T0_OBSERVATIONS).apply { observationId = targetObservationId }

    store.mergeObservationData(sourceObservationId, targetObservationId)

    assertTableEquals(expectedT0Observations)
    assertTableEquals(expectedT0Density)
  }

  @Test
  fun `leaves t0 untouched when a third observation is the t0 observation`() {
    val plotId = insertMonitoringPlot()
    val t0ObservationId = insertObservation()
    val sourceObservationId = insertObservation()
    val targetObservationId = insertObservation()

    completePlotWith(t0ObservationId, plotId, 1)
    completePlotWith(targetObservationId, plotId, 1)
    completePlotWith(sourceObservationId, plotId, 1)

    insertPlotT0Density()
    insertPlotT0Observation(observationId = t0ObservationId)

    val expectedT0Densities = dslContext.fetch(PLOT_T0_DENSITIES)
    val expectedT0Observations = dslContext.fetch(PLOT_T0_OBSERVATIONS)

    store.mergeObservationData(sourceObservationId, targetObservationId)

    assertTableEquals(expectedT0Densities)
    assertTableEquals(expectedT0Observations)
  }

  @Test
  fun `reparents source child data to the target`() {
    val plotId = insertMonitoringPlot()
    val sourceObservationId = insertObservation()
    val targetObservationId = insertObservation()
    insertObservationPlot(observationId = sourceObservationId, claimedBy = user.userId)

    store.completePlot(
        sourceObservationId,
        plotId,
        setOf(ObservableCondition.AnimalDamage),
        null,
        Instant.EPOCH,
        listOf(livePlant()),
    )

    insertFile()
    insertObservationMediaFile(observationId = sourceObservationId)
    insertObservedCoordinates(observationId = sourceObservationId)

    val expectedObservationMediaFiles =
        dslContext.fetchSingle(OBSERVATION_MEDIA_FILES).apply {
          observationId = targetObservationId
        }
    val expectedPlotConditions =
        dslContext.fetchSingle(OBSERVATION_PLOT_CONDITIONS).apply {
          observationId = targetObservationId
        }
    val expectedPlotCoordinates =
        dslContext.fetchSingle(OBSERVED_PLOT_COORDINATES).apply {
          observationId = targetObservationId
        }

    store.mergeObservationData(sourceObservationId, targetObservationId)

    assertTableEquals(expectedObservationMediaFiles)
    assertTableEquals(expectedPlotConditions)
    assertTableEquals(expectedPlotCoordinates)
  }

  @Test
  fun `excludes incomplete source plots from the move and drops them with the source`() {
    val completedPlot = insertMonitoringPlot()
    val incompletePlot = insertMonitoringPlot()
    val incompletePlotHistoryId = inserted.monitoringPlotHistoryId
    val sourceObservationId = insertObservation()
    val targetObservationId = insertObservation()

    completePlotWith(sourceObservationId, completedPlot, 1)

    val expectedObservationPlots =
        dslContext.fetchSingle(OBSERVATION_PLOTS).apply { observationId = targetObservationId }

    insertObservationPlot(
        observationId = sourceObservationId,
        monitoringPlotId = incompletePlot,
        monitoringPlotHistoryId = incompletePlotHistoryId,
    )

    store.mergeObservationData(sourceObservationId, targetObservationId)

    assertTableEquals(expectedObservationPlots)
    assertTableEmpty(OBSERVATIONS, where = OBSERVATIONS.ID.eq(sourceObservationId))
  }

  @Test
  fun `recalculateObservationTotals reproduces the totals from scratch`() {
    val observationId = insertObservation()
    val plotId1 = insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId)
    val plotId2 = insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId)
    val speciesId2 = insertSpecies()

    store.completePlot(
        observationId,
        plotId1,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(livePlant(), deadPlant()),
    )

    store.completePlot(
        observationId,
        plotId2,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(livePlant(speciesId), livePlant(speciesId2), livePlant(speciesId2)),
    )

    val expectedTotals = helper.fetchAllTotals()
    val expectedResults = helper.fetchAllResults()

    dslContext
        .update(OBSERVED_SITE_SPECIES_TOTALS)
        .set(OBSERVED_SITE_SPECIES_TOTALS.TOTAL_LIVE, 9999)
        .execute()
    dslContext
        .update(OBSERVED_STRATUM_SPECIES_TOTALS)
        .set(OBSERVED_STRATUM_SPECIES_TOTALS.TOTAL_LIVE, 8888)
        .execute()
    dslContext
        .update(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
        .set(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.TOTAL_LIVE, 7777)
        .execute()
    dslContext
        .update(OBSERVED_PLOT_SPECIES_TOTALS)
        .set(OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_LIVE, 6666)
        .execute()
    dslContext
        .update(OBSERVATION_SITE_RESULTS)
        .set(OBSERVATION_SITE_RESULTS.TOTAL_LIVE, 9898)
        .execute()
    dslContext
        .update(OBSERVATION_STRATUM_RESULTS)
        .set(OBSERVATION_STRATUM_RESULTS.TOTAL_LIVE, 8787)
        .execute()
    dslContext
        .update(OBSERVATION_SUBSTRATUM_RESULTS)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.TOTAL_LIVE, 7676)
        .execute()
    dslContext
        .update(OBSERVATION_PLOT_RESULTS)
        .set(OBSERVATION_PLOT_RESULTS.TOTAL_LIVE, 6565)
        .execute()

    store.recalculateObservationTotals(observationId)

    assertEquals(expectedTotals, helper.fetchAllTotals(), "Species totals after rebuild")
    assertEquals(expectedResults, helper.fetchAllResults(), "Observation results after rebuild")
  }

  @Test
  fun `merge folds the source's substratum into the target's dependencies`() {
    val substratumA = inserted.substratumId
    val substratumAHistory = inserted.substratumHistoryId
    val substratumB = insertSubstratum()
    val substratumBHistory = inserted.substratumHistoryId
    val plotA = insertMonitoringPlot(substratumId = substratumA)
    val plotB = insertMonitoringPlot(substratumId = substratumB)

    // Each observation covers only part of the site: the target observes substratum A, the source
    // observes substratum B.
    clock.instant = Instant.ofEpochSecond(1)
    val target = insertObservation()
    completePlotWith(target, plotA, 1)

    clock.instant = Instant.ofEpochSecond(2)
    val source = insertObservation()
    completePlotWith(source, plotB, 1)

    store.mergeObservationData(source, target)

    // The target absorbed the source's B plot, so it now self-references both substrata.
    assertTableEquals(
        listOf(
            DependentSubstratumObservationRecord(
                target,
                substratumAHistory,
                target,
                substratumAHistory,
            ),
            DependentSubstratumObservationRecord(
                target,
                substratumBHistory,
                target,
                substratumBHistory,
            ),
        ),
        "Target self-references both substrata after the merge",
    )
    assertTableEmpty(
        DEPENDENT_SUBSTRATUM_OBSERVATION,
        "No dependency rows reference the deleted source",
        where = DEPENDENT_SUBSTRATUM_OBSERVATION.DEPENDS_ON_OBSERVATION_ID.eq(source),
    )
  }

  @Test
  fun `merge recomputes dependencies of observations that rolled the source forward`() {
    val substratumA = inserted.substratumId
    val substratumAHistory = inserted.substratumHistoryId
    val substratumB = insertSubstratum()
    val substratumBHistory = inserted.substratumHistoryId
    val plotA = insertMonitoringPlot(substratumId = substratumA)
    val plotB = insertMonitoringPlot(substratumId = substratumB)
    val plotA2 = insertMonitoringPlot(substratumId = substratumA)

    // Partial-site observations: target observes A, source observes B (the latest B data), and a
    // later observation observes only A so it rolls B forward from the source.
    clock.instant = Instant.ofEpochSecond(1)
    val target = insertObservation()
    completePlotWith(target, plotA, 1)

    clock.instant = Instant.ofEpochSecond(2)
    val source = insertObservation()
    completePlotWith(source, plotB, 1)

    clock.instant = Instant.ofEpochSecond(3)
    val later = insertObservation()
    completePlotWith(later, plotA2, 1)

    // Before the merge, the later observation rolls B forward from the source. The source itself
    // rolls A forward from the target (it never observed A), and every observation has a row for
    // each substratum in its snapshot.
    assertTableEquals(
        listOf(
            DependentSubstratumObservationRecord(
                target,
                substratumAHistory,
                target,
                substratumAHistory,
            ),
            DependentSubstratumObservationRecord(
                source,
                substratumAHistory,
                target,
                substratumAHistory,
            ),
            DependentSubstratumObservationRecord(
                source,
                substratumBHistory,
                source,
                substratumBHistory,
            ),
            DependentSubstratumObservationRecord(
                later,
                substratumAHistory,
                later,
                substratumAHistory,
            ),
            DependentSubstratumObservationRecord(
                later,
                substratumBHistory,
                source,
                substratumBHistory,
            ),
        ),
        "Later observation rolls B forward from the source before the merge",
    )

    store.mergeObservationData(source, target)

    // The target absorbed B, so the later observation must now roll B forward from the target
    // rather than from the deleted source (which would otherwise drop the substratum).
    assertTableEquals(
        listOf(
            DependentSubstratumObservationRecord(
                target,
                substratumAHistory,
                target,
                substratumAHistory,
            ),
            DependentSubstratumObservationRecord(
                target,
                substratumBHistory,
                target,
                substratumBHistory,
            ),
            DependentSubstratumObservationRecord(
                later,
                substratumAHistory,
                later,
                substratumAHistory,
            ),
            DependentSubstratumObservationRecord(
                later,
                substratumBHistory,
                target,
                substratumBHistory,
            ),
        ),
        "Later observation rolls B forward from the target after the merge",
    )
  }

  private fun livePlant(speciesId: SpeciesId = this.speciesId) =
      RecordedPlantsRow(
          certaintyId = RecordedSpeciesCertainty.Known,
          gpsCoordinates = point(1),
          speciesId = speciesId,
          statusId = RecordedPlantStatus.Live,
      )

  private fun deadPlant(speciesId: SpeciesId = this.speciesId) =
      RecordedPlantsRow(
          certaintyId = RecordedSpeciesCertainty.Known,
          gpsCoordinates = point(1),
          speciesId = speciesId,
          statusId = RecordedPlantStatus.Dead,
      )

  /**
   * Adds [plotId] to [observationId] as a claimed plot and completes it with [plantCount] plants.
   */
  private fun completePlotWith(
      observationId: ObservationId,
      plotId: MonitoringPlotId,
      plantCount: Int,
  ) {
    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = plotId,
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
    )
    store.completePlot(
        observationId,
        plotId,
        emptySet(),
        null,
        Instant.EPOCH,
        List(plantCount) { livePlant() },
    )
  }
}
