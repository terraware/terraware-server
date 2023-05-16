package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationsDao
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.ObservationModel
import java.time.InstantSource
import javax.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class ObservationStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val observationsDao: ObservationsDao,
    private val observationPlotsDao: ObservationPlotsDao,
) {
  fun fetchObservationById(observationId: ObservationId): ExistingObservationModel {
    requirePermissions { readObservation(observationId) }

    return dslContext.selectFrom(OBSERVATIONS).where(OBSERVATIONS.ID.eq(observationId)).fetchOne {
      ObservationModel.of(it)
    }
        ?: throw ObservationNotFoundException(observationId)
  }

  fun fetchObservationsByPlantingSite(
      plantingSiteId: PlantingSiteId
  ): List<ExistingObservationModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return dslContext
        .selectFrom(OBSERVATIONS)
        .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
        .orderBy(OBSERVATIONS.START_DATE, OBSERVATIONS.ID)
        .fetch { ObservationModel.of(it) }
  }

  /**
   * Locks an observation and calls a function. The function is called in a database transaction.
   */
  fun withLockedObservation(
      observationId: ObservationId,
      func: (ExistingObservationModel) -> Unit
  ) {
    requirePermissions { updateObservation(observationId) }

    dslContext.transaction { _ ->
      val model =
          dslContext
              .selectFrom(OBSERVATIONS)
              .where(OBSERVATIONS.ID.eq(observationId))
              .forUpdate()
              .fetchOne { ObservationModel.of(it) }
              ?: throw ObservationNotFoundException(observationId)

      func(model)
    }
  }

  fun hasPlots(observationId: ObservationId): Boolean {
    fetchObservationById(observationId)

    return dslContext.fetchExists(
        DSL.selectOne()
            .from(OBSERVATION_PLOTS)
            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId)))
  }

  fun createObservation(newModel: NewObservationModel): ObservationId {
    requirePermissions { createObservation(newModel.plantingSiteId) }

    val row =
        ObservationsRow(
            createdTime = clock.instant(),
            endDate = newModel.endDate,
            plantingSiteId = newModel.plantingSiteId,
            startDate = newModel.startDate,
            stateId = ObservationState.Upcoming,
        )

    observationsDao.insert(row)

    return row.id!!
  }

  fun updateObservationState(observationId: ObservationId, newState: ObservationState) {
    requirePermissions {
      if (newState == ObservationState.Completed) {
        updateObservation(observationId)
      } else {
        manageObservation(observationId)
      }
    }

    withLockedObservation(observationId) { observation ->
      observation.validateStateTransition(newState)

      dslContext
          .update(OBSERVATIONS)
          .apply {
            if (newState == ObservationState.Completed)
                set(OBSERVATIONS.COMPLETED_TIME, clock.instant())
          }
          .set(OBSERVATIONS.STATE_ID, newState)
          .where(OBSERVATIONS.ID.eq(observationId))
          .execute()
    }
  }

  fun addPlotsToObservation(
      observationId: ObservationId,
      plotIds: Collection<MonitoringPlotId>,
      isPermanent: Boolean
  ) {
    requirePermissions { manageObservation(observationId) }

    if (plotIds.isEmpty()) {
      return
    }

    val observation = fetchObservationById(observationId)

    validatePlotsInPlantingSite(observation.plantingSiteId, plotIds)

    val createdBy = currentUser().userId
    val createdTime = clock.instant()

    plotIds.forEach { plotId ->
      observationPlotsDao.insert(
          ObservationPlotsRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              createdBy = createdBy,
              createdTime = createdTime,
              isPermanent = isPermanent,
              modifiedBy = createdBy,
              modifiedTime = createdTime,
          ))
    }
  }

  private fun validatePlotsInPlantingSite(
      plantingSiteId: PlantingSiteId,
      plotIds: Collection<MonitoringPlotId>
  ) {
    val nonMatchingPlot =
        dslContext
            .select(MONITORING_PLOTS.ID, MONITORING_PLOTS.plantingSubzones.PLANTING_SITE_ID)
            .from(MONITORING_PLOTS)
            .where(MONITORING_PLOTS.ID.`in`(plotIds))
            .and(MONITORING_PLOTS.plantingSubzones.PLANTING_SITE_ID.ne(plantingSiteId))
            .limit(1)
            .fetchOne()

    if (nonMatchingPlot != null) {
      throw IllegalStateException(
          "BUG! Plot ${nonMatchingPlot.value1()} is in site ${nonMatchingPlot.value2()}, not $plantingSiteId")
    }
  }
}
