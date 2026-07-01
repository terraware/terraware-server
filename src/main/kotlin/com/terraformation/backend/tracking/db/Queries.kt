package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_DEPENDENT_SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_REQUESTED_SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record1
import org.jooq.Select
import org.jooq.impl.DSL

/**
 * Retrieves the most recent observationId for a substratum, or null if the substratum was not
 * observed in or before the requested observation
 *
 * The substratum in question is assumed to be OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SUBSTRATUM_ID.
 */
fun latestObservationForSubstratumField(
    observationIdField: Field<ObservationId?>,
    substratumIdField: Field<SubstratumId?>,
): Select<Record1<ObservationId?>> {
  val dependentSsh = SUBSTRATUM_HISTORIES.`as`("dependent_obs_ssh")
  return DSL.select(OBSERVATION_DEPENDENT_SUBSTRATA.DEPENDS_ON_OBSERVATION_ID)
      .from(OBSERVATION_DEPENDENT_SUBSTRATA)
      .join(dependentSsh)
      .on(dependentSsh.ID.eq(OBSERVATION_DEPENDENT_SUBSTRATA.SUBSTRATUM_HISTORY_ID))
      .where(OBSERVATION_DEPENDENT_SUBSTRATA.OBSERVATION_ID.eq(observationIdField))
      .and(dependentSsh.SUBSTRATUM_ID.eq(substratumIdField))
      .limit(1)
}

/**
 * Returns an observationId if the given monitoring plot is used in the observation results from
 * observationIdField.
 *
 * A monitoring plot is considered used in an observation if it is:
 * 1. In a requested substratum in the observation, or
 * 2. If the plot's substratum was not requested in the current observation, but was requested in
 *    that substratum's most recent observation.
 */
fun observationIdForPlot(
    monitoringPlotIdField: Field<MonitoringPlotId?>,
    observationIdField: Field<ObservationId?>,
    isPermanent: Boolean,
): Field<ObservationId?> {
  // at the time of the observation:
  val substratumObservations = OBSERVATIONS.`as`("substratum_observations")
  val sh = STRATUM_HISTORIES.`as`("sh")
  val ssh = SUBSTRATUM_HISTORIES.`as`("ssh")
  val substrataInSite =
      DSL.selectDistinct(ssh.SUBSTRATUM_ID)
          .from(substratumObservations)
          .join(sh)
          .on(sh.PLANTING_SITE_HISTORY_ID.eq(substratumObservations.PLANTING_SITE_HISTORY_ID))
          .join(ssh)
          .on(ssh.STRATUM_HISTORY_ID.eq(sh.ID))
          .where(substratumObservations.ID.eq(observationIdField))

  val substrataInCurrentObservation =
      DSL.select(OBSERVATION_REQUESTED_SUBSTRATA.SUBSTRATUM_ID)
          .from(OBSERVATION_REQUESTED_SUBSTRATA)
          .where(OBSERVATION_REQUESTED_SUBSTRATA.OBSERVATION_ID.eq(observationIdField))

  val maxTimeObservations = OBSERVATIONS.`as`("max")
  val observationCompletedTime =
      DSL.select(maxTimeObservations.COMPLETED_TIME)
          .from(maxTimeObservations)
          .where(maxTimeObservations.ID.eq(observationIdField))

  val remainingObservations = OBSERVATIONS.`as`("remaining")
  val observationIdsForRemainingSubstrata =
      with(OBSERVATION_REQUESTED_SUBSTRATA) {
        DSL.select(
                DSL.field("substratum_id", SubstratumId::class.java),
                DSL.field("observation_id", ObservationId::class.java),
            )
            .from(
                DSL.select(
                        SUBSTRATUM_ID,
                        OBSERVATION_ID,
                        DSL.rowNumber()
                            .over()
                            .partitionBy(SUBSTRATUM_ID)
                            .orderBy(remainingObservations.COMPLETED_TIME.desc())
                            .`as`("rn"),
                    )
                    .from(this)
                    .join(remainingObservations)
                    .on(remainingObservations.ID.eq(OBSERVATION_ID))
                    .where(OBSERVATION_ID.ne(observationIdField))
                    .and(SUBSTRATUM_ID.`in`(substrataInSite))
                    .and(SUBSTRATUM_ID.notIn(substrataInCurrentObservation))
                    .and(remainingObservations.COMPLETED_TIME.le(observationCompletedTime))
            )
            .where(DSL.field(DSL.name("rn")).eq(1))
      }

  val op = OBSERVATION_PLOTS.`as`("op")
  val mph = MONITORING_PLOT_HISTORIES.`as`("mph")
  val psh2 = SUBSTRATUM_HISTORIES.`as`("psh2")
  return DSL.field(
      DSL.select(op.OBSERVATION_ID)
          .from(op)
          .join(mph)
          .on(mph.ID.eq(op.MONITORING_PLOT_HISTORY_ID))
          .join(psh2)
          .on(psh2.ID.eq(mph.SUBSTRATUM_HISTORY_ID))
          .where(op.MONITORING_PLOT_ID.eq(monitoringPlotIdField))
          .and(op.IS_PERMANENT.eq(isPermanent))
          .and(
              DSL.and(
                      op.OBSERVATION_ID.eq(observationIdField),
                      psh2.SUBSTRATUM_ID.`in`(substrataInCurrentObservation),
                  )
                  .or(
                      DSL.row(
                              psh2.SUBSTRATUM_ID,
                              op.OBSERVATION_ID,
                          )
                          .`in`(observationIdsForRemainingSubstrata)
                  )
          )
          .orderBy(op.observations.COMPLETED_TIME.desc())
          .limit(1)
  )
}

fun substratumObservedAtOrBefore(
    substratumIdField: Field<SubstratumId?>,
    observationIdField: Field<ObservationId?>,
): Condition =
    DSL.exists(
        DSL.selectOne()
            .from(
                latestObservationForSubstratumField(observationIdField, substratumIdField)
                    .asTable("latest_obs")
            )
    )
