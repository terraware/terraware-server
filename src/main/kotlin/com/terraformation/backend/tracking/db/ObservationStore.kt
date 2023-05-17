package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotConditionsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationsDao
import com.terraformation.backend.db.tracking.tables.daos.RecordedPlantsDao
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotConditionsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.tracking.model.AssignedPlotDetails
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.ObservationModel
import com.terraformation.backend.tracking.model.ObservationPlotModel
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class ObservationStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val observationsDao: ObservationsDao,
    private val observationPlotConditionsDao: ObservationPlotConditionsDao,
    private val observationPlotsDao: ObservationPlotsDao,
    private val recordedPlantsDao: RecordedPlantsDao,
) {
  fun fetchObservationById(observationId: ObservationId): ExistingObservationModel {
    requirePermissions { readObservation(observationId) }

    return dslContext.selectFrom(OBSERVATIONS).where(OBSERVATIONS.ID.eq(observationId)).fetchOne {
      ObservationModel.of(it)
    }
        ?: throw ObservationNotFoundException(observationId)
  }

  fun fetchObservationsByOrganization(
      organizationId: OrganizationId
  ): List<ExistingObservationModel> {
    requirePermissions { readOrganization(organizationId) }

    return dslContext
        .selectFrom(OBSERVATIONS)
        .where(OBSERVATIONS.plantingSites.ORGANIZATION_ID.eq(organizationId))
        .orderBy(OBSERVATIONS.START_DATE, OBSERVATIONS.ID)
        .fetch { ObservationModel.of(it) }
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

  fun fetchObservationPlotDetails(observationId: ObservationId): List<AssignedPlotDetails> {
    requirePermissions { readObservation(observationId) }

    // Calculated field that turns a users row into a String? with the user's full name.
    val fullNameField =
        DSL.row(USERS.FIRST_NAME, USERS.LAST_NAME).convertFrom { record ->
          record?.let { IndividualUser.makeFullName(it.value1(), it.value2()) }
        }
    val claimedByNameField =
        DSL.field(
            DSL.select(fullNameField).from(USERS).where(USERS.ID.eq(OBSERVATION_PLOTS.CLAIMED_BY)))
    val completedByNameField =
        DSL.field(
            DSL.select(fullNameField)
                .from(USERS)
                .where(USERS.ID.eq(OBSERVATION_PLOTS.COMPLETED_BY)))

    val earlierObservationPlots = OBSERVATION_PLOTS.`as`("earlier_plots")
    val isFirstObservationField =
        DSL.notExists(
            DSL.selectOne()
                .from(earlierObservationPlots)
                .where(
                    earlierObservationPlots.MONITORING_PLOT_ID.eq(
                        OBSERVATION_PLOTS.MONITORING_PLOT_ID))
                .and(earlierObservationPlots.OBSERVATION_ID.lt(OBSERVATION_PLOTS.OBSERVATION_ID)))

    return dslContext
        .select(
            OBSERVATION_PLOTS.CLAIMED_BY,
            OBSERVATION_PLOTS.CLAIMED_TIME,
            OBSERVATION_PLOTS.COMPLETED_BY,
            OBSERVATION_PLOTS.COMPLETED_TIME,
            OBSERVATION_PLOTS.IS_PERMANENT,
            OBSERVATION_PLOTS.MONITORING_PLOT_ID,
            OBSERVATION_PLOTS.NOTES,
            OBSERVATION_PLOTS.OBSERVED_TIME,
            OBSERVATION_PLOTS.OBSERVATION_ID,
            OBSERVATION_PLOTS.monitoringPlots.BOUNDARY,
            OBSERVATION_PLOTS.monitoringPlots.FULL_NAME,
            OBSERVATION_PLOTS.monitoringPlots.plantingSubzones.FULL_NAME,
            OBSERVATION_PLOTS.monitoringPlots.plantingSubzones.ID,
            claimedByNameField,
            completedByNameField,
            isFirstObservationField,
        )
        .from(OBSERVATION_PLOTS)
        .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
        .orderBy(OBSERVATION_PLOTS.MONITORING_PLOT_ID)
        .fetch { record ->
          AssignedPlotDetails(
              model = ObservationPlotModel.of(record),
              boundary = record[OBSERVATION_PLOTS.monitoringPlots.BOUNDARY]!!,
              claimedByName = record[claimedByNameField],
              completedByName = record[completedByNameField],
              isFirstObservation = record[isFirstObservationField]!!,
              plantingSubzoneId = record[OBSERVATION_PLOTS.monitoringPlots.plantingSubzones.ID]!!,
              plantingSubzoneName =
                  record[OBSERVATION_PLOTS.monitoringPlots.plantingSubzones.FULL_NAME]!!,
              plotName = record[OBSERVATION_PLOTS.monitoringPlots.FULL_NAME]!!,
          )
        }
  }

  fun fetchStartableObservations(
      plantingSiteId: PlantingSiteId? = null
  ): List<ExistingObservationModel> {
    val maxStartDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(1)
    val timeZoneField =
        DSL.coalesce(
            OBSERVATIONS.plantingSites.TIME_ZONE,
            OBSERVATIONS.plantingSites.organizations.TIME_ZONE)

    return dslContext
        .select(OBSERVATIONS.asterisk(), timeZoneField)
        .from(OBSERVATIONS)
        .where(OBSERVATIONS.STATE_ID.eq(ObservationState.Upcoming))
        .and(OBSERVATIONS.START_DATE.le(maxStartDate))
        .apply { if (plantingSiteId != null) and(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId)) }
        .orderBy(OBSERVATIONS.ID)
        .fetch { record ->
          val model = ObservationModel.of(record)
          val timeZone = record[timeZoneField] ?: ZoneOffset.UTC
          val todayAtSite = LocalDate.ofInstant(clock.instant(), timeZone)
          if (model.startDate <= todayAtSite) {
            model
          } else {
            null
          }
        }
        .filter { it != null && currentUser().canManageObservation(it.id) }
  }

  fun countUnclaimedPlots(plantingSiteId: PlantingSiteId): Map<ObservationId, Int> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return dslContext
        .select(OBSERVATION_PLOTS.OBSERVATION_ID, DSL.count())
        .from(OBSERVATION_PLOTS)
        .where(OBSERVATION_PLOTS.CLAIMED_TIME.isNull)
        .and(OBSERVATION_PLOTS.observations.PLANTING_SITE_ID.eq(plantingSiteId))
        .groupBy(OBSERVATION_PLOTS.OBSERVATION_ID)
        .fetchMap(OBSERVATION_PLOTS.OBSERVATION_ID.asNonNullable()) { it.value2() }
  }

  fun countUnclaimedPlots(organizationId: OrganizationId): Map<ObservationId, Int> {
    requirePermissions { readOrganization(organizationId) }

    return dslContext
        .select(OBSERVATION_PLOTS.OBSERVATION_ID, DSL.count())
        .from(OBSERVATION_PLOTS)
        .where(OBSERVATION_PLOTS.CLAIMED_TIME.isNull)
        .and(OBSERVATION_PLOTS.observations.plantingSites.ORGANIZATION_ID.eq(organizationId))
        .groupBy(OBSERVATION_PLOTS.OBSERVATION_ID)
        .fetchMap(OBSERVATION_PLOTS.OBSERVATION_ID.asNonNullable()) { it.value2() }
  }

  /**
   * Locks an observation and calls a function. Starts a database transaction; the function is
   * called with the transaction open, such that the lock is held while the function runs.
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

  fun claimPlot(observationId: ObservationId, monitoringPlotId: MonitoringPlotId) {
    requirePermissions { updateObservation(observationId) }

    val rowsUpdated =
        dslContext
            .update(OBSERVATION_PLOTS)
            .set(OBSERVATION_PLOTS.CLAIMED_BY, currentUser().userId)
            .set(OBSERVATION_PLOTS.CLAIMED_TIME, clock.instant())
            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
            .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
            .and(
                OBSERVATION_PLOTS.CLAIMED_BY.isNull.or(
                    OBSERVATION_PLOTS.CLAIMED_BY.eq(currentUser().userId)))
            .execute()

    if (rowsUpdated == 0) {
      val plotAssignment =
          dslContext
              .selectOne()
              .from(OBSERVATION_PLOTS)
              .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
              .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
              .fetch()
      if (plotAssignment.isEmpty()) {
        throw PlotNotInObservationException(observationId, monitoringPlotId)
      } else {
        throw PlotAlreadyClaimedException(monitoringPlotId)
      }
    }
  }

  fun releasePlot(observationId: ObservationId, monitoringPlotId: MonitoringPlotId) {
    requirePermissions { updateObservation(observationId) }

    val rowsUpdated =
        dslContext
            .update(OBSERVATION_PLOTS)
            .setNull(OBSERVATION_PLOTS.CLAIMED_BY)
            .setNull(OBSERVATION_PLOTS.CLAIMED_TIME)
            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
            .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
            .and(OBSERVATION_PLOTS.CLAIMED_BY.eq(currentUser().userId))
            .execute()

    if (rowsUpdated == 0) {
      val plotClaim =
          dslContext
              .select(OBSERVATION_PLOTS.CLAIMED_BY)
              .from(OBSERVATION_PLOTS)
              .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
              .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
              .fetch(OBSERVATION_PLOTS.CLAIMED_BY)
      if (plotClaim.isEmpty()) {
        throw PlotNotInObservationException(observationId, monitoringPlotId)
      } else if (plotClaim.first() == null) {
        throw PlotNotClaimedException(monitoringPlotId)
      } else {
        throw PlotAlreadyClaimedException(monitoringPlotId)
      }
    }
  }

  fun completePlot(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      conditions: Set<ObservableCondition>,
      notes: String?,
      observedTime: Instant,
      plants: Collection<RecordedPlantsRow>,
  ) {
    requirePermissions { updateObservation(observationId) }

    dslContext.transaction { _ ->
      val observationPlotsRow =
          dslContext
              .selectFrom(OBSERVATION_PLOTS)
              .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
              .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
              .forUpdate()
              .fetchOneInto(ObservationPlotsRow::class.java)
              ?: throw PlotNotInObservationException(observationId, monitoringPlotId)

      if (observationPlotsRow.completedTime != null) {
        throw PlotAlreadyCompletedException(monitoringPlotId)
      }

      observationPlotConditionsDao.insert(
          conditions.map { ObservationPlotConditionsRow(observationId, monitoringPlotId, it) })

      recordedPlantsDao.insert(
          plants.map {
            it.copy(monitoringPlotId = monitoringPlotId, observationId = observationId)
          })

      observationPlotsDao.update(
          observationPlotsRow.copy(
              completedBy = currentUser().userId,
              completedTime = clock.instant(),
              notes = notes,
              observedTime = observedTime))

      val allPlotsCompleted =
          dslContext
              .selectOne()
              .from(OBSERVATION_PLOTS)
              .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
              .and(OBSERVATION_PLOTS.COMPLETED_TIME.isNull)
              .limit(1)
              .fetch()
              .isEmpty()

      if (allPlotsCompleted) {
        updateObservationState(observationId, ObservationState.Completed)
      }
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
