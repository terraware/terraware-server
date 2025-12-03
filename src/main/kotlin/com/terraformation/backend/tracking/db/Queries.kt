package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_REQUESTED_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_HISTORIES
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL

/**
 * Retrieves the most recent observationId for a subzone, with a completed time less than or equal
 * to the given observationId's completed time.
 *
 * The subzone in question is assumed to be OBSERVED_SUBZONE_SPECIES_TOTALS.PLANTING_SUBZONE_ID.
 */
fun latestObservationForSubzoneField(observationIdField: Field<ObservationId?>) =
    with(OBSERVED_SUBZONE_SPECIES_TOTALS) {
      DSL.select(OBSERVATIONS.ID)
          .from(OBSERVATIONS)
          .join(OBSERVATION_REQUESTED_SUBZONES)
          .on(OBSERVATIONS.ID.eq(OBSERVATION_REQUESTED_SUBZONES.OBSERVATION_ID))
          .where(OBSERVATION_REQUESTED_SUBZONES.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONE_ID))
          .and(
              OBSERVATIONS.COMPLETED_TIME.le(
                  DSL.select(OBSERVATIONS.COMPLETED_TIME)
                      .from(OBSERVATIONS)
                      .where(OBSERVATIONS.ID.eq(observationIdField))
              )
          )
          .and(OBSERVATIONS.IS_AD_HOC.isFalse)
          .orderBy(
              // Prefer results from the same observation as the `observationId` if it exists.
              // True is considered greater than false, so sorting by an "=" expression in
              // descending order means the matches come first.
              OBSERVATIONS.ID.eq(observationIdField).desc(),
              // Otherwise take the results from the next latest observation for that area.
              OBSERVATIONS.COMPLETED_TIME.desc(),
          )
          .limit(1)
    }

/**
 * Returns a condition that is true if the given monitoring plot is used in the observation from
 * observationIdField.
 *
 * A monitoring plot is considered used in an observation if it is:
 * 1. In a requested subzone in the observation, or
 * 2. If the plot's subzone was not requested in the current observation, but was requested in that
 *    subzone's most recent observation.
 */
fun plotIsInObservationResult(
    monitoringPlotIdField: Field<MonitoringPlotId?>,
    observationIdField: Field<ObservationId?>,
    isPermanent: Boolean,
): Condition {
  // at the time of the observation:
  val subzoneObservations = OBSERVATIONS.`as`("subzone_observations")
  val subzonesInSite =
      DSL.selectDistinct(PLANTING_SUBZONE_HISTORIES.PLANTING_SUBZONE_ID)
          .from(subzoneObservations)
          .join(PLANTING_ZONE_HISTORIES)
          .on(
              PLANTING_ZONE_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                  subzoneObservations.PLANTING_SITE_HISTORY_ID
              )
          )
          .join(PLANTING_SUBZONE_HISTORIES)
          .on(PLANTING_SUBZONE_HISTORIES.PLANTING_ZONE_HISTORY_ID.eq(PLANTING_ZONE_HISTORIES.ID))
          .where(subzoneObservations.ID.eq(observationIdField))

  val subzonesInCurrentObservation =
      DSL.select(OBSERVATION_REQUESTED_SUBZONES.PLANTING_SUBZONE_ID)
          .from(OBSERVATION_REQUESTED_SUBZONES)
          .where(OBSERVATION_REQUESTED_SUBZONES.OBSERVATION_ID.eq(observationIdField))

  val maxTimeObservations = OBSERVATIONS.`as`("max")
  val observationCompletedTime =
      DSL.select(maxTimeObservations.COMPLETED_TIME)
          .from(maxTimeObservations)
          .where(maxTimeObservations.ID.eq(observationIdField))

  val observationIdsForRemainingSubzones =
      with(OBSERVATION_REQUESTED_SUBZONES) {
        DSL.select(
                DSL.field("planting_subzone_id", PlantingSubzoneId::class.java),
                DSL.field("observation_id", ObservationId::class.java),
            )
            .from(
                DSL.select(
                        PLANTING_SUBZONE_ID,
                        OBSERVATION_ID,
                        DSL.rowNumber()
                            .over()
                            .partitionBy(PLANTING_SUBZONE_ID)
                            .orderBy(OBSERVATIONS.COMPLETED_TIME.desc())
                            .`as`("rn"),
                    )
                    .from(this)
                    .join(OBSERVATIONS)
                    .on(OBSERVATIONS.ID.eq(OBSERVATION_ID))
                    .where(OBSERVATION_ID.ne(observationIdField))
                    .and(PLANTING_SUBZONE_ID.`in`(subzonesInSite))
                    .and(PLANTING_SUBZONE_ID.notIn(subzonesInCurrentObservation))
                    .and(OBSERVATIONS.COMPLETED_TIME.le(observationCompletedTime))
            )
            .where(DSL.field(DSL.name("rn")).eq(1))
      }

  return DSL.exists(
      DSL.select(OBSERVATION_PLOTS.MONITORING_PLOT_ID)
          .from(OBSERVATION_PLOTS)
          .join(MONITORING_PLOT_HISTORIES)
          .on(MONITORING_PLOT_HISTORIES.ID.eq(OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID))
          .join(PLANTING_SUBZONE_HISTORIES)
          .on(
              PLANTING_SUBZONE_HISTORIES.ID.eq(
                  MONITORING_PLOT_HISTORIES.PLANTING_SUBZONE_HISTORY_ID
              )
          )
          .where(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotIdField))
          .and(OBSERVATION_PLOTS.IS_PERMANENT.eq(isPermanent))
          .and(
              DSL.and(
                      OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationIdField),
                      PLANTING_SUBZONE_HISTORIES.PLANTING_SUBZONE_ID.`in`(
                          subzonesInCurrentObservation
                      ),
                  )
                  .or(
                      DSL.row(
                              PLANTING_SUBZONE_HISTORIES.PLANTING_SUBZONE_ID,
                              OBSERVATION_PLOTS.OBSERVATION_ID,
                          )
                          .`in`(observationIdsForRemainingSubzones)
                  )
          )
  )
}
