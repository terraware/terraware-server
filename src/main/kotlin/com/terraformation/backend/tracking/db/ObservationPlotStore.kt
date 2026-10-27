package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.EntityLocker
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.tracking.event.ObservationPlotCreatedEvent
import com.terraformation.backend.tracking.model.AssignedPlotDetails
import com.terraformation.backend.tracking.model.ObservationPlotCounts
import com.terraformation.backend.tracking.model.ObservationPlotModel
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

/** Manages which monitoring plots are included in observations, and plot claims. */
@Named
class ObservationPlotStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val entityLocker: EntityLocker,
    private val eventPublisher: ApplicationEventPublisher,
    private val parentStore: ParentStore,
) {
  fun fetchObservationPlotDetails(observationId: ObservationId): List<AssignedPlotDetails> {
    requirePermissions { readObservation(observationId) }

    return fetchObservationPlotDetails(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
  }

  fun fetchOneObservationPlotDetails(
      observationId: ObservationId,
      plotId: MonitoringPlotId,
  ): AssignedPlotDetails {
    requirePermissions { readObservation(observationId) }

    return fetchObservationPlotDetails(
            DSL.and(
                OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId),
                OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId),
            )
        )
        .firstOrNull() ?: throw ObservationPlotNotFoundException(observationId, plotId)
  }

  private fun fetchObservationPlotDetails(condition: Condition): List<AssignedPlotDetails> {
    // Calculated field that turns a users row into a String? with the user's full name.
    val fullNameField =
        DSL.row(USERS.FIRST_NAME, USERS.LAST_NAME).convertFrom { record ->
          record?.let { TerrawareUser.makeFullName(it.value1(), it.value2()) }
        }
    val claimedByNameField =
        DSL.field(
            DSL.select(fullNameField).from(USERS).where(USERS.ID.eq(OBSERVATION_PLOTS.CLAIMED_BY))
        )
    val completedByNameField =
        DSL.field(
            DSL.select(fullNameField).from(USERS).where(USERS.ID.eq(OBSERVATION_PLOTS.COMPLETED_BY))
        )

    val earlierObservationPlots = OBSERVATION_PLOTS.`as`("earlier_plots")
    val isFirstObservationField =
        DSL.notExists(
            DSL.selectOne()
                .from(earlierObservationPlots)
                .where(
                    earlierObservationPlots.MONITORING_PLOT_ID.eq(
                        OBSERVATION_PLOTS.MONITORING_PLOT_ID
                    )
                )
                .and(earlierObservationPlots.OBSERVATION_ID.lt(OBSERVATION_PLOTS.OBSERVATION_ID))
        )

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
            OBSERVATION_PLOTS.monitoringPlots.ELEVATION_METERS,
            OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.FULL_NAME,
            OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.SUBSTRATUM_ID,
            OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.stratumHistories.NAME,
            OBSERVATION_PLOTS.monitoringPlots.PLOT_NUMBER,
            OBSERVATION_PLOTS.monitoringPlots.SIZE_METERS,
            claimedByNameField,
            completedByNameField,
            isFirstObservationField,
        )
        .from(OBSERVATION_PLOTS)
        .where(condition)
        .orderBy(OBSERVATION_PLOTS.MONITORING_PLOT_ID)
        .fetch { record ->
          AssignedPlotDetails(
              model = ObservationPlotModel.of(record),
              boundary = record[OBSERVATION_PLOTS.monitoringPlots.BOUNDARY]!!,
              claimedByName = record[claimedByNameField],
              completedByName = record[completedByNameField],
              elevationMeters = record[OBSERVATION_PLOTS.monitoringPlots.ELEVATION_METERS],
              isFirstObservation = record[isFirstObservationField]!!,
              substratumId =
                  record[
                      OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.SUBSTRATUM_ID],
              substratumName =
                  record[OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.FULL_NAME],
              stratumName =
                  record[
                      OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.stratumHistories
                          .NAME],
              plotNumber = record[OBSERVATION_PLOTS.monitoringPlots.PLOT_NUMBER]!!,
              sizeMeters = record[OBSERVATION_PLOTS.monitoringPlots.SIZE_METERS]!!,
          )
        }
  }

  fun countPlots(
      plantingSiteId: PlantingSiteId,
      isAdHoc: Boolean = false,
  ): Map<ObservationId, ObservationPlotCounts> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    val incompleteField = DSL.count().filterWhere(OBSERVATION_PLOTS.COMPLETED_TIME.isNull)
    val totalField = DSL.count()
    val unclaimedField = DSL.count().filterWhere(OBSERVATION_PLOTS.CLAIMED_TIME.isNull)

    return dslContext
        .select(OBSERVATION_PLOTS.OBSERVATION_ID, incompleteField, totalField, unclaimedField)
        .from(OBSERVATION_PLOTS)
        .where(OBSERVATION_PLOTS.observations.PLANTING_SITE_ID.eq(plantingSiteId))
        .and(OBSERVATION_PLOTS.observations.IS_AD_HOC.eq(isAdHoc))
        .groupBy(OBSERVATION_PLOTS.OBSERVATION_ID)
        .fetchMap(OBSERVATION_PLOTS.OBSERVATION_ID.asNonNullable()) { record ->
          ObservationPlotCounts(
              totalIncomplete = record[incompleteField],
              totalPlots = record[totalField],
              totalUnclaimed = record[unclaimedField],
          )
        }
  }

  fun countPlots(observationId: ObservationId): ObservationPlotCounts {
    requirePermissions { readObservation(observationId) }

    if (dslContext.fetchExists(OBSERVATIONS, OBSERVATIONS.ID.eq(observationId))) {
      val incompleteField = DSL.count().filterWhere(OBSERVATION_PLOTS.COMPLETED_TIME.isNull)
      val totalField = DSL.count()
      val unclaimedField = DSL.count().filterWhere(OBSERVATION_PLOTS.CLAIMED_TIME.isNull)

      return dslContext
          .select(incompleteField, totalField, unclaimedField)
          .from(OBSERVATION_PLOTS)
          .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
          .fetchSingle { record ->
            ObservationPlotCounts(
                totalIncomplete = record[incompleteField],
                totalPlots = record[totalField],
                totalUnclaimed = record[unclaimedField],
            )
          }
    } else {
      throw ObservationNotFoundException(observationId)
    }
  }

  fun countPlots(
      organizationId: OrganizationId,
      isAdHoc: Boolean = false,
  ): Map<ObservationId, ObservationPlotCounts> {
    requirePermissions { readOrganization(organizationId) }

    val incompleteField = DSL.count().filterWhere(OBSERVATION_PLOTS.COMPLETED_TIME.isNull)
    val totalField = DSL.count()
    val unclaimedField = DSL.count().filterWhere(OBSERVATION_PLOTS.CLAIMED_TIME.isNull)

    return dslContext
        .select(OBSERVATION_PLOTS.OBSERVATION_ID, incompleteField, totalField, unclaimedField)
        .from(OBSERVATION_PLOTS)
        .where(OBSERVATION_PLOTS.observations.plantingSites.ORGANIZATION_ID.eq(organizationId))
        .and(OBSERVATION_PLOTS.observations.IS_AD_HOC.eq(isAdHoc))
        .groupBy(OBSERVATION_PLOTS.OBSERVATION_ID)
        .fetchMap(OBSERVATION_PLOTS.OBSERVATION_ID.asNonNullable()) { record ->
          ObservationPlotCounts(
              totalIncomplete = record[incompleteField],
              totalPlots = record[totalField],
              totalUnclaimed = record[unclaimedField],
          )
        }
  }

  fun hasPlots(observationId: ObservationId): Boolean {
    fetchObservationSiteInfo(observationId)

    return dslContext.fetchExists(
        DSL.selectOne()
            .from(OBSERVATION_PLOTS)
            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
    )
  }

  fun addAdHocPlotToObservation(
      observationId: ObservationId,
      plotId: MonitoringPlotId,
  ) {
    val observation = fetchObservationSiteInfo(observationId)
    requirePermissions { scheduleAdHocObservation(observation.plantingSiteId) }

    if (!observation.isAdHoc) {
      throw IllegalStateException("BUG: Must be an ad-hoc observation")
    }

    validateAdHocPlotInPlantingSite(observation.plantingSiteId, plotId)

    insertObservationPlots(observationId, setOf(plotId), false)
  }

  fun addPlotsToObservation(
      observationId: ObservationId,
      plotIds: Collection<MonitoringPlotId>,
      isPermanent: Boolean,
  ) {
    if (!currentUser().canManageObservation(observationId)) {
      requirePermissions { replaceObservationPlot(observationId) }
    }

    val observation = fetchObservationSiteInfo(observationId)

    if (observation.isAdHoc) {
      throw IllegalStateException("BUG: Cannot add monitoring plot to an ad-hoc observation")
    }

    if (plotIds.isEmpty()) {
      return
    }

    validateNonAdHocPlotsInPlantingSite(observation.plantingSiteId, plotIds)

    insertObservationPlots(observationId, plotIds, isPermanent)
  }

  fun removePlotsFromObservation(
      observationId: ObservationId,
      plotIds: Collection<MonitoringPlotId>,
  ) {
    if (!currentUser().canManageObservation(observationId)) {
      requirePermissions { replaceObservationPlot(observationId) }
    }

    if (plotIds.isEmpty()) {
      return
    }

    val observation = fetchObservationSiteInfo(observationId)

    if (observation.isAdHoc) {
      throw IllegalStateException("BUG: Cannot remove monitoring plot from an ad-hoc observation")
    }

    validateNonAdHocPlotsInPlantingSite(observation.plantingSiteId, plotIds)

    val observationPlots =
        dslContext
            .selectFrom(OBSERVATION_PLOTS)
            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
            .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.`in`(plotIds))
            .fetchInto(ObservationPlotsRow::class.java)

    observationPlots.forEach { observationPlot ->
      if (observationPlot.completedTime != null) {
        throw PlotAlreadyCompletedException(observationPlot.monitoringPlotId!!)
      }
    }

    dslContext
        .deleteFrom(OBSERVATION_PLOTS)
        .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
        .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.`in`(plotIds))
        .execute()
  }

  fun claimPlot(observationId: ObservationId, monitoringPlotId: MonitoringPlotId) {
    requirePermissions { updateObservation(observationId) }

    val rowsUpdated =
        dslContext
            .update(OBSERVATION_PLOTS)
            .set(OBSERVATION_PLOTS.STATUS_ID, ObservationPlotStatus.Claimed)
            .set(OBSERVATION_PLOTS.CLAIMED_BY, currentUser().userId)
            .set(OBSERVATION_PLOTS.CLAIMED_TIME, clock.instant())
            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
            .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
            .and(
                OBSERVATION_PLOTS.STATUS_ID.eq(ObservationPlotStatus.Unclaimed)
                    .or(
                        OBSERVATION_PLOTS.STATUS_ID.eq(ObservationPlotStatus.Claimed)
                            .and(OBSERVATION_PLOTS.CLAIMED_BY.eq(currentUser().userId))
                    )
            )
            .execute()

    if (rowsUpdated == 0) {
      val plotStatus =
          dslContext
              .select(OBSERVATION_PLOTS.STATUS_ID)
              .from(OBSERVATION_PLOTS)
              .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
              .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
              .fetch { it[OBSERVATION_PLOTS.STATUS_ID]!! }
      if (plotStatus.isEmpty()) {
        throw PlotNotInObservationException(observationId, monitoringPlotId)
      } else {
        when (plotStatus.first()) {
          ObservationPlotStatus.Claimed -> throw PlotAlreadyClaimedException(monitoringPlotId)
          else -> throw PlotAlreadyCompletedException(monitoringPlotId)
        }
      }
    }
  }

  fun releasePlot(observationId: ObservationId, monitoringPlotId: MonitoringPlotId) {
    requirePermissions { updateObservation(observationId) }

    val rowsUpdated =
        dslContext
            .update(OBSERVATION_PLOTS)
            .set(OBSERVATION_PLOTS.STATUS_ID, ObservationPlotStatus.Unclaimed)
            .setNull(OBSERVATION_PLOTS.CLAIMED_BY)
            .setNull(OBSERVATION_PLOTS.CLAIMED_TIME)
            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
            .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
            .and(OBSERVATION_PLOTS.STATUS_ID.eq(ObservationPlotStatus.Claimed))
            .and(OBSERVATION_PLOTS.CLAIMED_BY.eq(currentUser().userId))
            .execute()

    if (rowsUpdated == 0) {
      val plotStatus =
          dslContext
              .select(OBSERVATION_PLOTS.STATUS_ID)
              .from(OBSERVATION_PLOTS)
              .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
              .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
              .fetch { it[OBSERVATION_PLOTS.STATUS_ID]!! }
      if (plotStatus.isEmpty()) {
        throw PlotNotInObservationException(observationId, monitoringPlotId)
      } else {
        when (plotStatus.first()) {
          ObservationPlotStatus.Unclaimed -> throw PlotNotClaimedException(monitoringPlotId)
          ObservationPlotStatus.Claimed -> throw PlotAlreadyClaimedException(monitoringPlotId)
          else -> throw PlotAlreadyCompletedException(monitoringPlotId)
        }
      }
    }
  }

  fun fetchObservedPermanentPlotIds(plantingSiteId: PlantingSiteId): Set<MonitoringPlotId> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return dslContext
        .selectDistinct(OBSERVATION_PLOTS.MONITORING_PLOT_ID)
        .from(OBSERVATION_PLOTS)
        .join(MONITORING_PLOTS)
        .on(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
        .where(MONITORING_PLOTS.PLANTING_SITE_ID.eq(plantingSiteId))
        .and(OBSERVATION_PLOTS.IS_PERMANENT)
        .fetchSet(OBSERVATION_PLOTS.MONITORING_PLOT_ID.asNonNullable())
  }

  fun deleteIncompletePlots(
      observationId: ObservationId,
      retainFromObservationIds: Collection<ObservationId>,
      dryRun: Boolean = false,
  ): Pair<List<MonitoringPlotId>, List<MonitoringPlotId>> {
    requirePermissions { manageObservation(observationId) }

    val currentState =
        dslContext.fetchValue(OBSERVATIONS.STATE_ID, OBSERVATIONS.ID.eq(observationId))
            ?: throw ObservationNotFoundException(observationId)

    if (currentState != ObservationState.Abandoned) {
      throw IllegalStateException(
          "Observation state is $currentState; can only delete plots from abandoned observations"
      )
    }

    return entityLocker.withLockedObservation(observationId) {
      with(OBSERVATION_PLOTS) {
        val innerObservationPlots = OBSERVATION_PLOTS.`as`("inner")

        val retainedPlotIds =
            if (retainFromObservationIds.isNotEmpty()) {
              dslContext
                  .select(MONITORING_PLOT_ID)
                  .from(OBSERVATION_PLOTS)
                  .where(OBSERVATION_ID.eq(observationId))
                  .and(STATUS_ID.ne(ObservationPlotStatus.Completed))
                  .and(IS_PERMANENT.isTrue)
                  .andNotExists(
                      DSL.selectOne()
                          .from(innerObservationPlots)
                          .where(innerObservationPlots.MONITORING_PLOT_ID.eq(MONITORING_PLOT_ID))
                          .and(innerObservationPlots.STATUS_ID.eq(ObservationPlotStatus.Completed))
                          .and(innerObservationPlots.OBSERVATION_ID.`in`(retainFromObservationIds))
                  )
                  .fetch(MONITORING_PLOT_ID.asNonNullable())
            } else {
              emptyList()
            }

        val deletedPlotIds =
            dslContext
                .select(MONITORING_PLOT_ID)
                .from(OBSERVATION_PLOTS)
                .where(OBSERVATION_ID.eq(observationId))
                .and(STATUS_ID.ne(ObservationPlotStatus.Completed))
                .and(MONITORING_PLOT_ID.notIn(retainedPlotIds))
                .fetch(MONITORING_PLOT_ID.asNonNullable())

        if (!dryRun && deletedPlotIds.isNotEmpty()) {
          dslContext
              .deleteFrom(OBSERVATION_PLOTS)
              .where(OBSERVATION_ID.eq(observationId))
              .and(MONITORING_PLOT_ID.`in`(deletedPlotIds))
              .and(STATUS_ID.ne(ObservationPlotStatus.Completed))
              .execute()
        }

        retainedPlotIds to deletedPlotIds
      }
    }
  }

  private data class ObservationSiteInfo(val plantingSiteId: PlantingSiteId, val isAdHoc: Boolean)

  private fun fetchObservationSiteInfo(observationId: ObservationId): ObservationSiteInfo {
    requirePermissions { readObservation(observationId) }

    return dslContext
        .select(OBSERVATIONS.PLANTING_SITE_ID, OBSERVATIONS.IS_AD_HOC)
        .from(OBSERVATIONS)
        .where(OBSERVATIONS.ID.eq(observationId))
        .fetchOne {
          ObservationSiteInfo(
              it[OBSERVATIONS.PLANTING_SITE_ID]!!,
              it[OBSERVATIONS.IS_AD_HOC]!!,
          )
        } ?: throw ObservationNotFoundException(observationId)
  }

  private fun insertObservationPlots(
      observationId: ObservationId,
      plotIds: Collection<MonitoringPlotId>,
      isPermanent: Boolean,
  ) {
    val createdBy = currentUser().userId
    val createdTime = clock.instant()

    val organizationId =
        parentStore.getOrganizationId(observationId)
            ?: throw ObservationNotFoundException(observationId)
    val plantingSiteId =
        parentStore.getPlantingSiteId(observationId)
            ?: throw ObservationNotFoundException(observationId)
    val historyIds =
        with(MONITORING_PLOT_HISTORIES) {
          dslContext
              .select(MONITORING_PLOT_ID, DSL.max(ID))
              .from(MONITORING_PLOT_HISTORIES)
              .where(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.`in`(plotIds))
              .groupBy(MONITORING_PLOT_ID)
              .fetchMap(MONITORING_PLOT_ID.asNonNullable(), DSL.max(ID).asNonNullable())
        }
    val plotNumbers =
        with(MONITORING_PLOTS) {
          dslContext
              .select(ID, PLOT_NUMBER)
              .from(MONITORING_PLOTS)
              .where(ID.`in`(plotIds))
              .fetchMap(ID.asNonNullable(), PLOT_NUMBER.asNonNullable())
        }

    plotIds.forEach { plotId ->
      with(OBSERVATION_PLOTS) {
        dslContext
            .insertInto(OBSERVATION_PLOTS)
            .set(CREATED_BY, createdBy)
            .set(CREATED_TIME, createdTime)
            .set(IS_PERMANENT, isPermanent)
            .set(MODIFIED_BY, createdBy)
            .set(MODIFIED_TIME, createdTime)
            .set(MONITORING_PLOT_HISTORY_ID, historyIds[plotId])
            .set(MONITORING_PLOT_ID, plotId)
            .set(OBSERVATION_ID, observationId)
            .set(STATUS_ID, ObservationPlotStatus.Unclaimed)
            .execute()
      }

      eventPublisher.publishEvent(
          ObservationPlotCreatedEvent(
              isPermanent = isPermanent,
              monitoringPlotHistoryId = historyIds.getValue(plotId),
              monitoringPlotId = plotId,
              observationId = observationId,
              organizationId = organizationId,
              plantingSiteId = plantingSiteId,
              plotNumber = plotNumbers.getValue(plotId),
          )
      )
    }
  }

  private fun validateAdHocPlotInPlantingSite(
      plantingSiteId: PlantingSiteId,
      plotId: MonitoringPlotId,
  ) {
    val isAdHoc =
        dslContext
            .select(MONITORING_PLOTS.IS_AD_HOC)
            .from(MONITORING_PLOTS)
            .where(MONITORING_PLOTS.ID.eq(plotId))
            .and(MONITORING_PLOTS.PLANTING_SITE_ID.eq(plantingSiteId))
            .fetchOneInto(Boolean::class.java)

    if (isAdHoc != true) {
      throw IllegalStateException(
          "BUG! Only an ad-hoc plot in the planting site can be added to an ad-hoc observation."
      )
    }
  }

  private fun validateNonAdHocPlotsInPlantingSite(
      plantingSiteId: PlantingSiteId,
      plotIds: Collection<MonitoringPlotId>,
  ) {
    val plantingSiteField =
        DSL.ifnull(
            MONITORING_PLOTS.substrata.PLANTING_SITE_ID,
            MONITORING_PLOTS.PLANTING_SITE_ID,
        )

    val nonMatchingPlot =
        dslContext
            .select(MONITORING_PLOTS.ID, plantingSiteField, MONITORING_PLOTS.IS_AD_HOC)
            .from(MONITORING_PLOTS)
            .where(MONITORING_PLOTS.ID.`in`(plotIds))
            .and(
                DSL.or(
                    plantingSiteField.ne(plantingSiteId),
                    MONITORING_PLOTS.IS_AD_HOC.isTrue(),
                )
            )
            .limit(1)
            .fetchOne()

    if (nonMatchingPlot != null) {
      if (nonMatchingPlot[plantingSiteField] != plantingSiteId) {
        throw IllegalStateException(
            "BUG! Plot ${nonMatchingPlot[MONITORING_PLOTS.ID]} is in site ${nonMatchingPlot[plantingSiteField]}, not $plantingSiteId"
        )
      } else {
        throw IllegalStateException(
            "BUG! Plot ${nonMatchingPlot[MONITORING_PLOTS.ID]} is an ad-hoc plot"
        )
      }
    }
  }
}
