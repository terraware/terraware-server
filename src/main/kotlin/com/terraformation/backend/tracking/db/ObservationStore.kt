package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesIdConverter
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationIdConverter
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.RecordedSpeciesCertaintyConverter
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotConditionsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationRequestedSubzonesDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationsDao
import com.terraformation.backend.db.tracking.tables.daos.RecordedPlantsDao
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotConditionsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationRequestedSubzonesRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.records.ObservedPlotSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_REQUESTED_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_COORDINATES
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_ZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_PLANTS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.log.withMDC
import com.terraformation.backend.tracking.model.AssignedPlotDetails
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.NewObservedPlotCoordinatesModel
import com.terraformation.backend.tracking.model.ObservationModel
import com.terraformation.backend.tracking.model.ObservationPlotCounts
import com.terraformation.backend.tracking.model.ObservationPlotModel
import jakarta.inject.Named
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

@Named
class ObservationStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val observationsDao: ObservationsDao,
    private val observationPlotConditionsDao: ObservationPlotConditionsDao,
    private val observationPlotsDao: ObservationPlotsDao,
    private val observationRequestedSubzonesDao: ObservationRequestedSubzonesDao,
    private val parentStore: ParentStore,
    private val recordedPlantsDao: RecordedPlantsDao,
) {
  private val requestedSubzoneIdsField: Field<Set<PlantingSubzoneId>> =
      with(OBSERVATION_REQUESTED_SUBZONES) {
        DSL.multiset(
                DSL.select(PLANTING_SUBZONE_ID)
                    .from(OBSERVATION_REQUESTED_SUBZONES)
                    .where(OBSERVATION_ID.eq(OBSERVATIONS.ID)))
            .convertFrom { result -> result.map { it[PLANTING_SUBZONE_ID]!! }.toSet() }
      }

  private val log = perClassLogger()

  fun fetchObservationById(observationId: ObservationId): ExistingObservationModel {
    requirePermissions { readObservation(observationId) }

    return dslContext
        .select(OBSERVATIONS.asterisk(), requestedSubzoneIdsField)
        .from(OBSERVATIONS)
        .where(OBSERVATIONS.ID.eq(observationId))
        .fetchOne { ObservationModel.of(it, requestedSubzoneIdsField) }
        ?: throw ObservationNotFoundException(observationId)
  }

  fun fetchObservationsByOrganization(
      organizationId: OrganizationId,
      isAdHoc: Boolean = false,
  ): List<ExistingObservationModel> {
    requirePermissions { readOrganization(organizationId) }

    return dslContext
        .select(OBSERVATIONS.asterisk(), requestedSubzoneIdsField)
        .from(OBSERVATIONS)
        .where(OBSERVATIONS.plantingSites.ORGANIZATION_ID.eq(organizationId))
        .and(OBSERVATIONS.IS_AD_HOC.eq(isAdHoc))
        .orderBy(OBSERVATIONS.START_DATE, OBSERVATIONS.ID)
        .fetch { ObservationModel.of(it, requestedSubzoneIdsField) }
  }

  fun fetchInProgressObservation(plantingSiteId: PlantingSiteId): ExistingObservationModel? {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return dslContext
        .select(OBSERVATIONS.asterisk(), requestedSubzoneIdsField)
        .from(OBSERVATIONS)
        .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
        .and(OBSERVATIONS.STATE_ID.eq(ObservationState.InProgress))
        .fetchOne { ObservationModel.of(it, requestedSubzoneIdsField) }
  }

  fun fetchObservationsByPlantingSite(
      plantingSiteId: PlantingSiteId,
      isAdHoc: Boolean = false,
  ): List<ExistingObservationModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return dslContext
        .select(OBSERVATIONS.asterisk(), requestedSubzoneIdsField)
        .from(OBSERVATIONS)
        .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
        .and(OBSERVATIONS.IS_AD_HOC.eq(isAdHoc))
        .orderBy(OBSERVATIONS.START_DATE, OBSERVATIONS.ID)
        .fetch { ObservationModel.of(it, requestedSubzoneIdsField) }
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
            OBSERVATION_PLOTS.monitoringPlots.SIZE_METERS,
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
              sizeMeters = record[OBSERVATION_PLOTS.monitoringPlots.SIZE_METERS]!!,
          )
        }
  }

  /**
   * Evaluates to true if an observation's planting site has subzones with any plantings.
   *
   * When we test whether a specific subzone is planted, we need to account for reassignments by
   * totaling the number of plants in the plantings in the subzone, so we don't count a subzone as
   * planted if all its deliveries were reassigned. But here, it is sufficient to just check for the
   * existence of any planting; reassignments can only move plants between subzones within a single
   * planting site, which means there's no way for a reassignment to lower a site's plant count to
   * zero.
   */
  private val plantingSiteHasPlantings: Condition =
      DSL.exists(
          DSL.selectOne()
              .from(PLANTINGS)
              .where(PLANTINGS.PLANTING_SITE_ID.eq(OBSERVATIONS.PLANTING_SITE_ID))
              .and(PLANTINGS.PLANTING_SUBZONE_ID.isNotNull))

  /**
   * Returns a list of observations that are starting in 1 month or less and for which we have yet
   * to send out notifications that they're coming up.
   */
  fun fetchNonNotifiedUpcomingObservations(): List<ExistingObservationModel> {
    val maxStartDate =
        LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusMonths(1).plusDays(1)

    return fetchWithDateFilter(
        listOf(
            OBSERVATIONS.STATE_ID.eq(ObservationState.Upcoming),
            OBSERVATIONS.START_DATE.le(maxStartDate),
            OBSERVATIONS.UPCOMING_NOTIFICATION_SENT_TIME.isNull,
            plantingSiteHasPlantings,
        )) { todayAtSite, record ->
          record[OBSERVATIONS.START_DATE]!! <= todayAtSite.plusMonths(1)
        }
  }

  fun fetchStartableObservations(
      plantingSiteId: PlantingSiteId? = null
  ): List<ExistingObservationModel> {
    val maxStartDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(1)

    return fetchWithDateFilter(
        listOfNotNull(
            OBSERVATIONS.STATE_ID.eq(ObservationState.Upcoming),
            OBSERVATIONS.START_DATE.le(maxStartDate),
            plantingSiteHasPlantings,
            plantingSiteId?.let { OBSERVATIONS.PLANTING_SITE_ID.eq(it) },
        )) { todayAtSite, record ->
          record[OBSERVATIONS.START_DATE]!! <= todayAtSite
        }
  }

  fun fetchObservationsPastEndDate(
      plantingSiteId: PlantingSiteId? = null
  ): List<ExistingObservationModel> {
    val maxEndDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    return fetchWithDateFilter(
        listOfNotNull(
            OBSERVATIONS.STATE_ID.eq(ObservationState.InProgress),
            OBSERVATIONS.END_DATE.le(maxEndDate),
            plantingSiteId?.let { OBSERVATIONS.PLANTING_SITE_ID.eq(it) },
        )) { todayAtSite, record ->
          record[OBSERVATIONS.END_DATE]!! < todayAtSite
        }
  }

  /**
   * Returns a list of observations that match a set of conditions and match a predicate that uses
   * the current date in the site's local time zone.
   */
  private fun fetchWithDateFilter(
      conditions: List<Condition>,
      predicate: (LocalDate, Record) -> Boolean
  ): List<ExistingObservationModel> {
    val timeZoneField =
        DSL.coalesce(
            OBSERVATIONS.plantingSites.TIME_ZONE,
            OBSERVATIONS.plantingSites.organizations.TIME_ZONE)

    return dslContext
        .select(OBSERVATIONS.asterisk(), requestedSubzoneIdsField, timeZoneField)
        .from(OBSERVATIONS)
        .where(conditions)
        .orderBy(OBSERVATIONS.ID)
        .fetch { record ->
          val timeZone = record[timeZoneField] ?: ZoneOffset.UTC
          val todayAtSite = LocalDate.ofInstant(clock.instant(), timeZone)

          if (predicate(todayAtSite, record)) {
            ObservationModel.of(record, requestedSubzoneIdsField)
          } else {
            null
          }
        }
        .filter { it != null && currentUser().canManageObservation(it.id) }
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

  /**
   * Locks an observation and calls a function. Starts a database transaction; the function is
   * called with the transaction open, such that the lock is held while the function runs.
   */
  fun <T> withLockedObservation(
      observationId: ObservationId,
      func: (ExistingObservationModel) -> T
  ): T {
    requirePermissions { updateObservation(observationId) }

    return dslContext.transactionResult { _ ->
      val model =
          dslContext
              .select(OBSERVATIONS.asterisk(), requestedSubzoneIdsField)
              .from(OBSERVATIONS)
              .where(OBSERVATIONS.ID.eq(observationId))
              .forUpdate()
              .of(OBSERVATIONS)
              .fetchOne { ObservationModel.of(it, requestedSubzoneIdsField) }
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
    requirePermissions {
      if (newModel.isAdHoc) {
        scheduleAdHocObservation(newModel.plantingSiteId)
      } else {
        createObservation(newModel.plantingSiteId)
      }
    }

    // Validate that all the requested subzones are part of the requested planting site.
    if (newModel.requestedSubzoneIds.isNotEmpty()) {
      val subzonesInRequestedSite =
          dslContext
              .select(PLANTING_SUBZONES.ID)
              .from(PLANTING_SUBZONES)
              .where(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(newModel.plantingSiteId))
              .and(PLANTING_SUBZONES.ID.`in`(newModel.requestedSubzoneIds))
              .fetchSet(PLANTING_SUBZONES.ID.asNonNullable())

      if (subzonesInRequestedSite != newModel.requestedSubzoneIds) {
        val missingSubzoneIds = newModel.requestedSubzoneIds - subzonesInRequestedSite
        throw PlantingSubzoneNotFoundException(missingSubzoneIds.first())
      }

      if (newModel.isAdHoc) {
        throw IllegalArgumentException("Requested subzones must be empty for ad-hoc observations")
      }
    }

    return dslContext.transactionResult { _ ->
      val row =
          ObservationsRow(
              createdTime = clock.instant(),
              endDate = newModel.endDate,
              isAdHoc = newModel.isAdHoc,
              observationTypeId = newModel.observationType,
              plantingSiteId = newModel.plantingSiteId,
              startDate = newModel.startDate,
              stateId = ObservationState.Upcoming,
          )

      observationsDao.insert(row)

      newModel.requestedSubzoneIds.forEach { subzoneId ->
        observationRequestedSubzonesDao.insert(ObservationRequestedSubzonesRow(row.id, subzoneId))
      }

      row.id!!
    }
  }

  fun rescheduleObservation(
      observationId: ObservationId,
      startDate: LocalDate,
      endDate: LocalDate
  ) {
    requirePermissions { updateObservation(observationId) }

    withLockedObservation(observationId) { _ ->
      dslContext
          .update(OBSERVATIONS)
          .setNull(OBSERVATIONS.PLANTING_SITE_HISTORY_ID)
          .set(OBSERVATIONS.STATE_ID, ObservationState.Upcoming)
          .set(OBSERVATIONS.START_DATE, startDate)
          .set(OBSERVATIONS.END_DATE, endDate)
          .where(OBSERVATIONS.ID.eq(observationId))
          .execute()

      // If the observation was already in progress, it will have plots, but we want to assign a
      // fresh set on the new start date so that the observation is based on up-to-date information
      // about which subzones are planted. Delete the existing plots. This is a no-op if the
      // observation hadn't started yet.
      //
      // Rescheduling should only be allowed if there are no completed plots, but for added safety,
      // guard against deleting completed ones.
      dslContext
          .deleteFrom(OBSERVATION_PLOTS)
          .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
          .and(OBSERVATION_PLOTS.COMPLETED_TIME.isNull)
          .execute()
    }
  }

  fun updateObservationState(observationId: ObservationId, newState: ObservationState) {
    if (newState == ObservationState.InProgress) {
      log.error("BUG! Should call recordObservationStart to set state to $newState")
      throw IllegalArgumentException("Invalid state transition")
    }

    requirePermissions {
      if (newState == ObservationState.Completed || newState == ObservationState.Abandoned) {
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
            if (newState == ObservationState.Completed || newState == ObservationState.Abandoned)
                set(OBSERVATIONS.COMPLETED_TIME, clock.instant())
          }
          .set(OBSERVATIONS.STATE_ID, newState)
          .where(OBSERVATIONS.ID.eq(observationId))
          .execute()
    }
  }

  fun recordObservationStart(observationId: ObservationId): ExistingObservationModel {
    requirePermissions { manageObservation(observationId) }

    return withLockedObservation(observationId) { observation ->
      val plantingSiteHistoryId =
          dslContext
              .select(DSL.max(PLANTING_SITE_HISTORIES.ID))
              .from(PLANTING_SITE_HISTORIES)
              .where(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID.eq(observation.plantingSiteId))
              .fetchOne()
              ?.value1() ?: throw IllegalStateException("Planting site has no history")

      dslContext
          .update(OBSERVATIONS)
          .set(OBSERVATIONS.PLANTING_SITE_HISTORY_ID, plantingSiteHistoryId)
          .set(OBSERVATIONS.STATE_ID, ObservationState.InProgress)
          .where(OBSERVATIONS.ID.eq(observationId))
          .execute()

      observation.copy(
          plantingSiteHistoryId = plantingSiteHistoryId, state = ObservationState.InProgress)
    }
  }

  fun markUpcomingNotificationComplete(observationId: ObservationId) {
    requirePermissions { manageObservation(observationId) }

    dslContext
        .update(OBSERVATIONS)
        .set(OBSERVATIONS.UPCOMING_NOTIFICATION_SENT_TIME, clock.instant())
        .where(OBSERVATIONS.ID.eq(observationId))
        .execute()
  }

  fun addAdHocPlotToObservation(
      observationId: ObservationId,
      plotId: MonitoringPlotId,
  ) {
    val observation = fetchObservationById(observationId)
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
      isPermanent: Boolean
  ) {
    if (!currentUser().canManageObservation(observationId)) {
      requirePermissions { replaceObservationPlot(observationId) }
    }

    val observation = fetchObservationById(observationId)

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
      plotIds: Collection<MonitoringPlotId>
  ) {
    if (!currentUser().canManageObservation(observationId)) {
      requirePermissions { replaceObservationPlot(observationId) }
    }

    if (plotIds.isEmpty()) {
      return
    }

    val observation = fetchObservationById(observationId)

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
                            .and(OBSERVATION_PLOTS.CLAIMED_BY.eq(currentUser().userId))))
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
      val (plantingZoneId, plantingSiteId, isAdHoc) =
          dslContext
              .select(
                  MONITORING_PLOTS.plantingSubzones.PLANTING_ZONE_ID,
                  MONITORING_PLOTS.PLANTING_SITE_ID.asNonNullable(),
                  MONITORING_PLOTS.IS_AD_HOC.asNonNullable())
              .from(MONITORING_PLOTS)
              .where(MONITORING_PLOTS.ID.eq(monitoringPlotId))
              .fetchOne()!!

      // We will be calculating cumulative totals across all observations of a planting site and
      // across monitoring plots and planting zones; guard against multiple submissions arriving at
      // once and causing data races.
      dslContext
          .selectOne()
          .from(PLANTING_SITES)
          .where(PLANTING_SITES.ID.eq(plantingSiteId))
          .forUpdate()

      val observationPlotsRow =
          dslContext
              .selectFrom(OBSERVATION_PLOTS)
              .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
              .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
              .fetchOneInto(ObservationPlotsRow::class.java)
              ?: throw PlotNotInObservationException(observationId, monitoringPlotId)

      if (observationPlotsRow.statusId == ObservationPlotStatus.Completed ||
          observationPlotsRow.statusId == ObservationPlotStatus.NotObserved) {
        throw PlotAlreadyCompletedException(monitoringPlotId)
      }

      observationPlotConditionsDao.insert(
          conditions.map { ObservationPlotConditionsRow(observationId, monitoringPlotId, it) })

      val plantsRows =
          plants.map { it.copy(monitoringPlotId = monitoringPlotId, observationId = observationId) }

      recordedPlantsDao.insert(plantsRows)

      val plantCountsBySpecies: Map<RecordedSpeciesKey, Map<RecordedPlantStatus, Int>> =
          plantsRows
              .groupBy { RecordedSpeciesKey(it.certaintyId!!, it.speciesId, it.speciesName) }
              .mapValues { (_, rowsForSpecies) ->
                rowsForSpecies
                    .groupBy { it.statusId!! }
                    .mapValues { (_, rowsForStatus) -> rowsForStatus.size }
              }

      updateSpeciesTotals(
          observationId,
          plantingSiteId,
          plantingZoneId,
          monitoringPlotId,
          isAdHoc,
          observationPlotsRow.isPermanent!!,
          plantCountsBySpecies)

      observationPlotsDao.update(
          observationPlotsRow.copy(
              completedBy = currentUser().userId,
              completedTime = clock.instant(),
              notes = notes,
              observedTime = observedTime,
              statusId = ObservationPlotStatus.Completed,
          ))

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
        completeObservation(observationId, plantingSiteId)
      }
    }
  }

  fun removePlotFromTotals(monitoringPlotId: MonitoringPlotId) {
    val (plantingSiteId, plantingZoneId, isAdHoc) =
        dslContext
            .select(
                PLANTING_ZONES.PLANTING_SITE_ID.asNonNullable(),
                PLANTING_ZONES.ID.asNonNullable(),
                MONITORING_PLOTS.IS_AD_HOC.asNonNullable(),
            )
            .from(MONITORING_PLOTS)
            .leftJoin(PLANTING_SUBZONES)
            .on(MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
            .leftJoin(PLANTING_ZONES)
            .on(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID))
            .where(MONITORING_PLOTS.ID.eq(monitoringPlotId))
            .fetchOne() ?: throw PlotNotFoundException(monitoringPlotId)

    if (isAdHoc) {
      throw IllegalStateException("Cannot subtract plant counts for ad-hoc plot $monitoringPlotId")
    }

    data class NegativeCount(
        val observationId: ObservationId,
        val isPermanent: Boolean,
        val plantCountsBySpecies: Map<RecordedSpeciesKey, Map<RecordedPlantStatus, Int>>,
    )

    val negativeCountField = DSL.count().neg()
    val negativeCounts: List<NegativeCount> =
        dslContext
            .select(
                OBSERVATION_PLOTS.OBSERVATION_ID,
                OBSERVATION_PLOTS.IS_PERMANENT,
                RECORDED_PLANTS.CERTAINTY_ID,
                RECORDED_PLANTS.SPECIES_ID,
                RECORDED_PLANTS.SPECIES_NAME,
                RECORDED_PLANTS.STATUS_ID,
                negativeCountField,
            )
            .from(OBSERVATION_PLOTS)
            .join(MONITORING_PLOTS)
            .on(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
            .join(PLANTING_SUBZONES)
            .on(MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
            .join(PLANTING_ZONES)
            .on(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID))
            .join(RECORDED_PLANTS)
            .on(OBSERVATION_PLOTS.OBSERVATION_ID.eq(RECORDED_PLANTS.OBSERVATION_ID))
            .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(RECORDED_PLANTS.MONITORING_PLOT_ID))
            .where(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
            .and(OBSERVATION_PLOTS.COMPLETED_TIME.isNotNull)
            .groupBy(
                OBSERVATION_PLOTS.OBSERVATION_ID,
                OBSERVATION_PLOTS.IS_PERMANENT,
                RECORDED_PLANTS.CERTAINTY_ID,
                RECORDED_PLANTS.SPECIES_ID,
                RECORDED_PLANTS.SPECIES_NAME,
                RECORDED_PLANTS.STATUS_ID,
            )
            .fetch()
            .groupBy { it[OBSERVATION_PLOTS.OBSERVATION_ID]!! }
            .entries
            .map { (observationId, records) ->
              val plantCountsBySpecies =
                  records
                      .groupBy {
                        RecordedSpeciesKey(
                            it[RECORDED_PLANTS.CERTAINTY_ID]!!,
                            it[RECORDED_PLANTS.SPECIES_ID],
                            it[RECORDED_PLANTS.SPECIES_NAME])
                      }
                      .mapValues { (_, statusTotals) ->
                        statusTotals.associate {
                          it[RECORDED_PLANTS.STATUS_ID]!! to it[negativeCountField]!!
                        }
                      }

              NegativeCount(
                  observationId,
                  records.first()[OBSERVATION_PLOTS.IS_PERMANENT]!!,
                  plantCountsBySpecies)
            }

    negativeCounts.forEach { negativeCount ->
      log.debug(
          "Subtracting plant counts for plot $monitoringPlotId from site $plantingSiteId and " +
              "zone $plantingZoneId in observation ${negativeCount.observationId}: " +
              "${negativeCount.plantCountsBySpecies}")

      updateSpeciesTotals(
          negativeCount.observationId,
          plantingSiteId,
          plantingZoneId,
          null,
          isAdHoc,
          negativeCount.isPermanent,
          negativeCount.plantCountsBySpecies)
    }
  }

  /**
   * Merges observation data for a species with a user-entered name into the data for one of the
   * organization's species. This causes the observation to appear as if the users in the field had
   * recorded seeing the target species instead of selecting "Other" and entering a species name
   * manually.
   */
  fun mergeOtherSpecies(
      observationId: ObservationId,
      otherSpeciesName: String,
      speciesId: SpeciesId
  ) {
    requirePermissions {
      updateObservation(observationId)
      updateSpecies(speciesId)
    }

    if (parentStore.getOrganizationId(observationId) != parentStore.getOrganizationId(speciesId)) {
      throw SpeciesInWrongOrganizationException(speciesId)
    }

    withLockedObservation(observationId) { observation ->
      val observationPlotDetails =
          fetchObservationPlotDetails(observationId).associateBy { it.model.monitoringPlotId }
      val plantingZoneIds: Map<MonitoringPlotId, PlantingZoneId> =
          dslContext
              .select(MONITORING_PLOTS.ID, PLANTING_SUBZONES.PLANTING_ZONE_ID)
              .from(PLANTING_SUBZONES)
              .join(MONITORING_PLOTS)
              .on(PLANTING_SUBZONES.ID.eq(MONITORING_PLOTS.PLANTING_SUBZONE_ID))
              .where(
                  MONITORING_PLOTS.ID.`in`(
                      observationPlotDetails.values.map { it.model.monitoringPlotId }))
              .fetchMap(
                  MONITORING_PLOTS.ID.asNonNullable(),
                  PLANTING_SUBZONES.PLANTING_ZONE_ID.asNonNullable())

      // Make the raw data (individual recorded plants) refer to the target species. This has no
      // impact on statistics; those are adjusted later.
      with(RECORDED_PLANTS) {
        dslContext
            .update(RECORDED_PLANTS)
            .set(CERTAINTY_ID, RecordedSpeciesCertainty.Known)
            .set(SPECIES_ID, speciesId)
            .setNull(SPECIES_NAME)
            .where(OBSERVATION_ID.eq(observationId))
            .and(SPECIES_NAME.eq(otherSpeciesName))
            .and(CERTAINTY_ID.eq(RecordedSpeciesCertainty.Other))
            .execute()
      }

      // We need to adjust the plot-level summary statistics for all the sightings of the Other
      // species in this observation. Updating the plot-level statistics will automatically also
      // update the site- and zone-level ones.
      val plotTotals: List<ObservedPlotSpeciesTotalsRecord> =
          with(OBSERVED_PLOT_SPECIES_TOTALS) {
            dslContext
                .selectFrom(OBSERVED_PLOT_SPECIES_TOTALS)
                .where(OBSERVATION_ID.eq(observationId))
                .and(SPECIES_NAME.eq(otherSpeciesName))
                .and(CERTAINTY_ID.eq(RecordedSpeciesCertainty.Other))
                .fetch()
          }

      plotTotals.forEach { plotTotal ->
        val monitoringPlotId = plotTotal.monitoringPlotId
        val plotDetails =
            observationPlotDetails[monitoringPlotId]
                ?: throw IllegalStateException(
                    "Monitoring plot $monitoringPlotId has species totals for $otherSpeciesName " +
                        "in observation $observationId but is not in observation")
        val plantingZoneId =
            plantingZoneIds[monitoringPlotId]
                ?: throw IllegalStateException(
                    "Unable to look up planting zone for monitoring plot $monitoringPlotId")

        // Subtract the plot-level live/dead/existing counts from the Other species and add them
        // to the target species. This propagates the changes up to the zone and site totals.
        // Once this has been done for all the plot-level totals, the end result will be that the
        // plot, zone, and site totals for the Other species will all be zero.
        //
        // We have to cancel out the Other totals rather than just deleting them because the same
        // Other species might appear in later observations, in which case the cumulative dead and
        // mortality rate in those observations will need to change to the values they would have
        // had if the Other species hadn't been recorded in this observation.
        updateSpeciesTotals(
            observationId,
            observation.plantingSiteId,
            plantingZoneId,
            monitoringPlotId,
            observation.isAdHoc,
            plotDetails.model.isPermanent,
            mapOf(
                RecordedSpeciesKey(RecordedSpeciesCertainty.Other, null, otherSpeciesName) to
                    mapOf(
                        RecordedPlantStatus.Live to -(plotTotal.totalLive ?: 0),
                        RecordedPlantStatus.Dead to -(plotTotal.totalDead ?: 0),
                        RecordedPlantStatus.Existing to -(plotTotal.totalExisting ?: 0),
                    ),
                RecordedSpeciesKey(RecordedSpeciesCertainty.Known, speciesId, null) to
                    mapOf(
                        RecordedPlantStatus.Live to (plotTotal.totalLive ?: 0),
                        RecordedPlantStatus.Dead to (plotTotal.totalDead ?: 0),
                        RecordedPlantStatus.Existing to (plotTotal.totalExisting ?: 0))))
      }

      // The plant counts for the Other species have been emptied out for this observation. We want
      // the end result to be as if people had never recorded the Other species in the first place,
      // so we need to delete its statistics or else the Other species will still be included by
      // queries that list all the recorded species in an observation.
      with(OBSERVED_PLOT_SPECIES_TOTALS) {
        dslContext
            .deleteFrom(OBSERVED_PLOT_SPECIES_TOTALS)
            .where(OBSERVATION_ID.eq(observationId))
            .and(SPECIES_NAME.eq(otherSpeciesName))
            .and(CERTAINTY_ID.eq(RecordedSpeciesCertainty.Other))
            .execute()
      }

      with(OBSERVED_ZONE_SPECIES_TOTALS) {
        dslContext
            .deleteFrom(OBSERVED_ZONE_SPECIES_TOTALS)
            .where(OBSERVATION_ID.eq(observationId))
            .and(SPECIES_NAME.eq(otherSpeciesName))
            .and(CERTAINTY_ID.eq(RecordedSpeciesCertainty.Other))
            .execute()
      }

      with(OBSERVED_SITE_SPECIES_TOTALS) {
        dslContext
            .deleteFrom(OBSERVED_SITE_SPECIES_TOTALS)
            .where(OBSERVATION_ID.eq(observationId))
            .and(SPECIES_NAME.eq(otherSpeciesName))
            .and(CERTAINTY_ID.eq(RecordedSpeciesCertainty.Other))
            .execute()
      }
    }
  }

  /**
   * Fetches last completed observation for a planting site if there are no other upcoming or in
   * progress observations, otherwise returns most recently updated observation.
   */
  fun fetchLastCompletedObservationTime(plantingSiteId: PlantingSiteId): Instant? {
    return dslContext
        .select(DSL.max(OBSERVATIONS.COMPLETED_TIME))
        .from(OBSERVATIONS)
        .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
        .andNotExists(
            DSL.selectOne()
                .from(OBSERVATIONS)
                .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
                .and(
                    OBSERVATIONS.STATE_ID.`in`(
                        ObservationState.InProgress,
                        ObservationState.Overdue,
                        ObservationState.Upcoming)))
        .fetchOne(DSL.max(OBSERVATIONS.COMPLETED_TIME))
  }

  fun updatePlotObservation(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      coordinates: List<NewObservedPlotCoordinatesModel>
  ) {
    requirePermissions { updateObservation(observationId) }

    dslContext.transaction { _ ->
      val existingCoordinates =
          dslContext
              .selectFrom(OBSERVED_PLOT_COORDINATES)
              .where(OBSERVED_PLOT_COORDINATES.OBSERVATION_ID.eq(observationId))
              .and(OBSERVED_PLOT_COORDINATES.MONITORING_PLOT_ID.eq(monitoringPlotId))
              .fetch()

      val coordinateIdsToDelete =
          existingCoordinates
              .filter { existing ->
                coordinates.none {
                  it.position == existing.positionId && it.gpsCoordinates == existing.gpsCoordinates
                }
              }
              .map { it.id!! }
      val coordinatesToInsert =
          coordinates.filter { desired ->
            existingCoordinates.none {
              it.positionId == desired.position && it.gpsCoordinates == desired.gpsCoordinates
            }
          }

      if (coordinateIdsToDelete.isNotEmpty()) {
        dslContext
            .deleteFrom(OBSERVED_PLOT_COORDINATES)
            .where(OBSERVED_PLOT_COORDINATES.ID.`in`(coordinateIdsToDelete))
            .execute()
      }

      coordinatesToInsert.forEach { desired ->
        dslContext
            .insertInto(OBSERVED_PLOT_COORDINATES)
            .set(OBSERVED_PLOT_COORDINATES.OBSERVATION_ID, observationId)
            .set(OBSERVED_PLOT_COORDINATES.MONITORING_PLOT_ID, monitoringPlotId)
            .set(OBSERVED_PLOT_COORDINATES.POSITION_ID, desired.position)
            .set(OBSERVED_PLOT_COORDINATES.GPS_COORDINATES, desired.gpsCoordinates)
            .execute()
      }
    }
  }

  fun hasObservations(plantingSiteId: PlantingSiteId): Boolean {
    return dslContext.fetchExists(OBSERVATIONS, OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
  }

  /**
   * Deletes the observation if no plot has been observed, or sets the observation status to
   * "Abandoned" and all unobserved plots' statuses to "Not Observed".
   */
  fun abandonObservation(observationId: ObservationId) {
    requirePermissions { updateObservation(observationId) }

    val observation = fetchObservationById(observationId)

    if (observation.state == ObservationState.Completed ||
        observation.state == ObservationState.Abandoned) {
      throw ObservationAlreadyEndedException(observationId)
    }

    val hasCompletedPlots =
        dslContext.fetchExists(
            OBSERVATION_PLOTS,
            OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId),
            OBSERVATION_PLOTS.STATUS_ID.eq(ObservationPlotStatus.Completed))

    if (hasCompletedPlots) {
      dslContext.transaction { _ ->
        abandonPlots(observationId)
        updateObservationState(observationId, ObservationState.Abandoned)
        resetPlantPopulationSinceLastObservation(observation.plantingSiteId)
      }
    } else {
      deleteObservation(observationId)
    }
  }

  private fun completeObservation(observationId: ObservationId, plantingSiteId: PlantingSiteId) {
    updateObservationState(observationId, ObservationState.Completed)
    resetPlantPopulationSinceLastObservation(plantingSiteId)
  }

  private fun deleteObservation(observationId: ObservationId) {
    dslContext.transaction { _ ->
      dslContext
          .deleteFrom(OBSERVATION_PLOTS)
          .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
          .execute()

      dslContext.deleteFrom(OBSERVATIONS).where(OBSERVATIONS.ID.eq(observationId)).execute()
    }
  }

  private fun resetPlantPopulationSinceLastObservation(plantingSiteId: PlantingSiteId) {
    dslContext
        .update(PLANTING_SITE_POPULATIONS)
        .set(PLANTING_SITE_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION, 0)
        .where(PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
        .execute()

    dslContext
        .update(PLANTING_ZONE_POPULATIONS)
        .set(PLANTING_ZONE_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION, 0)
        .where(
            PLANTING_ZONE_POPULATIONS.PLANTING_ZONE_ID.`in`(
                DSL.select(PLANTING_ZONES.ID)
                    .from(PLANTING_ZONES)
                    .where(PLANTING_ZONES.PLANTING_SITE_ID.eq(plantingSiteId))))
        .execute()

    dslContext
        .update(PLANTING_SUBZONE_POPULATIONS)
        .set(PLANTING_SUBZONE_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION, 0)
        .where(
            PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID.`in`(
                DSL.select(PLANTING_SUBZONES.ID)
                    .from(PLANTING_SUBZONES)
                    .where(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(plantingSiteId))))
        .execute()
  }

  /**
   * Populates the cumulative dead counts for permanent monitoring plots so that dead plants from
   * previous observations can be included in mortality rate calculations.
   *
   * Temporary monitoring plots are excluded because mortality rate calculations aren't meaningful
   * if we have no way of telling how many plants died and fully decayed prior to the first
   * observation. With permanent monitoring plots, the assumption is that observations will happen
   * often enough that all dead plants will be counted.
   *
   * We only include plants from plots that are marked as permanent in the current observation. If
   * the number of permanent monitoring plots decreases between observations, plants from the plots
   * that used to be marked as permanent, but no longer are, won't be included in the totals. To
   * make that work, we can't just copy the zone- and site-level totals from the previous
   * observation; we have to compute them from scratch using the current observation's list of
   * permanent plots.
   *
   * This is called when an observation is started, meaning that the cumulative dead counts are
   * guaranteed to already be present when a plot is completed; [updateSpeciesTotalsTable] can thus
   * assume that if there aren't already totals present for a given species in a permanent
   * monitoring plot, there must not have been any dead plants in previous observations.
   */
  fun populateCumulativeDead(observationId: ObservationId) {
    requirePermissions { updateObservation(observationId) }

    val observation = fetchObservationById(observationId)

    val previousObservationId =
        dslContext
            .select(OBSERVATIONS.ID)
            .from(OBSERVATIONS)
            .where(
                OBSERVATIONS.STATE_ID.`in`(ObservationState.Completed, ObservationState.Abandoned))
            .and(OBSERVATIONS.PLANTING_SITE_ID.eq(observation.plantingSiteId))
            .and(OBSERVATIONS.ID.ne(observationId))
            .orderBy(OBSERVATIONS.COMPLETED_TIME.desc())
            .limit(1)
            .fetchOne(OBSERVATIONS.ID) ?: return

    dslContext.transaction { _ ->
      with(OBSERVED_PLOT_SPECIES_TOTALS) {
        dslContext
            .insertInto(
                OBSERVED_PLOT_SPECIES_TOTALS,
                CERTAINTY_ID,
                CUMULATIVE_DEAD,
                MONITORING_PLOT_ID,
                MORTALITY_RATE,
                OBSERVATION_ID,
                SPECIES_ID,
                SPECIES_NAME)
            .select(
                DSL.select(
                        CERTAINTY_ID,
                        CUMULATIVE_DEAD,
                        MONITORING_PLOT_ID,
                        DSL.value(100),
                        DSL.value(observationId),
                        SPECIES_ID,
                        SPECIES_NAME)
                    .from(OBSERVED_PLOT_SPECIES_TOTALS)
                    .join(OBSERVATION_PLOTS)
                    .on(MONITORING_PLOT_ID.eq(OBSERVATION_PLOTS.MONITORING_PLOT_ID))
                    .where(OBSERVATION_ID.eq(previousObservationId))
                    .and(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
                    .and(OBSERVATION_PLOTS.IS_PERMANENT)
                    .and(CUMULATIVE_DEAD.gt(0)))
            .execute()
      }

      // Roll up the just-inserted plot totals (which only include plots that are currently
      // permanent and that had dead plants previously) to get the zone totals.

      with(OBSERVED_ZONE_SPECIES_TOTALS) {
        dslContext
            .insertInto(
                OBSERVED_ZONE_SPECIES_TOTALS,
                CERTAINTY_ID,
                CUMULATIVE_DEAD,
                MORTALITY_RATE,
                OBSERVATION_ID,
                PLANTING_ZONE_ID,
                SPECIES_ID,
                SPECIES_NAME)
            .select(
                DSL.select(
                        OBSERVED_PLOT_SPECIES_TOTALS.CERTAINTY_ID,
                        DSL.sum(OBSERVED_PLOT_SPECIES_TOTALS.CUMULATIVE_DEAD).cast(Int::class.java),
                        DSL.value(100),
                        DSL.value(observationId),
                        PLANTING_SUBZONES.PLANTING_ZONE_ID,
                        OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID,
                        OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_NAME)
                    .from(OBSERVED_PLOT_SPECIES_TOTALS)
                    .join(MONITORING_PLOTS)
                    .on(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                    .join(PLANTING_SUBZONES)
                    .on(MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
                    .where(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
                    .groupBy(
                        OBSERVED_PLOT_SPECIES_TOTALS.CERTAINTY_ID,
                        PLANTING_SUBZONES.PLANTING_ZONE_ID,
                        OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID,
                        OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_NAME))
            .execute()
      }

      // Roll up the just-inserted zone totals to get the site totals.

      with(OBSERVED_SITE_SPECIES_TOTALS) {
        dslContext
            .insertInto(
                OBSERVED_SITE_SPECIES_TOTALS,
                CERTAINTY_ID,
                CUMULATIVE_DEAD,
                MORTALITY_RATE,
                OBSERVATION_ID,
                PLANTING_SITE_ID,
                SPECIES_ID,
                SPECIES_NAME)
            .select(
                DSL.select(
                        OBSERVED_ZONE_SPECIES_TOTALS.CERTAINTY_ID,
                        DSL.sum(OBSERVED_ZONE_SPECIES_TOTALS.CUMULATIVE_DEAD).cast(Int::class.java),
                        DSL.value(100),
                        DSL.value(observationId),
                        DSL.value(observation.plantingSiteId),
                        OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_ID,
                        OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_NAME)
                    .from(OBSERVED_ZONE_SPECIES_TOTALS)
                    .where(OBSERVED_ZONE_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
                    .groupBy(
                        OBSERVED_ZONE_SPECIES_TOTALS.CERTAINTY_ID,
                        OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_ID,
                        OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_NAME))
            .execute()
      }
    }
  }

  /** Sets the statuses of the incomplete observation plots to be "Not Observed" */
  private fun abandonPlots(observationId: ObservationId) {
    dslContext
        .update(OBSERVATION_PLOTS)
        .set(OBSERVATION_PLOTS.STATUS_ID, ObservationPlotStatus.NotObserved)
        .setNull(OBSERVATION_PLOTS.CLAIMED_BY)
        .setNull(OBSERVATION_PLOTS.CLAIMED_TIME)
        .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
        .and(OBSERVATION_PLOTS.STATUS_ID.notEqual(ObservationPlotStatus.Completed))
        .execute()
  }

  private fun insertObservationPlots(
      observationId: ObservationId,
      plotIds: Collection<MonitoringPlotId>,
      isPermanent: Boolean
  ) {
    val createdBy = currentUser().userId
    val createdTime = clock.instant()

    plotIds.forEach { plotId ->
      with(OBSERVATION_PLOTS) {
        dslContext
            .insertInto(OBSERVATION_PLOTS)
            .set(CREATED_BY, createdBy)
            .set(CREATED_TIME, createdTime)
            .set(IS_PERMANENT, isPermanent)
            .set(MODIFIED_BY, createdBy)
            .set(MODIFIED_TIME, createdTime)
            .set(
                MONITORING_PLOT_HISTORY_ID,
                DSL.select(DSL.max(MONITORING_PLOT_HISTORIES.ID))
                    .from(MONITORING_PLOT_HISTORIES)
                    .where(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(plotId)))
            .set(MONITORING_PLOT_ID, plotId)
            .set(OBSERVATION_ID, observationId)
            .set(STATUS_ID, ObservationPlotStatus.Unclaimed)
            .execute()
      }
    }
  }

  /** Updates the tables that hold the aggregated per-species plant totals from observations. */
  private fun updateSpeciesTotals(
      observationId: ObservationId,
      plantingSiteId: PlantingSiteId,
      plantingZoneId: PlantingZoneId?,
      monitoringPlotId: MonitoringPlotId?,
      isAdHoc: Boolean,
      isPermanent: Boolean,
      plantCountsBySpecies: Map<RecordedSpeciesKey, Map<RecordedPlantStatus, Int>>
  ) {
    if (plantCountsBySpecies.isNotEmpty()) {
      if (monitoringPlotId != null) {
        updateSpeciesTotalsTable(
            OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID,
            observationId,
            monitoringPlotId,
            isPermanent,
            plantCountsBySpecies,
        )
      }

      if (!isAdHoc) {
        if (plantingZoneId != null) {
          updateSpeciesTotalsTable(
              OBSERVED_ZONE_SPECIES_TOTALS.PLANTING_ZONE_ID,
              observationId,
              plantingZoneId,
              isPermanent,
              plantCountsBySpecies,
          )
        }

        updateSpeciesTotalsTable(
            OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID,
            observationId,
            plantingSiteId,
            isPermanent,
            plantCountsBySpecies,
        )
      }
    }
  }

  /**
   * Updates one of the tables that holds the aggregated per-species plant totals from observations.
   *
   * These tables are all identical with the exception of one column that identifies the scope of
   * aggregation (monitoring plot, planting zone, or planting site).
   */
  private fun <ID : Any> updateSpeciesTotalsTable(
      scopeIdField: TableField<*, ID?>,
      observationId: ObservationId,
      scopeId: ID,
      isPermanent: Boolean,
      totals: Map<RecordedSpeciesKey, Map<RecordedPlantStatus, Int>>,
  ) {
    val table = scopeIdField.table!!
    val observationIdField =
        table.field(
            "observation_id", SQLDataType.BIGINT.asConvertedDataType(ObservationIdConverter()))!!
    val certaintyField =
        table.field(
            "certainty_id",
            SQLDataType.INTEGER.asConvertedDataType(RecordedSpeciesCertaintyConverter()))!!
    val speciesIdField =
        table.field("species_id", SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()))!!
    val speciesNameField = table.field("species_name", String::class.java)!!
    val totalLiveField = table.field("total_live", Int::class.java)!!
    val totalDeadField = table.field("total_dead", Int::class.java)!!
    val totalExistingField = table.field("total_existing", Int::class.java)!!
    val mortalityRateField = table.field("mortality_rate", Int::class.java)!!
    val cumulativeDeadField = table.field("cumulative_dead", Int::class.java)!!
    val permanentLiveField = table.field("permanent_live", Int::class.java)!!

    dslContext.transaction { _ ->
      totals.forEach { (speciesKey, statusCounts) ->
        val totalLive = statusCounts.getOrDefault(RecordedPlantStatus.Live, 0)
        val totalDead = statusCounts.getOrDefault(RecordedPlantStatus.Dead, 0)
        val totalExisting = statusCounts.getOrDefault(RecordedPlantStatus.Existing, 0)
        val permanentDead: Int
        val permanentLive: Int
        val existingCumulativeDead: Int

        if (isPermanent) {
          permanentDead = totalDead
          permanentLive = totalLive

          existingCumulativeDead =
              dslContext
                  .select(cumulativeDeadField)
                  .from(table)
                  .where(scopeIdField.eq(scopeId))
                  .and(observationIdField.le(observationId))
                  .and(certaintyField.eq(speciesKey.certainty))
                  .and(
                      if (speciesKey.id != null) speciesIdField.eq(speciesKey.id)
                      else speciesIdField.isNull)
                  .and(
                      if (speciesKey.name != null) speciesNameField.eq(speciesKey.name)
                      else speciesNameField.isNull)
                  .orderBy(observationIdField.desc())
                  .limit(1)
                  .fetchOne(cumulativeDeadField) ?: 0
        } else {
          permanentDead = 0
          permanentLive = 0
          existingCumulativeDead = 0
        }

        val cumulativeDead = existingCumulativeDead + permanentDead
        val totalPlants = permanentLive + cumulativeDead

        val mortalityRate =
            if (isPermanent) {
              if (totalPlants == 0) {
                0
              } else {
                (cumulativeDead * 100.0 / totalPlants).roundToInt()
              }
            } else {
              null
            }

        val rowsInserted =
            dslContext
                .insertInto(
                    table,
                    observationIdField,
                    scopeIdField,
                    certaintyField,
                    speciesIdField,
                    speciesNameField,
                    totalLiveField,
                    totalDeadField,
                    totalExistingField,
                    cumulativeDeadField,
                    permanentLiveField,
                    mortalityRateField)
                .values(
                    observationId,
                    scopeId,
                    speciesKey.certainty,
                    speciesKey.id,
                    speciesKey.name,
                    totalLive,
                    totalDead,
                    totalExisting,
                    cumulativeDead,
                    permanentLive,
                    mortalityRate)
                .onConflictDoNothing()
                .execute()

        if (rowsInserted == 0) {
          val scopeIdAndSpeciesCondition =
              scopeIdField
                  .eq(scopeId)
                  .and(
                      if (speciesKey.id != null) speciesIdField.eq(speciesKey.id)
                      else speciesIdField.isNull)
                  .and(
                      if (speciesKey.name != null) speciesNameField.eq(speciesKey.name)
                      else speciesNameField.isNull)

          // If we are updating a past observation (e.g., when removing a plot due to a map edit),
          // the cumulative dead counts for the current observation as well as any subsequent ones
          // need to be updated, as do their mortality rates.
          if (permanentLive != 0 || permanentDead != 0) {
            val mortalityRateDenominatorField =
                permanentLiveField
                    .plus(cumulativeDeadField)
                    .plus(permanentDead)
                    .plus(
                        // For this observation, the adjustment to the live plants count needs to be
                        // included in the mortality rate denominator. But the live plant counts
                        // in subsequent observations are already correct.
                        DSL.case_(observationIdField).`when`(observationId, permanentLive).else_(0))

            dslContext
                .update(table)
                .set(cumulativeDeadField, cumulativeDeadField.plus(permanentDead))
                .set(
                    mortalityRateField,
                    DSL.case_()
                        .`when`(mortalityRateDenominatorField.eq(0), 0)
                        .else_(
                            (cumulativeDeadField
                                    .cast(SQLDataType.NUMERIC)
                                    .plus(permanentDead)
                                    .times(100)
                                    .div(mortalityRateDenominatorField))
                                .cast(SQLDataType.INTEGER)))
                .where(observationIdField.ge(observationId))
                .and(scopeIdAndSpeciesCondition)
                .execute()
          }

          val rowsUpdated =
              dslContext
                  .update(table)
                  .set(totalLiveField, totalLiveField.plus(totalLive))
                  .set(totalDeadField, totalDeadField.plus(totalDead))
                  .set(totalExistingField, totalExistingField.plus(totalExisting))
                  .set(permanentLiveField, permanentLiveField.plus(permanentLive))
                  .where(observationIdField.eq(observationId))
                  .and(scopeIdAndSpeciesCondition)
                  .execute()

          if (rowsUpdated != 1) {
            log.withMDC(
                "table" to table.name,
                "observation" to observationId,
                "scope" to scopeId,
                "species" to speciesKey,
            ) {
              log.error("BUG! Insert and update of species totals both failed")
            }
          }
        }
      }
    }
  }

  private fun validateAdHocPlotInPlantingSite(
      plantingSiteId: PlantingSiteId,
      plotId: MonitoringPlotId
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
          "BUG! Only an ad-hoc plot in the planting site can be added to an ad-hoc observation.")
    }
  }

  private fun validateNonAdHocPlotsInPlantingSite(
      plantingSiteId: PlantingSiteId,
      plotIds: Collection<MonitoringPlotId>
  ) {
    val plantingSiteField =
        DSL.ifnull(
            MONITORING_PLOTS.plantingSubzones.PLANTING_SITE_ID, MONITORING_PLOTS.PLANTING_SITE_ID)

    val nonMatchingPlot =
        dslContext
            .select(MONITORING_PLOTS.ID, plantingSiteField, MONITORING_PLOTS.IS_AD_HOC)
            .from(MONITORING_PLOTS)
            .where(MONITORING_PLOTS.ID.`in`(plotIds))
            .and(
                DSL.or(
                    plantingSiteField.ne(plantingSiteId),
                    MONITORING_PLOTS.IS_AD_HOC.isTrue(),
                ))
            .limit(1)
            .fetchOne()

    if (nonMatchingPlot != null) {
      if (nonMatchingPlot[plantingSiteField] != plantingSiteId) {
        throw IllegalStateException(
            "BUG! Plot ${nonMatchingPlot[MONITORING_PLOTS.ID]} is in site ${nonMatchingPlot[plantingSiteField]}, not $plantingSiteId")
      } else {
        throw IllegalStateException(
            "BUG! Plot ${nonMatchingPlot[MONITORING_PLOTS.ID]} is an ad-hoc plot")
      }
    }
  }

  data class RecordedSpeciesKey(
      val certainty: RecordedSpeciesCertainty,
      val id: SpeciesId?,
      val name: String?,
  )
}
