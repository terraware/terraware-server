package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_DEPENDENT_SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.tracking.event.PlantingSiteMapEditedEvent
import com.terraformation.backend.tracking.event.SurvivalRateIncludesTempPlotsChangedEvent
import com.terraformation.backend.tracking.event.T0PlotDataAssignedEvent
import com.terraformation.backend.tracking.event.T0StratumDataAssignedEvent
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.event.EventListener

/**
 * Marks observation results rows as needing recalculation. Callers should invoke these methods in
 * the same transaction as the data change that makes the results stale.
 *
 * A flagged results row means the row itself and all the species totals rows for the same
 * observation and scope will be recalculated. If a results row doesn't exist yet, a placeholder
 * with zero counts is inserted so there is somewhere to put the flag.
 */
@Named
class ObservationResultsInvalidator(private val dslContext: DSLContext) {
  /**
   * Flags the results that depend on a monitoring plot in every observation where the plot was
   * completed.
   */
  fun invalidatePlot(monitoringPlotId: MonitoringPlotId) {
    invalidate(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
  }

  /** Flags the results that depend on specific plots in a single observation. */
  fun invalidateObservationPlots(
      observationId: ObservationId,
      monitoringPlotIds: Collection<MonitoringPlotId>,
  ) {
    if (monitoringPlotIds.isNotEmpty()) {
      invalidate(
          DSL.and(
              OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId),
              OBSERVATION_PLOTS.MONITORING_PLOT_ID.`in`(monitoringPlotIds),
          )
      )
    }
  }

  /** Flags all the results of an observation, along with the observations that depend on it. */
  fun invalidateObservation(observationId: ObservationId) {
    invalidate(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
  }

  /** Flags results of plots that were in the stratum when observed. */
  fun invalidateStratum(stratumId: StratumId) {
    invalidate(
        OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID.`in`(
            DSL.select(MONITORING_PLOT_HISTORIES.ID)
                .from(MONITORING_PLOT_HISTORIES)
                .join(SUBSTRATUM_HISTORIES)
                .on(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID.eq(SUBSTRATUM_HISTORIES.ID))
                .join(STRATUM_HISTORIES)
                .on(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(STRATUM_HISTORIES.ID))
                .where(STRATUM_HISTORIES.STRATUM_ID.eq(stratumId))
        )
    )
  }

  /** Flags all the results of all the observations of a planting site. */
  fun invalidateSite(plantingSiteId: PlantingSiteId) {
    invalidate(
        OBSERVATION_PLOTS.OBSERVATION_ID.`in`(
            DSL.select(OBSERVATIONS.ID)
                .from(OBSERVATIONS)
                .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
        )
    )
  }

  /** Flags all the results of every planting site. */
  fun invalidateAllSites() {
    invalidate(DSL.trueCondition())
  }

  @EventListener
  fun on(event: T0PlotDataAssignedEvent) {
    invalidatePlot(event.monitoringPlotId)
  }

  @EventListener
  fun on(event: T0StratumDataAssignedEvent) {
    invalidateStratum(event.stratumId)
  }

  @EventListener
  fun on(event: SurvivalRateIncludesTempPlotsChangedEvent) {
    invalidateSite(event.plantingSiteId)
  }

  @EventListener
  fun on(event: PlantingSiteMapEditedEvent) {
    invalidateSite(event.edited.id)
  }

  /** Returns true if any of a planting site's results rows are flagged for recalculation. */
  fun plantingSiteNeedsRecalculation(plantingSiteId: PlantingSiteId): Boolean {
    return dslContext.fetchExists(
        DSL.selectOne()
            .from(OBSERVATIONS)
            .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
            .and(observationNeedsRecalculationCondition)
    )
  }

  /** Returns the planting sites that have any results rows flagged for recalculation. */
  fun fetchPlantingSiteIdsNeedingRecalculation(): List<PlantingSiteId> {
    return dslContext
        .select(OBSERVATIONS.PLANTING_SITE_ID.asNonNullable())
        .from(OBSERVATIONS)
        .where(observationNeedsRecalculationCondition)
        .groupBy(OBSERVATIONS.PLANTING_SITE_ID)
        .orderBy(OBSERVATIONS.PLANTING_SITE_ID)
        .fetch(OBSERVATIONS.PLANTING_SITE_ID.asNonNullable())
  }

  /** Clears the recalculation flags on all the results rows of a planting site's observations. */
  fun clearRecalculationFlags(plantingSiteId: PlantingSiteId) {
    val siteObservationIds =
        DSL.select(OBSERVATIONS.ID)
            .from(OBSERVATIONS)
            .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))

    listOf(
            OBSERVATION_PLOT_RESULTS.OBSERVATION_ID to OBSERVATION_PLOT_RESULTS.NEEDS_RECALCULATION,
            OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID to
                OBSERVATION_SUBSTRATUM_RESULTS.NEEDS_RECALCULATION,
            OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID to
                OBSERVATION_STRATUM_RESULTS.NEEDS_RECALCULATION,
            OBSERVATION_SITE_RESULTS.OBSERVATION_ID to OBSERVATION_SITE_RESULTS.NEEDS_RECALCULATION,
        )
        .forEach { (observationIdField, needsRecalculationField) ->
          dslContext
              .update(needsRecalculationField.table!!)
              .set(needsRecalculationField, false)
              .where(observationIdField.`in`(siteObservationIds))
              .and(needsRecalculationField)
              .execute()
        }
  }

  private val observationNeedsRecalculationCondition: Condition
    get() =
        DSL.or(
            listOf(
                    OBSERVATION_PLOT_RESULTS.OBSERVATION_ID to
                        OBSERVATION_PLOT_RESULTS.NEEDS_RECALCULATION,
                    OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID to
                        OBSERVATION_SUBSTRATUM_RESULTS.NEEDS_RECALCULATION,
                    OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID to
                        OBSERVATION_STRATUM_RESULTS.NEEDS_RECALCULATION,
                    OBSERVATION_SITE_RESULTS.OBSERVATION_ID to
                        OBSERVATION_SITE_RESULTS.NEEDS_RECALCULATION,
                )
                .map { (observationIdField, needsRecalculationField) ->
                  DSL.exists(
                      DSL.selectOne()
                          .from(needsRecalculationField.table)
                          .where(observationIdField.eq(OBSERVATIONS.ID))
                          .and(needsRecalculationField)
                  )
                }
        )

  /**
   * Flags the results rows affected by a set of observation plots, identified by a condition on
   * [OBSERVATION_PLOTS]. Only completed plots of monitoring observations are considered.
   */
  private fun invalidate(observationPlotsCondition: Condition) {
    val changedPlotsCondition =
        DSL.and(
            observationPlotsCondition,
            OBSERVATION_PLOTS.STATUS_ID.eq(ObservationPlotStatus.Completed),
            OBSERVATIONS.OBSERVATION_TYPE_ID.eq(ObservationType.Monitoring),
        )
    val nonAdHocCondition = DSL.and(changedPlotsCondition, OBSERVATIONS.IS_AD_HOC.isFalse)

    flagPlotResults(changedPlotsCondition)
    flagSubstratumResults(nonAdHocCondition)
    flagStratumResults(nonAdHocCondition)
    flagSiteResults(nonAdHocCondition)
    flagDependentObservations(nonAdHocCondition)
  }

  private fun flagPlotResults(changedPlotsCondition: Condition) {
    with(OBSERVATION_PLOT_RESULTS) {
      dslContext
          .insertInto(
              OBSERVATION_PLOT_RESULTS,
              OBSERVATION_ID,
              MONITORING_PLOT_ID,
              MONITORING_PLOT_HISTORY_ID,
              TOTAL_LIVE,
              TOTAL_DEAD,
              TOTAL_EXISTING,
              PERMANENT_LIVE,
              NEEDS_RECALCULATION,
          )
          .select(
              DSL.select(
                      OBSERVATION_PLOTS.OBSERVATION_ID,
                      OBSERVATION_PLOTS.MONITORING_PLOT_ID,
                      OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID,
                      DSL.inline(0),
                      DSL.inline(0),
                      DSL.inline(0),
                      DSL.inline(0),
                      DSL.inline(true),
                  )
                  .from(OBSERVATION_PLOTS)
                  .join(OBSERVATIONS)
                  .on(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .where(changedPlotsCondition)
          )
          .onConflict(OBSERVATION_ID, MONITORING_PLOT_ID)
          .doUpdate()
          .set(NEEDS_RECALCULATION, true)
          .execute()
    }
  }

  private fun flagSubstratumResults(changedPlotsCondition: Condition) {
    with(OBSERVATION_SUBSTRATUM_RESULTS) {
      dslContext
          .insertInto(
              OBSERVATION_SUBSTRATUM_RESULTS,
              OBSERVATION_ID,
              SUBSTRATUM_HISTORY_ID,
              SUBSTRATUM_ID,
              TOTAL_LIVE,
              TOTAL_DEAD,
              TOTAL_EXISTING,
              PERMANENT_LIVE,
              NEEDS_RECALCULATION,
          )
          .select(
              DSL.selectDistinct(
                      OBSERVATION_PLOTS.OBSERVATION_ID,
                      SUBSTRATUM_HISTORIES.ID,
                      SUBSTRATUM_HISTORIES.SUBSTRATUM_ID,
                      DSL.inline(0),
                      DSL.inline(0),
                      DSL.inline(0),
                      DSL.inline(0),
                      DSL.inline(true),
                  )
                  .from(OBSERVATION_PLOTS)
                  .join(OBSERVATIONS)
                  .on(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .join(MONITORING_PLOT_HISTORIES)
                  .on(OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID.eq(MONITORING_PLOT_HISTORIES.ID))
                  .join(SUBSTRATUM_HISTORIES)
                  .on(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID.eq(SUBSTRATUM_HISTORIES.ID))
                  .where(changedPlotsCondition)
          )
          .onConflict(OBSERVATION_ID, SUBSTRATUM_HISTORY_ID)
          .doUpdate()
          .set(NEEDS_RECALCULATION, true)
          .execute()
    }
  }

  private fun flagStratumResults(changedPlotsCondition: Condition) {
    with(OBSERVATION_STRATUM_RESULTS) {
      dslContext
          .insertInto(
              OBSERVATION_STRATUM_RESULTS,
              OBSERVATION_ID,
              STRATUM_HISTORY_ID,
              STRATUM_ID,
              TOTAL_LIVE,
              TOTAL_DEAD,
              TOTAL_EXISTING,
              PERMANENT_LIVE,
              NEEDS_RECALCULATION,
          )
          .select(
              DSL.selectDistinct(
                      OBSERVATION_PLOTS.OBSERVATION_ID,
                      STRATUM_HISTORIES.ID,
                      STRATUM_HISTORIES.STRATUM_ID,
                      DSL.inline(0),
                      DSL.inline(0),
                      DSL.inline(0),
                      DSL.inline(0),
                      DSL.inline(true),
                  )
                  .from(OBSERVATION_PLOTS)
                  .join(OBSERVATIONS)
                  .on(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .join(MONITORING_PLOT_HISTORIES)
                  .on(OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID.eq(MONITORING_PLOT_HISTORIES.ID))
                  .join(SUBSTRATUM_HISTORIES)
                  .on(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID.eq(SUBSTRATUM_HISTORIES.ID))
                  .join(STRATUM_HISTORIES)
                  .on(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(STRATUM_HISTORIES.ID))
                  .where(changedPlotsCondition)
          )
          .onConflict(OBSERVATION_ID, STRATUM_HISTORY_ID)
          .doUpdate()
          .set(NEEDS_RECALCULATION, true)
          .execute()
    }
  }

  private fun flagSiteResults(changedPlotsCondition: Condition) {
    with(OBSERVATION_SITE_RESULTS) {
      dslContext
          .insertInto(
              OBSERVATION_SITE_RESULTS,
              OBSERVATION_ID,
              PLANTING_SITE_ID,
              PLANTING_SITE_HISTORY_ID,
              TOTAL_LIVE,
              TOTAL_DEAD,
              TOTAL_EXISTING,
              PERMANENT_LIVE,
              NEEDS_RECALCULATION,
          )
          .select(
              DSL.selectDistinct(
                      OBSERVATIONS.ID,
                      OBSERVATIONS.PLANTING_SITE_ID,
                      OBSERVATIONS.PLANTING_SITE_HISTORY_ID,
                      DSL.inline(0),
                      DSL.inline(0),
                      DSL.inline(0),
                      DSL.inline(0),
                      DSL.inline(true),
                  )
                  .from(OBSERVATION_PLOTS)
                  .join(OBSERVATIONS)
                  .on(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .where(changedPlotsCondition)
                  .and(OBSERVATIONS.PLANTING_SITE_HISTORY_ID.isNotNull)
          )
          .onConflict(OBSERVATION_ID)
          .doUpdate()
          .set(NEEDS_RECALCULATION, true)
          .execute()
    }
  }

  /**
   * Flags results in later observations that roll forward data from the changed observations.
   *
   * A later observation's stratum results change if it rolls forward one of the changed substrata.
   * Its site results can change if it depends on a changed observation for any substratum, since
   * site results roll forward stratum rows, and recalculating a dependent observation changes the
   * stratum rows its own dependents read, so the set of dependent observations is closed
   * transitively.
   */
  private fun flagDependentObservations(changedPlotsCondition: Condition) {
    val changedObservationIds =
        dslContext
            .selectDistinct(OBSERVATION_PLOTS.OBSERVATION_ID.asNonNullable())
            .from(OBSERVATION_PLOTS)
            .join(OBSERVATIONS)
            .on(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
            .where(changedPlotsCondition)
            .fetchSet(OBSERVATION_PLOTS.OBSERVATION_ID.asNonNullable())

    val dependentObservationIds = mutableSetOf<ObservationId>()
    var dependencySources: Set<ObservationId> = changedObservationIds

    while (dependencySources.isNotEmpty()) {
      dependencySources =
          dslContext
              .selectDistinct(OBSERVATION_DEPENDENT_SUBSTRATA.OBSERVATION_ID.asNonNullable())
              .from(OBSERVATION_DEPENDENT_SUBSTRATA)
              .join(OBSERVATIONS)
              .on(OBSERVATION_DEPENDENT_SUBSTRATA.OBSERVATION_ID.eq(OBSERVATIONS.ID))
              .where(
                  OBSERVATION_DEPENDENT_SUBSTRATA.DEPENDS_ON_OBSERVATION_ID.`in`(dependencySources)
              )
              .and(OBSERVATIONS.OBSERVATION_TYPE_ID.eq(ObservationType.Monitoring))
              .fetchSet(OBSERVATION_DEPENDENT_SUBSTRATA.OBSERVATION_ID.asNonNullable())
              .minus(changedObservationIds)
              .minus(dependentObservationIds)

      dependentObservationIds += dependencySources
    }

    if (dependentObservationIds.isEmpty()) {
      return
    }

    val changedSubstratumPlotHistories = MONITORING_PLOT_HISTORIES.`as`("changed_mph")

    dslContext
        .update(OBSERVATION_STRATUM_RESULTS)
        .set(OBSERVATION_STRATUM_RESULTS.NEEDS_RECALCULATION, true)
        .where(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID.`in`(dependentObservationIds))
        .and(
            OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID.`in`(
                DSL.select(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID)
                    .from(OBSERVATION_DEPENDENT_SUBSTRATA)
                    .join(SUBSTRATUM_HISTORIES)
                    .on(
                        OBSERVATION_DEPENDENT_SUBSTRATA.SUBSTRATUM_HISTORY_ID.eq(
                            SUBSTRATUM_HISTORIES.ID
                        )
                    )
                    .where(
                        OBSERVATION_DEPENDENT_SUBSTRATA.OBSERVATION_ID.eq(
                            OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID
                        )
                    )
                    .andExists(
                        DSL.selectOne()
                            .from(OBSERVATION_PLOTS)
                            .join(OBSERVATIONS)
                            .on(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                            .join(changedSubstratumPlotHistories)
                            .on(
                                OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID.eq(
                                    changedSubstratumPlotHistories.ID
                                )
                            )
                            .where(changedPlotsCondition)
                            .and(
                                OBSERVATION_PLOTS.OBSERVATION_ID.eq(
                                    OBSERVATION_DEPENDENT_SUBSTRATA.DEPENDS_ON_OBSERVATION_ID
                                )
                            )
                            .and(
                                changedSubstratumPlotHistories.SUBSTRATUM_HISTORY_ID.eq(
                                    OBSERVATION_DEPENDENT_SUBSTRATA.DEPENDS_ON_SUBSTRATUM_HISTORY_ID
                                )
                            )
                    )
            )
        )
        .execute()

    dslContext
        .update(OBSERVATION_SITE_RESULTS)
        .set(OBSERVATION_SITE_RESULTS.NEEDS_RECALCULATION, true)
        .where(OBSERVATION_SITE_RESULTS.OBSERVATION_ID.`in`(dependentObservationIds))
        .execute()
  }
}
