package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotConditionsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationRequestedSubstrataDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationsDao
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotConditionsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationRequestedSubstrataRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.records.ObservationPlotConditionsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservedPlotSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.RecordedPlantsRecord
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_CONDITIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_REQUESTED_SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_COORDINATES
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SURVIVAL_RATE_CALCULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_PLANTS
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.STRATUM_T0_TEMP_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_POPULATIONS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.log.withMDC
import com.terraformation.backend.tracking.event.MonitoringSpeciesTotalsEditedEvent
import com.terraformation.backend.tracking.event.ObservationCompletedEvent
import com.terraformation.backend.tracking.event.ObservationPlotCreatedEvent
import com.terraformation.backend.tracking.event.ObservationPlotEditedEvent
import com.terraformation.backend.tracking.event.ObservationStateUpdatedEvent
import com.terraformation.backend.tracking.event.T0PlotDataAssignedEvent
import com.terraformation.backend.tracking.event.T0StratumDataAssignedEvent
import com.terraformation.backend.tracking.model.AssignedPlotDetails
import com.terraformation.backend.tracking.model.EditableMonitoringSpeciesModel
import com.terraformation.backend.tracking.model.EditableObservationPlotDetailsModel
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.NewObservedPlotCoordinatesModel
import com.terraformation.backend.tracking.model.ObservationModel
import com.terraformation.backend.tracking.model.ObservationPlotCounts
import com.terraformation.backend.tracking.model.ObservationPlotModel
import com.terraformation.backend.tracking.util.ObservationResultsPlot
import com.terraformation.backend.tracking.util.ObservationResultsScope
import com.terraformation.backend.tracking.util.ObservationResultsSite
import com.terraformation.backend.tracking.util.ObservationResultsStratum
import com.terraformation.backend.tracking.util.ObservationResultsSubstratum
import com.terraformation.backend.tracking.util.ObservationSpeciesPlot
import com.terraformation.backend.tracking.util.ObservationSpeciesScope
import com.terraformation.backend.tracking.util.ObservationSpeciesSite
import com.terraformation.backend.tracking.util.ObservationSpeciesStratum
import com.terraformation.backend.tracking.util.ObservationSpeciesSubstratum
import com.terraformation.backend.util.HECTARES_PER_PLOT
import com.terraformation.backend.util.SQUARE_METERS_PER_HECTARE
import com.terraformation.backend.util.eqOrIsNull
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneOffset
import org.jobrunr.jobs.JobId
import org.jobrunr.scheduling.JobScheduler
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener

@Named
class ObservationStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    @Lazy private val jobScheduler: JobScheduler,
    private val observationLocker: ObservationLocker,
    private val observationsDao: ObservationsDao,
    private val observationPlotConditionsDao: ObservationPlotConditionsDao,
    private val observationPlotsDao: ObservationPlotsDao,
    private val observationRequestedSubstrataDao: ObservationRequestedSubstrataDao,
    private val parentStore: ParentStore,
    private val systemUser: SystemUser,
) {
  companion object {
    val requestedSubstratumIdsField: Field<Set<SubstratumId>> =
        with(OBSERVATION_REQUESTED_SUBSTRATA) {
          DSL.multiset(
                  DSL.select(SUBSTRATUM_ID)
                      .from(OBSERVATION_REQUESTED_SUBSTRATA)
                      .where(OBSERVATION_ID.eq(OBSERVATIONS.ID))
              )
              .convertFrom { result -> result.map { it[SUBSTRATUM_ID]!! }.toSet() }
        }

    private val log = perClassLogger()
  }

  fun fetchObservationById(observationId: ObservationId): ExistingObservationModel {
    requirePermissions { readObservation(observationId) }

    return dslContext
        .select(OBSERVATIONS.asterisk(), requestedSubstratumIdsField)
        .from(OBSERVATIONS)
        .where(OBSERVATIONS.ID.eq(observationId))
        .fetchOne { ObservationModel.of(it, requestedSubstratumIdsField) }
        ?: throw ObservationNotFoundException(observationId)
  }

  fun fetchObservationsByOrganization(
      organizationId: OrganizationId,
      isAdHoc: Boolean = false,
  ): List<ExistingObservationModel> {
    requirePermissions { readOrganization(organizationId) }

    return dslContext
        .select(OBSERVATIONS.asterisk(), requestedSubstratumIdsField)
        .from(OBSERVATIONS)
        .where(OBSERVATIONS.plantingSites.ORGANIZATION_ID.eq(organizationId))
        .and(OBSERVATIONS.IS_AD_HOC.eq(isAdHoc))
        .orderBy(OBSERVATIONS.START_DATE, OBSERVATIONS.ID)
        .fetch { ObservationModel.of(it, requestedSubstratumIdsField) }
  }

  fun fetchObservationsByPlantingSite(
      plantingSiteId: PlantingSiteId,
      isAdHoc: Boolean = false,
  ): List<ExistingObservationModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return dslContext
        .select(OBSERVATIONS.asterisk(), requestedSubstratumIdsField)
        .from(OBSERVATIONS)
        .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
        .and(OBSERVATIONS.IS_AD_HOC.eq(isAdHoc))
        .orderBy(OBSERVATIONS.START_DATE, OBSERVATIONS.ID)
        .fetch { ObservationModel.of(it, requestedSubstratumIdsField) }
  }

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
                  record[OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.FULL_NAME]!!,
              stratumName =
                  record[
                      OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.stratumHistories
                          .NAME]!!,
              plotNumber = record[OBSERVATION_PLOTS.monitoringPlots.PLOT_NUMBER]!!,
              sizeMeters = record[OBSERVATION_PLOTS.monitoringPlots.SIZE_METERS]!!,
          )
        }
  }

  /** Evaluates to true if an observation has requested substrata. */
  private val observationHasRequestedSubstrata: Condition =
      DSL.exists(
          DSL.selectOne()
              .from(OBSERVATION_REQUESTED_SUBSTRATA)
              .where(OBSERVATION_REQUESTED_SUBSTRATA.OBSERVATION_ID.eq(OBSERVATIONS.ID))
      )

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
            observationHasRequestedSubstrata,
        )
    ) { todayAtSite, record ->
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
            observationHasRequestedSubstrata,
            plantingSiteId?.let { OBSERVATIONS.PLANTING_SITE_ID.eq(it) },
        )
    ) { todayAtSite, record ->
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
        )
    ) { todayAtSite, record ->
      record[OBSERVATIONS.END_DATE]!! < todayAtSite
    }
  }

  /**
   * Returns a list of observations that match a set of conditions and match a predicate that uses
   * the current date in the site's local time zone.
   */
  private fun fetchWithDateFilter(
      conditions: List<Condition>,
      predicate: (LocalDate, Record) -> Boolean,
  ): List<ExistingObservationModel> {
    val timeZoneField =
        DSL.coalesce(
            OBSERVATIONS.plantingSites.TIME_ZONE,
            OBSERVATIONS.plantingSites.organizations.TIME_ZONE,
        )

    return dslContext
        .select(OBSERVATIONS.asterisk(), requestedSubstratumIdsField, timeZoneField)
        .from(OBSERVATIONS)
        .where(conditions)
        .orderBy(OBSERVATIONS.ID)
        .fetch { record ->
          val timeZone = record[timeZoneField] ?: ZoneOffset.UTC
          val todayAtSite = LocalDate.ofInstant(clock.instant(), timeZone)

          if (predicate(todayAtSite, record)) {
            ObservationModel.of(record, requestedSubstratumIdsField)
          } else {
            null
          }
        }
        .filter { it != null && currentUser().canManageObservation(it.id) }
  }

  /**
   * Returns the IDs of any active assigned observations of a planting site that include unobserved
   * plots in specific strata.
   */
  fun fetchActiveObservationIds(
      plantingSiteId: PlantingSiteId,
      stratumIds: Collection<StratumId>,
  ): List<ObservationId> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    if (stratumIds.isEmpty()) {
      return emptyList()
    }

    return with(OBSERVATIONS) {
      dslContext
          .select(ID)
          .from(OBSERVATIONS)
          .where(PLANTING_SITE_ID.eq(plantingSiteId))
          .and(IS_AD_HOC.eq(false))
          .and(STATE_ID.`in`(ObservationState.InProgress, ObservationState.Overdue))
          .and(
              ID.`in`(
                  DSL.select(OBSERVATION_PLOTS.OBSERVATION_ID)
                      .from(OBSERVATION_PLOTS)
                      .where(
                          OBSERVATION_PLOTS.monitoringPlots.substrata.STRATUM_ID.`in`(stratumIds)
                      )
                      .and(
                          OBSERVATION_PLOTS.STATUS_ID.`in`(
                              ObservationPlotStatus.Claimed,
                              ObservationPlotStatus.Unclaimed,
                          )
                      )
              )
          )
          .orderBy(ID)
          .fetch(ID.asNonNullable())
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
    fetchObservationById(observationId)

    return dslContext.fetchExists(
        DSL.selectOne()
            .from(OBSERVATION_PLOTS)
            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
    )
  }

  fun createObservation(newModel: NewObservationModel): ObservationId {
    requirePermissions {
      if (newModel.isAdHoc) {
        scheduleAdHocObservation(newModel.plantingSiteId)
      } else {
        createObservation(newModel.plantingSiteId)
      }
    }

    // Validate that all the requested substrata are part of the requested planting site.
    if (newModel.requestedSubstratumIds.isNotEmpty()) {
      val substrataInRequestedSite =
          dslContext
              .select(SUBSTRATA.ID)
              .from(SUBSTRATA)
              .where(SUBSTRATA.PLANTING_SITE_ID.eq(newModel.plantingSiteId))
              .and(SUBSTRATA.ID.`in`(newModel.requestedSubstratumIds))
              .fetchSet(SUBSTRATA.ID.asNonNullable())

      if (substrataInRequestedSite != newModel.requestedSubstratumIds) {
        val missingSubstratumIds = newModel.requestedSubstratumIds - substrataInRequestedSite
        throw SubstratumNotFoundException(missingSubstratumIds.first())
      }

      if (newModel.isAdHoc) {
        throw IllegalArgumentException("Requested substrata must be empty for ad-hoc observations")
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

      newModel.requestedSubstratumIds.forEach { substratumId ->
        observationRequestedSubstrataDao.insert(
            ObservationRequestedSubstrataRow(row.id, substratumId)
        )
      }

      row.id!!
    }
  }

  fun rescheduleObservation(
      observationId: ObservationId,
      startDate: LocalDate,
      endDate: LocalDate,
  ) {
    requirePermissions { updateObservation(observationId) }

    observationLocker.withLockedObservation(observationId) { _ ->
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
      // about which substrata are planted. Delete the existing plots. This is a no-op if the
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

    observationLocker.withLockedObservation(observationId) { observation ->
      observation.validateStateTransition(newState)

      val maxCompletedTime =
          if (newState == ObservationState.Completed || newState == ObservationState.Abandoned) {
            dslContext
                .select(DSL.max(OBSERVATION_PLOTS.COMPLETED_TIME))
                .from(OBSERVATION_PLOTS)
                .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
                .and(OBSERVATION_PLOTS.STATUS_ID.eq(ObservationPlotStatus.Completed))
                .fetchOneInto(Instant::class.java)
                ?: throw IllegalStateException("Observation $observationId has no completed plots")
          } else {
            null
          }

      dslContext
          .update(OBSERVATIONS)
          .set(OBSERVATIONS.COMPLETED_TIME, maxCompletedTime)
          .set(OBSERVATIONS.STATE_ID, newState)
          .where(OBSERVATIONS.ID.eq(observationId))
          .execute()

      eventPublisher.publishEvent(ObservationStateUpdatedEvent(observationId, newState))
    }
  }

  fun recordObservationStart(observationId: ObservationId): ExistingObservationModel {
    requirePermissions { manageObservation(observationId) }

    return observationLocker.withLockedObservation(observationId) { observation ->
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
          plantingSiteHistoryId = plantingSiteHistoryId,
          state = ObservationState.InProgress,
      )
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
      isPermanent: Boolean,
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
      plotIds: Collection<MonitoringPlotId>,
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
      val (isAdHoc) =
          dslContext
              .select(
                  MONITORING_PLOTS.IS_AD_HOC.asNonNullable(),
              )
              .from(MONITORING_PLOTS)
              .where(MONITORING_PLOTS.ID.eq(monitoringPlotId))
              .fetchOne()!!

      val (
          monitoringPlotHistoryId,
          substratumHistoryId,
          substratumId,
          stratumHistoryId,
          stratumId,
          plantingSiteHistoryId,
          plantingSiteId,
      ) = dslContext
          .select(
              OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID.asNonNullable(),
              OBSERVATION_PLOTS.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID,
              OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.SUBSTRATUM_ID,
              OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.STRATUM_HISTORY_ID,
              OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.stratumHistories
                  .STRATUM_ID,
              OBSERVATION_PLOTS.observations.PLANTING_SITE_HISTORY_ID.asNonNullable(),
              OBSERVATION_PLOTS.observations.PLANTING_SITE_ID.asNonNullable(),
          )
          .from(OBSERVATION_PLOTS)
          .where(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
          .and(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
          .fetchOne() ?: throw PlotNotInObservationException(observationId, monitoringPlotId)

      // We will be calculating cumulative totals across all observations of a planting site and
      // across monitoring plots and strata; guard against multiple submissions arriving at
      // once and causing data races.
      val plantingSite =
          dslContext
              .select()
              .from(PLANTING_SITES)
              .where(PLANTING_SITES.ID.eq(plantingSiteId))
              .forUpdate()
              .fetchOneInto(PlantingSitesRow::class.java)
              ?: throw PlantingSiteNotFoundException(plantingSiteId)

      val observationPlotsRow =
          dslContext
              .selectFrom(OBSERVATION_PLOTS)
              .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
              .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
              .fetchOneInto(ObservationPlotsRow::class.java)
              ?: throw PlotNotInObservationException(observationId, monitoringPlotId)

      if (
          observationPlotsRow.statusId == ObservationPlotStatus.Completed ||
              observationPlotsRow.statusId == ObservationPlotStatus.NotObserved
      ) {
        throw PlotAlreadyCompletedException(monitoringPlotId)
      }

      observationPlotConditionsDao.insert(
          conditions.map { ObservationPlotConditionsRow(observationId, monitoringPlotId, it) }
      )

      val plantsRecords = plants.map { plantsRow ->
        RecordedPlantsRecord(
            certaintyId = plantsRow.certaintyId,
            gpsCoordinates = plantsRow.gpsCoordinates,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            speciesId = plantsRow.speciesId,
            speciesName = plantsRow.speciesName,
            statusId = plantsRow.statusId,
        )
      }

      dslContext.batchInsert(plantsRecords).execute()

      val plantCountsBySpecies: Map<RecordedSpeciesKey, Map<RecordedPlantStatus, Int>> =
          plants
              .groupBy { RecordedSpeciesKey(it.certaintyId!!, it.speciesId, it.speciesName) }
              .mapValues { (_, rowsForSpecies) ->
                rowsForSpecies
                    .groupBy { it.statusId!! }
                    .mapValues { (_, rowsForStatus) -> rowsForStatus.size }
              }

      updateSpeciesTotals(
          observationId,
          plantingSite,
          plantingSiteHistoryId,
          stratumId,
          stratumHistoryId,
          substratumId,
          substratumHistoryId,
          monitoringPlotId,
          monitoringPlotHistoryId,
          isAdHoc,
          observationPlotsRow.isPermanent!!,
          plantCountsBySpecies,
      )

      updateObservationResults(
          observationId,
          plantingSite,
          stratumId,
          stratumHistoryId,
          substratumId,
          substratumHistoryId,
          monitoringPlotId,
          isAdHoc,
      )

      observationPlotsDao.update(
          observationPlotsRow.copy(
              completedBy = currentUser().userId,
              completedTime = clock.instant(),
              notes = notes,
              observedTime = observedTime,
              statusId = ObservationPlotStatus.Completed,
          )
      )

      if (substratumId != null) {
        updateSubstratumObservedTime(substratumId, observedTime)
      }

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
        completeObservation(observationId, plantingSiteId, isAdHoc)
      } else {
        if (!isAdHoc) {
          recalculateSurvivalRateResults(observationId, monitoringPlotId)
        }
      }
    }
  }

  /** Updates the observed time of a substratum if it wasn't already observed more recently. */
  private fun updateSubstratumObservedTime(
      substratumId: SubstratumId,
      observedTime: Instant,
  ) {
    with(SUBSTRATA) {
      dslContext
          .update(SUBSTRATA)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(OBSERVED_TIME, observedTime)
          .where(ID.eq(substratumId))
          .and(OBSERVED_TIME.isNull.or(OBSERVED_TIME.lt(observedTime)))
          .execute()
    }
  }

  @Deprecated("Call ObservationService.mergeOtherSpecies instead.")
  fun mergeOtherSpeciesForMonitoring(
      observationId: ObservationId,
      plantingSiteId: PlantingSiteId,
      isAdHoc: Boolean,
      otherSpeciesName: String,
      speciesId: SpeciesId,
  ) {
    val observationPlotDetails =
        dslContext
            .select(
                OBSERVATION_PLOTS.MONITORING_PLOT_ID,
                OBSERVATION_PLOTS.IS_PERMANENT,
                MONITORING_PLOT_HISTORIES.ID,
                OBSERVATIONS.PLANTING_SITE_HISTORY_ID,
                SUBSTRATUM_HISTORIES.ID,
                SUBSTRATUM_HISTORIES.SUBSTRATUM_ID,
                SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID,
                STRATUM_HISTORIES.STRATUM_ID,
            )
            .from(OBSERVATION_PLOTS)
            .join(OBSERVATIONS)
            .on(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
            .join(MONITORING_PLOT_HISTORIES)
            .on(
                OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(
                    MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID
                )
            )
            .and(
                OBSERVATIONS.PLANTING_SITE_HISTORY_ID.eq(
                    MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID
                )
            )
            .leftJoin(SUBSTRATUM_HISTORIES)
            .on(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID.eq(SUBSTRATUM_HISTORIES.ID))
            .leftJoin(STRATUM_HISTORIES)
            .on(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(STRATUM_HISTORIES.ID))
            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
            .fetch()
            .associateBy { it[OBSERVATION_PLOTS.MONITORING_PLOT_ID]!! }

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
    // update the site- and stratum-level ones.
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
                      "in observation $observationId but is not in observation",
              )

      val plantingSite =
          dslContext
              .select()
              .from(PLANTING_SITES)
              .where(PLANTING_SITES.ID.eq(plantingSiteId))
              .fetchOneInto(PlantingSitesRow::class.java)
              ?: throw PlantingSiteNotFoundException(plantingSiteId)

      // Add the plot-level live/dead/existing counts to the target species. This propagates the
      // changes up to the stratum and site totals.
      updateSpeciesTotals(
          observationId,
          plantingSite,
          plotDetails[OBSERVATIONS.PLANTING_SITE_HISTORY_ID]!!,
          plotDetails[STRATUM_HISTORIES.STRATUM_ID],
          plotDetails[SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID],
          plotDetails[SUBSTRATUM_HISTORIES.SUBSTRATUM_ID],
          plotDetails[SUBSTRATUM_HISTORIES.ID],
          monitoringPlotId,
          plotDetails[MONITORING_PLOT_HISTORIES.ID]!!,
          isAdHoc,
          plotDetails[OBSERVATION_PLOTS.IS_PERMANENT]!!,
          mapOf(
              RecordedSpeciesKey(RecordedSpeciesCertainty.Known, speciesId, null) to
                  mapOf(
                      RecordedPlantStatus.Live to (plotTotal.totalLive ?: 0),
                      RecordedPlantStatus.Dead to (plotTotal.totalDead ?: 0),
                      RecordedPlantStatus.Existing to (plotTotal.totalExisting ?: 0),
                  ),
          ),
      )
    }

    // The plant counts for the Other species have been added to the target species for this
    // observation. We want the end result to be as if people had never recorded the Other species
    // in the first place, so we need to delete its totals entirely.
    with(OBSERVED_PLOT_SPECIES_TOTALS) {
      dslContext
          .deleteFrom(OBSERVED_PLOT_SPECIES_TOTALS)
          .where(OBSERVATION_ID.eq(observationId))
          .and(SPECIES_NAME.eq(otherSpeciesName))
          .and(CERTAINTY_ID.eq(RecordedSpeciesCertainty.Other))
          .execute()
    }

    with(OBSERVED_SUBSTRATUM_SPECIES_TOTALS) {
      dslContext
          .deleteFrom(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
          .where(OBSERVATION_ID.eq(observationId))
          .and(SPECIES_NAME.eq(otherSpeciesName))
          .and(CERTAINTY_ID.eq(RecordedSpeciesCertainty.Other))
          .execute()
    }

    with(OBSERVED_STRATUM_SPECIES_TOTALS) {
      dslContext
          .deleteFrom(OBSERVED_STRATUM_SPECIES_TOTALS)
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
                        ObservationState.Upcoming,
                    )
                )
        )
        .fetchOne(DSL.max(OBSERVATIONS.COMPLETED_TIME))
  }

  private val observationPlotConditionsMultiset =
      with(OBSERVATION_PLOT_CONDITIONS) {
        DSL.multiset(
                DSL.select(CONDITION_ID)
                    .from(OBSERVATION_PLOT_CONDITIONS)
                    .where(OBSERVATION_ID.eq(OBSERVATION_PLOTS.OBSERVATION_ID))
                    .and(MONITORING_PLOT_ID.eq(OBSERVATION_PLOTS.MONITORING_PLOT_ID))
            )
            .convertFrom { result -> result.map { record -> record[CONDITION_ID]!! }.toSet() }
      }

  fun updateObservationPlotDetails(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      updateFunc: (EditableObservationPlotDetailsModel) -> EditableObservationPlotDetailsModel,
  ) {
    requirePermissions { updateObservation(observationId) }

    val organizationId =
        parentStore.getOrganizationId(observationId)
            ?: throw ObservationNotFoundException(observationId)

    observationLocker.withLockedObservation(observationId) { observation ->
      val existing =
          with(OBSERVATION_PLOTS) {
            val record =
                dslContext
                    .select(NOTES, STATUS_ID, observationPlotConditionsMultiset)
                    .from(OBSERVATION_PLOTS)
                    .where(OBSERVATION_ID.eq(observationId))
                    .and(MONITORING_PLOT_ID.eq(monitoringPlotId))
                    .fetchOne()
                    ?: throw ObservationPlotNotFoundException(observationId, monitoringPlotId)

            if (record[STATUS_ID] != ObservationPlotStatus.Completed) {
              throw PlotNotCompletedException(monitoringPlotId)
            }

            EditableObservationPlotDetailsModel.of(record, observationPlotConditionsMultiset)
          }

      val updated = updateFunc(existing)

      val changedFrom = existing.toEventValues(updated)
      val changedTo = updated.toEventValues(existing)

      if (changedFrom != changedTo) {
        if (changedFrom.notes != changedTo.notes) {
          with(OBSERVATION_PLOTS) {
            dslContext
                .update(OBSERVATION_PLOTS)
                .set(NOTES, updated.notes)
                .where(OBSERVATION_ID.eq(observationId))
                .and(MONITORING_PLOT_ID.eq(monitoringPlotId))
                .execute()
          }
        }

        val conditionsToDelete = existing.conditions - updated.conditions
        val conditionsToInsert = updated.conditions - existing.conditions

        if (conditionsToDelete.isNotEmpty()) {
          with(OBSERVATION_PLOT_CONDITIONS) {
            dslContext
                .deleteFrom(OBSERVATION_PLOT_CONDITIONS)
                .where(OBSERVATION_ID.eq(observationId))
                .and(MONITORING_PLOT_ID.eq(monitoringPlotId))
                .and(CONDITION_ID.`in`(conditionsToDelete))
                .execute()
          }
        }

        if (conditionsToInsert.isNotEmpty()) {
          dslContext
              .batchInsert(
                  conditionsToInsert.map {
                    ObservationPlotConditionsRecord(observationId, monitoringPlotId, it)
                  }
              )
              .execute()
        }

        eventPublisher.publishEvent(
            ObservationPlotEditedEvent(
                changedFrom = changedFrom,
                changedTo = changedTo,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = observation.plantingSiteId,
            )
        )
      }
    }
  }

  fun updateMonitoringSpecies(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      certainty: RecordedSpeciesCertainty,
      speciesId: SpeciesId?,
      speciesName: String?,
      updateFunc: (EditableMonitoringSpeciesModel) -> EditableMonitoringSpeciesModel,
  ) {
    requirePermissions { updateObservationQuantities(observationId) }

    observationLocker.withLockedObservation(observationId) { observation ->
      if (observation.observationType != ObservationType.Monitoring) {
        throw IllegalArgumentException("Observation type is not Monitoring")
      }

      val existing =
          with(OBSERVED_PLOT_SPECIES_TOTALS) {
            dslContext
                .select(TOTAL_DEAD, TOTAL_LIVE, TOTAL_EXISTING)
                .from(OBSERVED_PLOT_SPECIES_TOTALS)
                .where(OBSERVATION_ID.eq(observationId))
                .and(MONITORING_PLOT_ID.eq(monitoringPlotId))
                .and(CERTAINTY_ID.eq(certainty))
                .and(SPECIES_ID.eqOrIsNull(speciesId))
                .and(SPECIES_NAME.eqOrIsNull(speciesName))
                .fetchOne { EditableMonitoringSpeciesModel.of(it) }
                ?: throw SpeciesNotInObservationException(speciesId, speciesName)
          }

      val updated = updateFunc(existing)

      if (updated != existing) {
        // We need to update the totals for the stratum and substratum the plot was in at the time
        // of the observation, which might not be where it currently is.
        val (
            isPermanent,
            monitoringPlotHistoryId,
            substratumHistoryId,
            substratumId,
            stratumHistoryId,
            stratumId,
            plantingSiteHistoryId,
        ) = dslContext
            .select(
                OBSERVATION_PLOTS.IS_PERMANENT.asNonNullable(),
                OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID.asNonNullable(),
                OBSERVATION_PLOTS.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID,
                OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.SUBSTRATUM_ID,
                OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.STRATUM_HISTORY_ID,
                OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.stratumHistories
                    .STRATUM_ID,
                OBSERVATION_PLOTS.observations.PLANTING_SITE_HISTORY_ID.asNonNullable(),
            )
            .from(OBSERVATION_PLOTS)
            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
            .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
            .fetchOne() ?: throw PlotNotInObservationException(observationId, monitoringPlotId)

        val plantingSitesRow =
            dslContext
                .selectFrom(PLANTING_SITES)
                .where(PLANTING_SITES.ID.eq(observation.plantingSiteId))
                .fetchOneInto(PlantingSitesRow::class.java)
                ?: throw PlantingSiteNotFoundException(observation.plantingSiteId)

        val plantCountAdjustments =
            mapOf(
                RecordedSpeciesKey(certainty, speciesId, speciesName) to
                    mapOf(
                        RecordedPlantStatus.Dead to updated.totalDead - existing.totalDead,
                        RecordedPlantStatus.Existing to
                            updated.totalExisting - existing.totalExisting,
                        RecordedPlantStatus.Live to updated.totalLive - existing.totalLive,
                    )
            )

        updateSpeciesTotals(
            observationId,
            plantingSitesRow,
            plantingSiteHistoryId,
            stratumId,
            stratumHistoryId,
            substratumId,
            substratumHistoryId,
            monitoringPlotId,
            monitoringPlotHistoryId,
            observation.isAdHoc,
            isPermanent,
            plantCountAdjustments,
        )
        // Propagate the new totals from species to aggregate totals
        updateObservationResults(observationId, observation.plantingSiteId)
        recalculateSurvivalRateResults(observationId, monitoringPlotId)

        // Aggregation from substratum to stratum (and then to site) works by adding up the most
        // recent data for each substratum at the time of the observation in question.
        //
        // Edits to an observation can affect stratum and site data for later observations, but only
        // if the edited observation hasn't been superseded by a newer observation of the same
        // substratum.
        //
        // So we want to update later observations that don't have newer results for the substratum
        // we're editing, that is, observations for which this observation's substratum-level totals
        // would be used to calculate the stratum- and site-level totals.

        val latestObservations = OBSERVATIONS.`as`("latest_observations")
        val observationWithLatestResultsForSubstratum =
            DSL.field(
                DSL.select(latestObservations.ID)
                    .from(latestObservations)
                    .join(OBSERVATION_REQUESTED_SUBSTRATA)
                    .on(latestObservations.ID.eq(OBSERVATION_REQUESTED_SUBSTRATA.OBSERVATION_ID))
                    .where(latestObservations.COMPLETED_TIME.ge(observation.completedTime))
                    .and(latestObservations.COMPLETED_TIME.le(OBSERVATIONS.COMPLETED_TIME))
                    .and(OBSERVATION_REQUESTED_SUBSTRATA.SUBSTRATUM_ID.eq(substratumId))
                    .orderBy(latestObservations.COMPLETED_TIME.desc(), latestObservations.ID.desc())
                    .limit(1)
            )
        val stratumHistoryIdAtTimeOfObservation =
            DSL.field(
                DSL.select(STRATUM_HISTORIES.ID)
                    .from(STRATUM_HISTORIES)
                    .join(SUBSTRATUM_HISTORIES)
                    .on(STRATUM_HISTORIES.ID.eq(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID))
                    .where(
                        STRATUM_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                            OBSERVATIONS.PLANTING_SITE_HISTORY_ID
                        )
                    )
                    .and(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID.eq(substratumId))
            )
        val stratumIdAtTimeOfObservation =
            DSL.field(
                DSL.select(STRATUM_HISTORIES.STRATUM_ID)
                    .from(STRATUM_HISTORIES)
                    .join(SUBSTRATUM_HISTORIES)
                    .on(STRATUM_HISTORIES.ID.eq(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID))
                    .where(
                        STRATUM_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                            OBSERVATIONS.PLANTING_SITE_HISTORY_ID
                        )
                    )
                    .and(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID.eq(substratumId))
            )

        val laterObservationsDependentOnSubstratumDataFromThisObservation =
            dslContext
                .select(
                    OBSERVATIONS.ID.asNonNullable(),
                    OBSERVATIONS.PLANTING_SITE_HISTORY_ID.asNonNullable(),
                    stratumHistoryIdAtTimeOfObservation.asNonNullable(),
                    stratumIdAtTimeOfObservation,
                )
                .from(OBSERVATIONS)
                .where(OBSERVATIONS.PLANTING_SITE_ID.eq(observation.plantingSiteId))
                .and(OBSERVATIONS.COMPLETED_TIME.gt(observation.completedTime))
                .and(observationWithLatestResultsForSubstratum.eq(observationId))
                .and(OBSERVATIONS.OBSERVATION_TYPE_ID.eq(ObservationType.Monitoring))
                .fetch()

        laterObservationsDependentOnSubstratumDataFromThisObservation.forEach {
            (
                laterObservationId,
                laterPlantingSiteHistoryId,
                stratumHistoryIdInLaterObservation,
                stratumIdInLaterObservation,
            ) ->
          updateSpeciesTotals(
              observationId = laterObservationId,
              plantingSite = plantingSitesRow,
              plantingSiteHistoryId = laterPlantingSiteHistoryId,
              stratumId = stratumIdInLaterObservation,
              stratumHistoryId = stratumHistoryIdInLaterObservation,
              substratumId = null,
              substratumHistoryId = null,
              monitoringPlotId = null,
              monitoringPlotHistoryId = null,
              isAdHoc = observation.isAdHoc,
              isPermanent = isPermanent,
              plantCountsBySpecies = plantCountAdjustments,
          )
          updateObservationResults(laterObservationId, observation.plantingSiteId)
          recalculateSurvivalRateResults(laterObservationId, observation.plantingSiteId)
        }

        eventPublisher.publishEvent(
            MonitoringSpeciesTotalsEditedEvent(
                certainty = certainty,
                changedFrom = existing.toEventValues(updated),
                changedTo = updated.toEventValues(existing),
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
                organizationId = plantingSitesRow.organizationId!!,
                plantingSiteId = observation.plantingSiteId,
                speciesId = speciesId,
                speciesName = speciesName,
            )
        )
      }
    }
  }

  fun updateObservedPlotCoordinates(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      coordinates: List<NewObservedPlotCoordinatesModel>,
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
      val coordinatesToInsert = coordinates.filter { desired ->
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

    if (
        observation.state == ObservationState.Completed ||
            observation.state == ObservationState.Abandoned
    ) {
      throw ObservationAlreadyEndedException(observationId)
    }

    val hasCompletedPlots =
        dslContext.fetchExists(
            OBSERVATION_PLOTS,
            OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId),
            OBSERVATION_PLOTS.STATUS_ID.eq(ObservationPlotStatus.Completed),
        )

    if (hasCompletedPlots) {
      dslContext.transaction { _ ->
        log.info("Marking observation $observationId as abandoned")
        abandonPlots(observationId)
        updateObservationState(observationId, ObservationState.Abandoned)
        resetPlantPopulationSinceLastObservation(observation.plantingSiteId)
        recalculateSurvivalRateResults(observationId, observation.plantingSiteId)
      }
    } else {
      log.info("Deleting abandoned observation $observationId since it has no completed plots")
      deleteObservation(observationId)
    }
  }

  private fun completeObservation(
      observationId: ObservationId,
      plantingSiteId: PlantingSiteId,
      isAdHoc: Boolean = false,
  ) {
    updateObservationState(observationId, ObservationState.Completed)
    if (!isAdHoc) {
      // Ad-hoc observations do not reset unobserved populations
      resetPlantPopulationSinceLastObservation(plantingSiteId)
      recalculateSurvivalRates(observationId, plantingSiteId)
      recalculateSurvivalRateResults(observationId, plantingSiteId)
    }

    eventPublisher.publishEvent(ObservationCompletedEvent(observationId))
  }

  /**
   * Populates the `observation_*_results` tables for an observation whose plot completions predate
   * those tables existing, treating `observed_*_species_totals` as the source of truth.
   */
  fun populateObservationResults(observationId: ObservationId) {
    requirePermissions { manageObservation(observationId) }

    val observation = fetchObservationById(observationId)
    val plantingSiteId = observation.plantingSiteId
    val isAdHoc = observation.isAdHoc

    val plantingSite =
        dslContext
            .select()
            .from(PLANTING_SITES)
            .where(PLANTING_SITES.ID.eq(plantingSiteId))
            .fetchOneInto(PlantingSitesRow::class.java)
            ?: throw PlantingSiteNotFoundException(plantingSiteId)

    val completedPlots =
        dslContext
            .select(
                OBSERVATION_PLOTS.MONITORING_PLOT_ID.asNonNullable(),
                OBSERVATION_PLOTS.monitoringPlotHistories.SUBSTRATUM_ID,
                OBSERVATION_PLOTS.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID,
                OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.STRATUM_HISTORY_ID,
                OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.stratumHistories
                    .STRATUM_ID,
            )
            .from(OBSERVATION_PLOTS)
            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
            .and(OBSERVATION_PLOTS.STATUS_ID.eq(ObservationPlotStatus.Completed))
            .fetch()

    dslContext.transaction { _ ->
      completedPlots.forEach { record ->
        updateObservationResults(
            observationId,
            plantingSite,
            record[
                OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.stratumHistories
                    .STRATUM_ID],
            record[
                OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.STRATUM_HISTORY_ID],
            record[OBSERVATION_PLOTS.monitoringPlotHistories.SUBSTRATUM_ID],
            record[OBSERVATION_PLOTS.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID],
            record[OBSERVATION_PLOTS.MONITORING_PLOT_ID.asNonNullable()],
            isAdHoc,
        )
      }

      if (!isAdHoc) {
        recalculateSurvivalRateResults(observationId, plantingSiteId)
      }
    }
  }

  fun fetchSurvivalRateCalculationInProgress(plantingSiteId: PlantingSiteId): Boolean {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return with(PLANTING_SITE_SURVIVAL_RATE_CALCULATIONS) {
      dslContext.fetchExists(DSL.selectOne().from(this).where(PLANTING_SITE_ID.eq(plantingSiteId)))
    }
  }

  /** Recalculates the stratum- and site-level survival rates for an observation. */
  fun recalculateSurvivalRates(
      observationId: ObservationId,
      plantingSiteId: PlantingSiteId,
  ) {
    data class SubstratumSpeciesRecord(
        val certaintyId: RecordedSpeciesCertainty,
        val speciesId: SpeciesId?,
        val speciesName: String?,
        val stratumHistoryId: StratumHistoryId,
        val permanentLive: Int,
        val totalLive: Int,
        val survivalRateIncludesTempPlots: Boolean,
    )

    val plantingSiteHistoryId =
        dslContext
            .select(OBSERVATIONS.PLANTING_SITE_HISTORY_ID.asNonNullable())
            .from(OBSERVATIONS)
            .where(OBSERVATIONS.ID.eq(observationId))
            .fetchOne(OBSERVATIONS.PLANTING_SITE_HISTORY_ID.asNonNullable())!!

    // For each substratum in this observation's geometry snapshot, find the corresponding
    // stratum_history. This lets us aggregate observed_substratum_species_totals (which may have
    // been written under an older substratum_history) under the strata in the current snapshot.
    val obsSsh = SUBSTRATUM_HISTORIES.`as`("obs_ssh")
    val obsSh = STRATUM_HISTORIES.`as`("obs_sh")

    val liveAndDeadTotals:
        Map<RecordedSpeciesKey, Map<StratumHistoryId, List<SubstratumSpeciesRecord>>> =
        with(OBSERVED_SUBSTRATUM_SPECIES_TOTALS) {
          dslContext
              .select(
                  CERTAINTY_ID.asNonNullable(),
                  SPECIES_ID,
                  SPECIES_NAME,
                  obsSh.ID.asNonNullable(),
                  PERMANENT_LIVE.asNonNullable(),
                  TOTAL_LIVE.asNonNullable(),
                  PLANTING_SITE_HISTORIES.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
                      .asNonNullable(),
              )
              .from(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
              .join(SUBSTRATUM_HISTORIES)
              .on(SUBSTRATUM_HISTORY_ID.eq(SUBSTRATUM_HISTORIES.ID))
              .join(obsSsh)
              .on(obsSsh.SUBSTRATUM_ID.eq(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID))
              .join(obsSh)
              .on(obsSsh.STRATUM_HISTORY_ID.eq(obsSh.ID))
              .join(PLANTING_SITE_HISTORIES)
              .on(obsSh.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
              .where(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID.eq(plantingSiteId))
              .and(PLANTING_SITE_HISTORIES.ID.eq(plantingSiteHistoryId))
              .and(
                  OBSERVATION_ID.eq(
                      latestObservationForSubstratumField(
                          DSL.inline(observationId, OBSERVATIONS.ID.dataType),
                          OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SUBSTRATUM_ID,
                      )
                  )
              )
              .fetch { record ->
                SubstratumSpeciesRecord(
                    certaintyId = record[CERTAINTY_ID.asNonNullable()],
                    speciesId = record[SPECIES_ID],
                    speciesName = record[SPECIES_NAME],
                    stratumHistoryId = record[obsSh.ID.asNonNullable()],
                    permanentLive = record[PERMANENT_LIVE.asNonNullable()],
                    totalLive = record[TOTAL_LIVE.asNonNullable()],
                    survivalRateIncludesTempPlots =
                        record[
                            PLANTING_SITE_HISTORIES.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
                                .asNonNullable()],
                )
              }
              .groupBy { record ->
                RecordedSpeciesKey(record.certaintyId, record.speciesId, record.speciesName)
              }
              .mapValues { (_, recordsForSpecies) ->
                recordsForSpecies.groupBy { it.stratumHistoryId }
              }
        }

    val stratumIdByHistoryId =
        with(STRATUM_HISTORIES) {
          dslContext
              .select(ID.asNonNullable(), STRATUM_ID)
              .from(this)
              .where(PLANTING_SITE_HISTORY_ID.eq(plantingSiteHistoryId))
              .fetch { record -> record[ID.asNonNullable()] to record[STRATUM_ID] }
              .toMap()
        }

    liveAndDeadTotals.forEach { (speciesKey, stratumToLiveAndDead) ->
      stratumToLiveAndDead.forEach { (stratumHistoryId, liveAndDeadForStratum) ->
        val totalPermanentLive = liveAndDeadForStratum.sumOf { it.permanentLive }
        val totalLive = liveAndDeadForStratum.sumOf { it.totalLive }
        val stratumId = stratumIdByHistoryId[stratumHistoryId]

        with(OBSERVED_STRATUM_SPECIES_TOTALS) {
          val updateScope = ObservationSpeciesStratum(stratumHistoryId, stratumId)
          val survivalRatePermanentDenominator =
              getSurvivalRateDenominator(
                  updateScope,
                  PLOT_T0_DENSITIES.SPECIES_ID.eq(speciesKey.id),
                  DSL.value(observationId, OBSERVATIONS.ID.dataType),
              )
          val survivalRateTempDenominator =
              getSurvivalRateTempDenominator(
                  updateScope,
                  STRATUM_T0_TEMP_DENSITIES.SPECIES_ID.eq(speciesKey.id),
                  DSL.value(observationId, OBSERVATIONS.ID.dataType),
              )
          val survivalRateDenominator =
              DSL.coalesce(
                  survivalRatePermanentDenominator.plus(survivalRateTempDenominator),
                  survivalRatePermanentDenominator,
                  survivalRateTempDenominator,
              )
          val survivalRate =
              if (liveAndDeadForStratum.first().survivalRateIncludesTempPlots) {
                getSurvivalRate(DSL.value(totalLive), survivalRateDenominator)
              } else {
                getSurvivalRate(DSL.value(totalPermanentLive), survivalRateDenominator)
              }

          val rowsInserted =
              dslContext
                  .insertInto(OBSERVED_STRATUM_SPECIES_TOTALS)
                  .set(CERTAINTY_ID, speciesKey.certainty)
                  .set(OBSERVATION_ID, observationId)
                  .set(PERMANENT_LIVE, totalPermanentLive)
                  .set(STRATUM_ID, stratumId)
                  .set(STRATUM_HISTORY_ID, stratumHistoryId)
                  .set(SPECIES_ID, speciesKey.id)
                  .set(SPECIES_NAME, speciesKey.name)
                  .set(SURVIVAL_RATE, survivalRate)
                  .onConflictDoNothing()
                  .execute()
          if (rowsInserted == 0) {
            dslContext
                .update(OBSERVED_STRATUM_SPECIES_TOTALS)
                .set(PERMANENT_LIVE, totalPermanentLive)
                .set(SURVIVAL_RATE, survivalRate)
                .where(OBSERVATION_ID.eq(observationId))
                .and(STRATUM_HISTORY_ID.eq(stratumHistoryId))
                .and(CERTAINTY_ID.eq(speciesKey.certainty))
                .and(SPECIES_ID.eqOrIsNull(speciesKey.id))
                .and(SPECIES_NAME.eqOrIsNull(speciesKey.name))
                .execute()
          }
        }
      }

      val totalPermanentLive = stratumToLiveAndDead.flatMap { it.value }.sumOf { it.permanentLive }
      val totalLive = stratumToLiveAndDead.flatMap { it.value }.sumOf { it.totalLive }

      with(OBSERVED_SITE_SPECIES_TOTALS) {
        val updateScope = ObservationSpeciesSite(plantingSiteId, plantingSiteHistoryId)
        val survivalRatePermanentDenominator =
            getSurvivalRateDenominator(
                updateScope,
                PLOT_T0_DENSITIES.SPECIES_ID.eq(speciesKey.id),
                DSL.value(observationId, OBSERVATIONS.ID.dataType),
            )
        val survivalRateTempDenominator =
            getSurvivalRateTempDenominator(
                updateScope,
                STRATUM_T0_TEMP_DENSITIES.SPECIES_ID.eq(speciesKey.id),
                DSL.value(observationId, OBSERVATIONS.ID.dataType),
            )
        val survivalRateDenominator =
            DSL.coalesce(
                survivalRatePermanentDenominator.plus(survivalRateTempDenominator),
                survivalRatePermanentDenominator,
                survivalRateTempDenominator,
            )
        val survivalRate =
            if (stratumToLiveAndDead.flatMap { it.value }.first().survivalRateIncludesTempPlots) {
              getSurvivalRate(DSL.value(totalLive), survivalRateDenominator)
            } else {
              getSurvivalRate(DSL.value(totalPermanentLive), survivalRateDenominator)
            }

        val rowsInserted =
            dslContext
                .insertInto(OBSERVED_SITE_SPECIES_TOTALS)
                .set(CERTAINTY_ID, speciesKey.certainty)
                .set(OBSERVATION_ID, observationId)
                .set(PERMANENT_LIVE, totalPermanentLive)
                .set(PLANTING_SITE_ID, plantingSiteId)
                .set(PLANTING_SITE_HISTORY_ID, plantingSiteHistoryId)
                .set(SPECIES_ID, speciesKey.id)
                .set(SPECIES_NAME, speciesKey.name)
                .set(SURVIVAL_RATE, survivalRate)
                .onConflictDoNothing()
                .execute()
        if (rowsInserted == 0) {
          dslContext
              .update(OBSERVED_SITE_SPECIES_TOTALS)
              .set(PERMANENT_LIVE, totalPermanentLive)
              .set(SURVIVAL_RATE, survivalRate)
              .where(OBSERVATION_ID.eq(observationId))
              .and(PLANTING_SITE_HISTORY_ID.eq(plantingSiteHistoryId))
              .and(CERTAINTY_ID.eq(speciesKey.certainty))
              .and(SPECIES_ID.eqOrIsNull(speciesKey.id))
              .and(SPECIES_NAME.eqOrIsNull(speciesKey.name))
              .execute()
        }
      }
    }
  }

  fun recalculateSurvivalRates(monitoringPlotId: MonitoringPlotId) {
    recalculateSurvivalRate(ObservationSpeciesPlot(monitoringPlotId))
    recalculateSurvivalRate(ObservationSpeciesSubstratum(monitoringPlotId))
    recalculateSurvivalRate(ObservationSpeciesStratum(monitoringPlotId))
    recalculateSurvivalRate(ObservationSpeciesSite(monitoringPlotId))

    recalculateSurvivalRateResults(ObservationResultsPlot(monitoringPlotId))
    recalculateSurvivalRateResults(ObservationResultsSubstratum(monitoringPlotId))
    recalculateSurvivalRateResults(ObservationResultsStratum(monitoringPlotId))
    recalculateSurvivalRateResults(ObservationResultsSite(monitoringPlotId))
  }

  fun recalculateSurvivalRates(stratumId: StratumId) {
    val substratumGroups: Map<SubstratumHistoryId, List<MonitoringPlotId>> =
        with(SUBSTRATUM_HISTORIES) {
          dslContext
              .selectDistinct(
                  ID.asNonNullable(),
                  MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID,
              )
              .from(MONITORING_PLOT_HISTORIES)
              .join(SUBSTRATUM_HISTORIES)
              .on(ID.eq(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID))
              .where(stratumHistories.STRATUM_ID.eq(stratumId))
              .fetchGroups(
                  ID.asNonNullable(),
                  MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.asNonNullable(),
              )
        }

    substratumGroups.values.flatten().forEach {
      recalculateSurvivalRate(ObservationSpeciesPlot(it))
    }
    substratumGroups.keys.forEach { recalculateSurvivalRate(ObservationSpeciesSubstratum(it)) }
    recalculateSurvivalRate(ObservationSpeciesStratum(stratumId))
    recalculateSurvivalRate(ObservationSpeciesSite(stratumId))

    substratumGroups.values.flatten().forEach {
      recalculateSurvivalRateResults(ObservationResultsPlot(it))
    }
    substratumGroups.keys.forEach {
      recalculateSurvivalRateResults(ObservationResultsSubstratum(it))
    }
    recalculateSurvivalRateResults(ObservationResultsStratum(stratumId))
    recalculateSurvivalRateResults(ObservationResultsSite(stratumId))
  }

  fun recalculateSurvivalRates(plantingSiteId: PlantingSiteId) {
    val substratumGroups: Map<SubstratumHistoryId, List<MonitoringPlotId>> =
        with(SUBSTRATUM_HISTORIES) {
          dslContext
              .selectDistinct(
                  ID.asNonNullable(),
                  MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID,
              )
              .from(MONITORING_PLOT_HISTORIES)
              .join(SUBSTRATUM_HISTORIES)
              .on(ID.eq(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID))
              .where(stratumHistories.strata.PLANTING_SITE_ID.eq(plantingSiteId))
              .fetchGroups(
                  ID.asNonNullable(),
                  MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.asNonNullable(),
              )
        }

    val stratumIds =
        with(STRATA) {
          dslContext
              .select(ID.asNonNullable())
              .from(this)
              .where(PLANTING_SITE_ID.eq(plantingSiteId))
              .fetch(ID.asNonNullable())
        }

    substratumGroups.values.flatten().forEach {
      recalculateSurvivalRate(ObservationSpeciesPlot(it))
    }
    substratumGroups.keys.forEach { recalculateSurvivalRate(ObservationSpeciesSubstratum(it)) }
    stratumIds.forEach { recalculateSurvivalRate(ObservationSpeciesStratum(it)) }
    recalculateSurvivalRate(ObservationSpeciesSite(plantingSiteId))

    substratumGroups.values.flatten().forEach {
      recalculateSurvivalRateResults(ObservationResultsPlot(it))
    }
    substratumGroups.keys.forEach {
      recalculateSurvivalRateResults(ObservationResultsSubstratum(it))
    }
    stratumIds.forEach { recalculateSurvivalRateResults(ObservationResultsStratum(it)) }
    recalculateSurvivalRateResults(ObservationResultsSite(plantingSiteId))
  }

  /**
   * Recalculates survival rates for all planting sites. Returns a map of error messages by planting
   * site ID for any sites that failed.
   */
  fun recalculateAllSurvivalRates(): Map<PlantingSiteId, String?> {
    val plantingSiteIds =
        with(PLANTING_SITES) {
          dslContext.select(ID).from(PLANTING_SITES).orderBy(ID).fetch(ID.asNonNullable())
        }

    val failures = mutableMapOf<PlantingSiteId, String?>()

    plantingSiteIds.forEach { id ->
      try {
        recalculateSurvivalRates(id)
      } catch (e: Exception) {
        log.warn("Failed to recalculate survival rates for planting site $id", e)
        failures[id] = e.message
      }
    }

    return failures
  }

  private fun <ID : Any, HistoryId : Any> recalculateSurvivalRate(
      updateScope: ObservationSpeciesScope<ID, HistoryId>
  ) {
    val table = updateScope.observedTotalsTable
    val speciesIdField = table.field("species_id", SPECIES.ID.dataType)
    val observationIdField = table.field("observation_id", OBSERVATIONS.ID.dataType)!!
    val survivalRatePermanentDenominator =
        getSurvivalRateDenominator(
            updateScope,
            PLOT_T0_DENSITIES.SPECIES_ID.eq(speciesIdField),
            observationIdField,
        )
    val survivalRateTempDenominator =
        getSurvivalRateTempDenominator(
            updateScope,
            STRATUM_T0_TEMP_DENSITIES.SPECIES_ID.eq(speciesIdField),
            observationIdField,
        )
    val survivalRateDenominator =
        DSL.coalesce(survivalRatePermanentDenominator, BigDecimal.ZERO)
            .plus(DSL.coalesce(survivalRateTempDenominator, BigDecimal.ZERO))
    val survivalRateField = table.field("survival_rate", Int::class.java)!!
    val permanentLiveField = table.field("permanent_live", Int::class.java)!!
    val totalLiveField = table.field("total_live", Int::class.java)!!

    dslContext
        .update(table)
        .set(
            survivalRateField,
            DSL.case_()
                .`when`(updateScope.observedTotalsPlantingSiteTempCondition, totalLiveField)
                .else_(permanentLiveField)
                .mul(BigDecimal.valueOf(100))
                .div(DSL.nullif(survivalRateDenominator, BigDecimal.ZERO)),
        )
        .where(updateScope.observedTotalsCondition)
        .execute()
  }

  private fun recalculateSurvivalRateResults(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
  ) {
    val (
        monitoringPlotHistoryId,
        substratumHistoryId,
        substratumId,
        stratumHistoryId,
        stratumId,
        plantingSiteHistoryId,
        plantingSiteId,
    ) = dslContext
        .select(
            OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID.asNonNullable(),
            OBSERVATION_PLOTS.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID,
            OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.SUBSTRATUM_ID,
            OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.STRATUM_HISTORY_ID,
            OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.stratumHistories
                .STRATUM_ID,
            OBSERVATION_PLOTS.observations.PLANTING_SITE_HISTORY_ID.asNonNullable(),
            OBSERVATION_PLOTS.observations.PLANTING_SITE_ID.asNonNullable(),
        )
        .from(OBSERVATION_PLOTS)
        .where(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
        .and(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
        .fetchOne() ?: throw PlotNotInObservationException(observationId, monitoringPlotId)

    recalculateSurvivalRateResults(
        ObservationResultsPlot(monitoringPlotHistoryId, monitoringPlotId),
        observationId,
    )
    if (substratumHistoryId != null) {
      recalculateSurvivalRateResults(
          ObservationResultsSubstratum(substratumHistoryId, substratumId),
          observationId,
      )
    }
    if (stratumHistoryId != null) {
      recalculateSurvivalRateResults(
          ObservationResultsStratum(stratumHistoryId, stratumId),
          observationId,
      )
    }
    recalculateSurvivalRateResults(
        ObservationResultsSite(plantingSiteHistoryId, plantingSiteId),
        observationId,
    )
  }

  private fun <ID : Any, HistoryId : Any> recalculateSurvivalRateResults(
      updateScope: ObservationResultsScope<ID, HistoryId>,
  ) {
    val table = updateScope.observedTotalsTable
    val observationIdField = table.field("observation_id", OBSERVATIONS.ID.dataType)!!
    val survivalRatePermanentDenominator =
        getSurvivalRateDenominator(
            updateScope,
            DSL.trueCondition(),
            observationIdField,
        )
    val survivalRateTempDenominator =
        getSurvivalRateTempDenominator(
            updateScope,
            DSL.trueCondition(),
            observationIdField,
        )
    val survivalRateDenominator =
        DSL.coalesce(survivalRatePermanentDenominator, BigDecimal.ZERO)
            .plus(DSL.coalesce(survivalRateTempDenominator, BigDecimal.ZERO))
    val survivalRateField = table.field("survival_rate", Int::class.java)!!
    val survivalRateStdDevField = table.field("survival_rate_std_dev", Int::class.java)
    val survivalRateAreaField = table.field("survival_rate_area", BigDecimal::class.java)
    val permanentLiveField = table.field("permanent_live", Int::class.java)!!
    val latestLiveField = updateScope.latestLiveField

    val survivalRateValue =
        updateScope.survivalRateValue(
            observationIdField,
            survivalRateDenominator,
            latestLiveField,
            permanentLiveField,
        )

    val recalculationCondition =
        DSL.and(
            updateScope.survivalRateRecalculationCondition,
            DSL.notExists(
                DSL.selectOne()
                    .from(OBSERVATION_PLOTS)
                    .where(updateScope.observationPlotsCondition(observationIdField))
                    .and(OBSERVATION_PLOTS.COMPLETED_TIME.isNull)
            ),
        )

    dslContext
        .update(table)
        .set(survivalRateField, survivalRateValue)
        .where(recalculationCondition)
        .execute()

    // Standard deviation and area are stored only for scopes that aggregate plots (substratum,
    // stratum, site). They depend on the survival rate just computed above, so they're set in a
    // separate statement that reads the stored value instead of recomputing the (large) survival
    // rate expression for each column.
    if (survivalRateStdDevField != null && survivalRateAreaField != null) {
      dslContext
          .update(table)
          .set(
              survivalRateStdDevField,
              DSL.if_(
                  survivalRateField.isNotNull,
                  getSurvivalRateWeightedStandardDeviation(updateScope),
                  DSL.castNull(SQLDataType.INTEGER),
              ),
          )
          .set(
              survivalRateAreaField,
              DSL.if_(
                  survivalRateField.isNotNull,
                  updateScope.survivalRateAreaValue(observationIdField),
                  DSL.castNull(SQLDataType.NUMERIC),
              ),
          )
          .where(recalculationCondition)
          .execute()
    }
  }

  private fun recalculateSurvivalRateResults(
      observationId: ObservationId,
      plantingSiteId: PlantingSiteId,
  ) {
    // Update tables with latest total values
    updateObservationResults(observationId, plantingSiteId)

    val plotIds =
        dslContext
            .select(OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_ID.asNonNullable())
            .from(OBSERVATION_PLOT_RESULTS)
            .where(OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(observationId))
            .fetch { it.value1() }

    val substratumHistoryIds =
        dslContext
            .selectDistinct(OBSERVATION_PLOT_RESULTS.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID)
            .from(OBSERVATION_PLOT_RESULTS)
            .where(OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(observationId))
            .fetch { it.value1() }
            .filterNotNull()

    val stratumHistoryIds =
        dslContext
            .selectDistinct(
                OBSERVATION_PLOT_RESULTS.monitoringPlotHistories.substratumHistories
                    .STRATUM_HISTORY_ID
            )
            .from(OBSERVATION_PLOT_RESULTS)
            .where(OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(observationId))
            .fetch { it.value1() }
            .filterNotNull()

    plotIds.forEach { recalculateSurvivalRateResults(ObservationResultsPlot(it), observationId) }
    substratumHistoryIds.forEach {
      recalculateSurvivalRateResults(ObservationResultsSubstratum(it), observationId)
    }
    stratumHistoryIds.forEach {
      recalculateSurvivalRateResults(ObservationResultsStratum(it), observationId)
    }
    recalculateSurvivalRateResults(ObservationResultsSite(plantingSiteId), observationId)
  }

  private fun <ID : Any, HistoryId : Any> recalculateSurvivalRateResults(
      updateScope: ObservationResultsScope<ID, HistoryId>,
      observationId: ObservationId,
  ) {
    val table = updateScope.observedTotalsTable
    val observationIdField = table.field("observation_id", OBSERVATIONS.ID.dataType)!!
    val survivalRatePermanentDenominator =
        getSurvivalRateDenominator(
            updateScope,
            DSL.trueCondition(),
            DSL.value(observationId, OBSERVATIONS.ID.dataType),
        )
    val survivalRateTempDenominator =
        getSurvivalRateTempDenominator(
            updateScope,
            DSL.trueCondition(),
            DSL.value(observationId, OBSERVATIONS.ID.dataType),
        )
    val survivalRateDenominator =
        DSL.coalesce(survivalRatePermanentDenominator, BigDecimal.ZERO)
            .plus(DSL.coalesce(survivalRateTempDenominator, BigDecimal.ZERO))
    val survivalRateField = table.field("survival_rate", Int::class.java)!!
    val survivalRateStdDevField = table.field("survival_rate_std_dev", Int::class.java)
    val survivalRateAreaField = table.field("survival_rate_area", BigDecimal::class.java)
    val permanentLiveField = table.field("permanent_live", Int::class.java)!!
    val latestLiveField = updateScope.latestLiveField

    val survivalRateValue =
        updateScope.survivalRateValue(
            DSL.value(observationId, OBSERVATIONS.ID.dataType),
            survivalRateDenominator,
            latestLiveField,
            permanentLiveField,
        )

    val allPlotsCompleted =
        dslContext
            .fetchExists(
                DSL.selectOne()
                    .from(OBSERVATION_PLOTS)
                    .where(
                        updateScope.observationPlotsCondition(
                            DSL.value(observationId, OBSERVATIONS.ID.dataType)
                        )
                    )
                    .and(OBSERVATION_PLOTS.COMPLETED_TIME.isNull)
            )
            .not()

    if (allPlotsCompleted) {
      val recalculationCondition =
          DSL.and(updateScope.observedTotalsCondition, observationIdField.eq(observationId))

      dslContext
          .update(table)
          .set(survivalRateField, survivalRateValue)
          .where(recalculationCondition)
          .execute()

      // See the comment in the no-argument overload: std dev and area depend on the survival rate
      // computed above and are set in a separate statement that reads the stored value rather than
      // recomputing the survival rate expression once per column.
      if (survivalRateStdDevField != null && survivalRateAreaField != null) {
        dslContext
            .update(table)
            .set(
                survivalRateStdDevField,
                DSL.if_(
                    survivalRateField.isNotNull,
                    getSurvivalRateWeightedStandardDeviation(updateScope),
                    DSL.castNull(SQLDataType.INTEGER),
                ),
            )
            .set(
                survivalRateAreaField,
                DSL.if_(
                    survivalRateField.isNotNull,
                    updateScope.survivalRateAreaValue(
                        DSL.value(observationId, OBSERVATIONS.ID.dataType)
                    ),
                    DSL.castNull(SQLDataType.NUMERIC),
                ),
            )
            .where(recalculationCondition)
            .execute()
      }
    }
  }

  @EventListener
  fun on(event: T0PlotDataAssignedEvent) {
    val plantingSiteId = parentStore.getPlantingSiteId(event.monitoringPlotId) ?: return
    enqueueSurvivalRateCalculation(plantingSiteId) {
      jobScheduler.enqueue<ObservationStore> {
        runRecalculateSurvivalRates(plantingSiteId, event.monitoringPlotId)
      }
    }
  }

  @EventListener
  fun on(event: T0StratumDataAssignedEvent) {
    val plantingSiteId = parentStore.getPlantingSiteId(event.stratumId) ?: return
    enqueueSurvivalRateCalculation(plantingSiteId) {
      jobScheduler.enqueue<ObservationStore> {
        runRecalculateSurvivalRates(plantingSiteId, event.stratumId)
      }
    }
  }

  fun runRecalculateSurvivalRates(
      plantingSiteId: PlantingSiteId,
      monitoringPlotId: MonitoringPlotId,
  ) {
    systemUser.run {
      try {
        recalculateSurvivalRates(monitoringPlotId)
      } catch (e: Exception) {
        log.error("Survival rate recalculation failed for planting site $plantingSiteId", e)
      } finally {
        maybeRerunCalculateSurvivalRates(plantingSiteId)
      }
    }
  }

  fun runRecalculateSurvivalRates(plantingSiteId: PlantingSiteId, stratumId: StratumId) {
    systemUser.run {
      try {
        recalculateSurvivalRates(stratumId)
      } catch (e: Exception) {
        log.error("Survival rate recalculation failed for planting site $plantingSiteId", e)
      } finally {
        maybeRerunCalculateSurvivalRates(plantingSiteId)
      }
    }
  }

  fun runRecalculateSurvivalRates(plantingSiteId: PlantingSiteId) {
    systemUser.run {
      try {
        recalculateSurvivalRates(plantingSiteId)
      } catch (e: Exception) {
        log.error("Survival rate recalculation failed for planting site $plantingSiteId", e)
      } finally {
        maybeRerunCalculateSurvivalRates(plantingSiteId)
      }
    }
  }

  private fun maybeRerunCalculateSurvivalRates(plantingSiteId: PlantingSiteId) =
      withLockedSurvivalRateCalculation(plantingSiteId) {
        val additionalCalculationRequested =
            survivalRateAdditionalCalculationRequested(plantingSiteId)

        if (!additionalCalculationRequested) {
          deleteSurvivalRateCalculation(plantingSiteId)
          log.info("Planting Site $plantingSiteId survival rate calculation completed.")
        } else {
          setSurvivalRateAdditionalCalculationRequested(plantingSiteId, false)
          // Re-run recalculating survival rates asynchronously to unlock the survival rate
          // recalculation rows. Extend scope to the entire site to handle all updates at once
          val jobId =
              jobScheduler.enqueue<ObservationStore> { runRecalculateSurvivalRates(plantingSiteId) }
          log.info(
              "Planting Site $plantingSiteId additional survival rate calculation requested. Enqueuing new job $jobId."
          )
        }
      }

  /**
   * Immediately enqueue an async recalculation jobs, or set the existing job to rerun on
   * completion.
   */
  private fun enqueueSurvivalRateCalculation(
      plantingSiteId: PlantingSiteId,
      enqueueJob: () -> JobId,
  ) {
    withLockedSurvivalRateCalculation(plantingSiteId) {
      val calculationInProgress = survivalRateCalculationInProgress(plantingSiteId)

      if (!calculationInProgress) {
        insertSurvivalRateCalculation(plantingSiteId)
        // The recalculation job is often long-running and is run asynchronously to prevent
        // client call from blocking and timing-out
        val jobId = enqueueJob()
        log.info("Enqueuing planting Site $plantingSiteId survival rate calculation job $jobId.")
      } else {
        log.info(
            "Planting Site $plantingSiteId survival rate calculation in-progress. Additional calculation requested."
        )
        setSurvivalRateAdditionalCalculationRequested(plantingSiteId, true)
      }
    }
  }

  /**
   * Merges the observation data from a source observation to a target.
   *
   * You probably want `ObservationService.mergeObservations` instead of this.
   *
   * Moves the raw per-plot data of completed plots from the source observation to the target
   * observation, repoints/drops t0 baselines, and deletes the source observation. Does NOT delete
   * media (the caller handles colliding target media) and does NOT rebuild derived aggregates (call
   * [recalculateObservationTotals] and [recalculateSurvivalRates] afterward).
   */
  fun mergeObservationData(
      sourceObservationId: ObservationId,
      targetObservationId: ObservationId,
  ) {
    requirePermissions {
      manageObservation(sourceObservationId)
      manageObservation(targetObservationId)
    }

    // Lock both observations in ID order to avoid deadlocks.
    val idsToLock = listOf(sourceObservationId, targetObservationId).sorted()

    observationLocker.withLockedObservation(idsToLock[0]) {
      observationLocker.withLockedObservation(idsToLock[1]) {
        val completedInSource =
            dslContext
                .select(OBSERVATION_PLOTS.MONITORING_PLOT_ID)
                .from(OBSERVATION_PLOTS)
                .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(sourceObservationId))
                .and(OBSERVATION_PLOTS.STATUS_ID.eq(ObservationPlotStatus.Completed))
                .fetchSet(OBSERVATION_PLOTS.MONITORING_PLOT_ID.asNonNullable())

        if (completedInSource.isNotEmpty()) {
          val collidingPlots =
              dslContext
                  .select(OBSERVATION_PLOTS.MONITORING_PLOT_ID)
                  .from(OBSERVATION_PLOTS)
                  .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(targetObservationId))
                  .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.`in`(completedInSource))
                  .fetchSet(OBSERVATION_PLOTS.MONITORING_PLOT_ID.asNonNullable())

          if (collidingPlots.isNotEmpty()) {
            val t0DropPlots =
                dslContext
                    .select(PLOT_T0_OBSERVATIONS.MONITORING_PLOT_ID)
                    .from(PLOT_T0_OBSERVATIONS)
                    .where(PLOT_T0_OBSERVATIONS.MONITORING_PLOT_ID.`in`(collidingPlots))
                    .and(PLOT_T0_OBSERVATIONS.OBSERVATION_ID.eq(targetObservationId))
                    .fetchSet(PLOT_T0_OBSERVATIONS.MONITORING_PLOT_ID.asNonNullable())

            dslContext
                .deleteFrom(OBSERVATION_PLOTS)
                .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(targetObservationId))
                .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.`in`(collidingPlots))
                .execute()

            if (t0DropPlots.isNotEmpty()) {
              dslContext
                  .deleteFrom(PLOT_T0_DENSITIES)
                  .where(PLOT_T0_DENSITIES.MONITORING_PLOT_ID.`in`(t0DropPlots))
                  .execute()
            }
          }

          // Copy the source's completed observation_plots rows into the target so the composite FKs
          // of the child tables are satisfied when we reparent them.
          with(OBSERVATION_PLOTS) {
            dslContext
                .insertInto(
                    OBSERVATION_PLOTS,
                    OBSERVATION_ID,
                    MONITORING_PLOT_ID,
                    CLAIMED_BY,
                    CLAIMED_TIME,
                    COMPLETED_BY,
                    COMPLETED_TIME,
                    CREATED_BY,
                    CREATED_TIME,
                    IS_PERMANENT,
                    MODIFIED_BY,
                    MODIFIED_TIME,
                    OBSERVED_TIME,
                    NOTES,
                    STATUS_ID,
                    MONITORING_PLOT_HISTORY_ID,
                )
                .select(
                    DSL.select(
                            DSL.value(targetObservationId, OBSERVATION_ID.dataType),
                            MONITORING_PLOT_ID,
                            CLAIMED_BY,
                            CLAIMED_TIME,
                            COMPLETED_BY,
                            COMPLETED_TIME,
                            CREATED_BY,
                            CREATED_TIME,
                            IS_PERMANENT,
                            MODIFIED_BY,
                            MODIFIED_TIME,
                            OBSERVED_TIME,
                            NOTES,
                            STATUS_ID,
                            MONITORING_PLOT_HISTORY_ID,
                        )
                        .from(OBSERVATION_PLOTS)
                        .where(OBSERVATION_ID.eq(sourceObservationId))
                        .and(STATUS_ID.eq(ObservationPlotStatus.Completed))
                )
                .execute()
          }

          dslContext
              .update(RECORDED_PLANTS)
              .set(RECORDED_PLANTS.OBSERVATION_ID, targetObservationId)
              .where(RECORDED_PLANTS.OBSERVATION_ID.eq(sourceObservationId))
              .and(RECORDED_PLANTS.MONITORING_PLOT_ID.`in`(completedInSource))
              .execute()
          dslContext
              .update(OBSERVATION_PLOT_CONDITIONS)
              .set(OBSERVATION_PLOT_CONDITIONS.OBSERVATION_ID, targetObservationId)
              .where(OBSERVATION_PLOT_CONDITIONS.OBSERVATION_ID.eq(sourceObservationId))
              .and(OBSERVATION_PLOT_CONDITIONS.MONITORING_PLOT_ID.`in`(completedInSource))
              .execute()
          dslContext
              .update(OBSERVED_PLOT_COORDINATES)
              .set(OBSERVED_PLOT_COORDINATES.OBSERVATION_ID, targetObservationId)
              .where(OBSERVED_PLOT_COORDINATES.OBSERVATION_ID.eq(sourceObservationId))
              .and(OBSERVED_PLOT_COORDINATES.MONITORING_PLOT_ID.`in`(completedInSource))
              .execute()
          dslContext
              .update(OBSERVATION_MEDIA_FILES)
              .set(OBSERVATION_MEDIA_FILES.OBSERVATION_ID, targetObservationId)
              .where(OBSERVATION_MEDIA_FILES.OBSERVATION_ID.eq(sourceObservationId))
              .and(OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID.`in`(completedInSource))
              .execute()
          dslContext
              .update(PLOT_T0_OBSERVATIONS)
              .set(PLOT_T0_OBSERVATIONS.OBSERVATION_ID, targetObservationId)
              .where(PLOT_T0_OBSERVATIONS.OBSERVATION_ID.eq(sourceObservationId))
              .and(PLOT_T0_OBSERVATIONS.MONITORING_PLOT_ID.`in`(completedInSource))
              .execute()
        }

        dslContext.deleteFrom(OBSERVATIONS).where(OBSERVATIONS.ID.eq(sourceObservationId)).execute()
      }
    }
  }

  private fun fetchRecordedPlantCountsBySpecies(
      observationId: ObservationId,
      monitoringPlotIds: Collection<MonitoringPlotId>,
  ): Map<MonitoringPlotId, Map<RecordedSpeciesKey, Map<RecordedPlantStatus, Int>>> {
    val countField = DSL.count()
    return with(RECORDED_PLANTS) {
      dslContext
          .select(
              CERTAINTY_ID,
              MONITORING_PLOT_ID,
              SPECIES_ID,
              SPECIES_NAME,
              STATUS_ID,
              countField,
          )
          .from(RECORDED_PLANTS)
          .where(OBSERVATION_ID.eq(observationId))
          .and(MONITORING_PLOT_ID.`in`(monitoringPlotIds))
          .groupBy(MONITORING_PLOT_ID, CERTAINTY_ID, SPECIES_ID, SPECIES_NAME, STATUS_ID)
          .fetchGroups(MONITORING_PLOT_ID.asNonNullable())
          .map { (monitoringPlotId, resultForPlot) ->
            val resultsForStatus =
                resultForPlot
                    .groupBy { record ->
                      RecordedSpeciesKey(
                          record[CERTAINTY_ID]!!,
                          record[SPECIES_ID],
                          record[SPECIES_NAME],
                      )
                    }
                    .map { (speciesKey, result) ->
                      speciesKey to
                          result.associate { record -> record[STATUS_ID]!! to record[countField] }
                    }
                    .toMap()

            monitoringPlotId to resultsForStatus
          }
          .toMap()
    }
  }

  /**
   * Rebuilds the observed species totals and observation results for an observation based on the
   * observation's recorded plants. Does not recalculate survival rates; call
   * [recalculateSurvivalRates] for that.
   */
  fun recalculateObservationTotals(observationId: ObservationId) {
    requirePermissions { manageObservation(observationId) }

    val observation = fetchObservationById(observationId)
    val plantingSiteId = observation.plantingSiteId
    val plantingSiteHistoryId =
        observation.plantingSiteHistoryId
            ?: throw IllegalStateException(
                "Observation $observationId has no planting site history"
            )
    val plantingSite =
        dslContext
            .select()
            .from(PLANTING_SITES)
            .where(PLANTING_SITES.ID.eq(plantingSiteId))
            .fetchOneInto(PlantingSitesRow::class.java)
            ?: throw PlantingSiteNotFoundException(plantingSiteId)

    observationLocker.withLockedObservation(observationId) {
      dslContext
          .deleteFrom(OBSERVED_PLOT_SPECIES_TOTALS)
          .where(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
          .execute()
      dslContext
          .deleteFrom(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
          .where(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
          .execute()
      dslContext
          .deleteFrom(OBSERVED_STRATUM_SPECIES_TOTALS)
          .where(OBSERVED_STRATUM_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
          .execute()
      dslContext
          .deleteFrom(OBSERVED_SITE_SPECIES_TOTALS)
          .where(OBSERVED_SITE_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
          .execute()
      dslContext
          .deleteFrom(OBSERVATION_PLOT_RESULTS)
          .where(OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(observationId))
          .execute()
      dslContext
          .deleteFrom(OBSERVATION_SUBSTRATUM_RESULTS)
          .where(OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID.eq(observationId))
          .execute()
      dslContext
          .deleteFrom(OBSERVATION_STRATUM_RESULTS)
          .where(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID.eq(observationId))
          .execute()
      dslContext
          .deleteFrom(OBSERVATION_SITE_RESULTS)
          .where(OBSERVATION_SITE_RESULTS.OBSERVATION_ID.eq(observationId))
          .execute()

      val completedPlots =
          with(OBSERVATION_PLOTS) {
            dslContext
                .select(
                    MONITORING_PLOT_ID,
                    MONITORING_PLOT_HISTORY_ID,
                    IS_PERMANENT,
                    monitoringPlotHistories.SUBSTRATUM_ID,
                    monitoringPlotHistories.SUBSTRATUM_HISTORY_ID,
                    monitoringPlotHistories.substratumHistories.STRATUM_HISTORY_ID,
                    monitoringPlotHistories.substratumHistories.stratumHistories.STRATUM_ID,
                )
                .from(OBSERVATION_PLOTS)
                .where(OBSERVATION_ID.eq(observationId))
                .and(STATUS_ID.eq(ObservationPlotStatus.Completed))
                .fetch()
          }

      val allPlantCounts =
          fetchRecordedPlantCountsBySpecies(
              observationId,
              completedPlots.map { it[OBSERVATION_PLOTS.MONITORING_PLOT_ID]!! },
          )

      completedPlots.forEach { record ->
        val monitoringPlotId = record[OBSERVATION_PLOTS.MONITORING_PLOT_ID]!!
        val monitoringPlotHistoryId = record[OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID]!!
        val isPermanent = record[OBSERVATION_PLOTS.IS_PERMANENT]!!
        val substratumId = record[OBSERVATION_PLOTS.monitoringPlotHistories.SUBSTRATUM_ID]
        val substratumHistoryId =
            record[OBSERVATION_PLOTS.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID]
        val stratumHistoryId =
            record[OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.STRATUM_HISTORY_ID]
        val stratumId =
            record[
                OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.stratumHistories
                    .STRATUM_ID]

        val plantCounts = allPlantCounts[monitoringPlotId] ?: emptyMap()

        updateSpeciesTotals(
            observationId,
            plantingSite,
            plantingSiteHistoryId,
            stratumId,
            stratumHistoryId,
            substratumId,
            substratumHistoryId,
            monitoringPlotId,
            monitoringPlotHistoryId,
            observation.isAdHoc,
            isPermanent,
            plantCounts,
        )

        updateObservationResults(
            observationId,
            plantingSite,
            stratumId,
            stratumHistoryId,
            substratumId,
            substratumHistoryId,
            monitoringPlotId,
            observation.isAdHoc,
        )
      }
    }
  }

  fun deleteObservation(observationId: ObservationId) {
    val t0PlotIds =
        dslContext
            .select(PLOT_T0_OBSERVATIONS.MONITORING_PLOT_ID)
            .from(PLOT_T0_OBSERVATIONS)
            .where(PLOT_T0_OBSERVATIONS.OBSERVATION_ID.eq(observationId))
            .fetch(PLOT_T0_OBSERVATIONS.MONITORING_PLOT_ID.asNonNullable())

    dslContext.transaction { _ ->
      if (t0PlotIds.isNotEmpty()) {
        dslContext
            .deleteFrom(PLOT_T0_DENSITIES)
            .where(PLOT_T0_DENSITIES.MONITORING_PLOT_ID.`in`(t0PlotIds))
            .execute()
      }

      dslContext.deleteFrom(OBSERVATIONS).where(OBSERVATIONS.ID.eq(observationId)).execute()
    }
  }

  /**
   * Deletes incomplete plots from an observation. Optionally retains permanent plots that weren't
   * observed in a list of other observations.
   *
   * @param dryRun If true, calculate the lists of plot IDs that would be retained and deleted, but
   *   don't actually delete anything.
   * @return The list of retained plot IDs and the list of deleted plot IDs
   */
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

    return observationLocker.withLockedObservation(observationId) {
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

        if (!dryRun && retainedPlotIds.isNotEmpty()) {
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

  private fun resetPlantPopulationSinceLastObservation(plantingSiteId: PlantingSiteId) {
    dslContext
        .update(PLANTING_SITE_POPULATIONS)
        .set(PLANTING_SITE_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION, 0)
        .where(PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
        .execute()

    dslContext
        .update(STRATUM_POPULATIONS)
        .set(STRATUM_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION, 0)
        .where(
            STRATUM_POPULATIONS.STRATUM_ID.`in`(
                DSL.select(STRATA.ID).from(STRATA).where(STRATA.PLANTING_SITE_ID.eq(plantingSiteId))
            )
        )
        .execute()

    dslContext
        .update(SUBSTRATUM_POPULATIONS)
        .set(SUBSTRATUM_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION, 0)
        .where(
            SUBSTRATUM_POPULATIONS.SUBSTRATUM_ID.`in`(
                DSL.select(SUBSTRATA.ID)
                    .from(SUBSTRATA)
                    .where(SUBSTRATA.PLANTING_SITE_ID.eq(plantingSiteId))
            )
        )
        .execute()
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

  /** Updates the tables that hold the aggregated per-species plant totals from observations. */
  private fun updateSpeciesTotals(
      observationId: ObservationId,
      plantingSite: PlantingSitesRow,
      plantingSiteHistoryId: PlantingSiteHistoryId,
      stratumId: StratumId?,
      stratumHistoryId: StratumHistoryId?,
      substratumId: SubstratumId?,
      substratumHistoryId: SubstratumHistoryId?,
      monitoringPlotId: MonitoringPlotId?,
      monitoringPlotHistoryId: MonitoringPlotHistoryId?,
      isAdHoc: Boolean,
      isPermanent: Boolean,
      plantCountsBySpecies: Map<RecordedSpeciesKey, Map<RecordedPlantStatus, Int>>,
  ) {
    if (plantCountsBySpecies.isNotEmpty()) {
      if (monitoringPlotId != null && monitoringPlotHistoryId != null) {
        updateSpeciesTotalsTable(
            observationId,
            isPermanent,
            plantCountsBySpecies,
            plantingSite,
            ObservationSpeciesPlot(monitoringPlotId, monitoringPlotHistoryId),
        )
      }

      if (!isAdHoc) {
        if (substratumHistoryId != null) {
          updateSpeciesTotalsTable(
              observationId,
              isPermanent,
              plantCountsBySpecies,
              plantingSite,
              ObservationSpeciesSubstratum(
                  substratumHistoryId,
                  substratumId,
                  monitoringPlotId,
              ),
          )
        }

        if (stratumHistoryId != null) {
          updateSpeciesTotalsTable(
              observationId,
              isPermanent,
              plantCountsBySpecies,
              plantingSite,
              ObservationSpeciesStratum(stratumHistoryId, stratumId, monitoringPlotId),
          )
        }

        updateSpeciesTotalsTable(
            observationId,
            isPermanent,
            plantCountsBySpecies,
            plantingSite,
            ObservationSpeciesSite(plantingSite.id!!, plantingSiteHistoryId, monitoringPlotId),
        )
      }
    }
  }

  private fun rollup(field: Field<Int?>): Field<Int> =
      DSL.coalesce(DSL.sum(field).cast(SQLDataType.INTEGER), 0)

  /**
   * Rolls up the per-species totals for the given scope (plot, substratum, stratum, or site) into
   * the corresponding `observation_*_results` aggregate row, computing the plant density (and
   * std-dev where applicable) from the per-plot results table.
   */
  private fun <ID : Any, HistoryId : Any> updatePlotObservationResults(
      scope: ObservationResultsScope<ID, HistoryId>,
      observationId: ObservationId,
  ) {
    val resultsTable = scope.observedTotalsTable
    val rollupTable = scope.rollupSpeciesTable

    val resultsObservationIdField = resultsTable.field("observation_id", OBSERVATIONS.ID.dataType)!!
    val resultsTotalLiveField = resultsTable.field("total_live", Int::class.java)!!
    val resultsTotalDeadField = resultsTable.field("total_dead", Int::class.java)!!
    val resultsTotalExistingField = resultsTable.field("total_existing", Int::class.java)!!
    val resultsPermanentLiveField = resultsTable.field("permanent_live", Int::class.java)!!
    val resultsPlantDensityField = resultsTable.field("plant_density", Int::class.java)!!
    val resultsPlantDensityStdDevField =
        resultsTable.field("plant_density_std_dev", Int::class.java)
    val resultsObservedDensityField = resultsTable.field("observed_density", Int::class.java)

    val rollupObservationIdField = rollupTable.field("observation_id", OBSERVATIONS.ID.dataType)!!
    val rollupTotalLiveField = rollupTable.field("total_live", Int::class.java)!!
    val rollupTotalDeadField = rollupTable.field("total_dead", Int::class.java)!!
    val rollupTotalExistingField = rollupTable.field("total_existing", Int::class.java)!!
    val rollupPermanentLiveField = rollupTable.field("permanent_live", Int::class.java)!!

    val (totalLive, totalDead, totalExisting, permanentLive) =
        dslContext
            .select(
                rollup(rollupTotalLiveField),
                rollup(rollupTotalDeadField),
                rollup(rollupTotalExistingField),
                rollup(rollupPermanentLiveField),
            )
            .from(rollupTable)
            .where(rollupObservationIdField.eq(observationId))
            .and(scope.rollupSpeciesCondition)
            .fetchOne()!!

    val plantDensityField: Field<Int?> =
        if (scope is ObservationResultsPlot) {
          DSL.field(
              DSL.select(
                      DSL.round(
                              DSL.value(totalLive)
                                  .cast(SQLDataType.NUMERIC)
                                  .times(DSL.inline(SQUARE_METERS_PER_HECTARE))
                                  .div(
                                      MONITORING_PLOTS.SIZE_METERS.times(
                                              MONITORING_PLOTS.SIZE_METERS
                                          )
                                          .cast(SQLDataType.NUMERIC)
                                  )
                          )
                          .cast(SQLDataType.INTEGER)
                  )
                  .from(MONITORING_PLOTS)
                  .where(MONITORING_PLOTS.ID.eq(scope.plotId))
          )
        } else {
          scope.latestPlantDensityField(DSL.value(observationId, OBSERVATIONS.ID.dataType))
        }

    val plantDensityStdDevField: Field<Int?>? =
        if (resultsPlantDensityStdDevField != null) {
          scope.latestPlantDensityStdDevField(DSL.value(observationId, OBSERVATIONS.ID.dataType))
        } else {
          null
        }

    val observedDensityField: Field<Int?>? =
        if (resultsObservedDensityField != null) {
          scope.observedPlantDensityField(DSL.value(observationId, OBSERVATIONS.ID.dataType))
        } else {
          null
        }

    dslContext
        .insertInto(resultsTable)
        .set(resultsObservationIdField, observationId)
        .set(scope.observedTotalsScopeField, scope.scopeId)
        .set(scope.observedTotalsScopeHistoryField, scope.scopeHistoryId)
        .set(resultsTotalLiveField, totalLive)
        .set(resultsTotalDeadField, totalDead)
        .set(resultsTotalExistingField, totalExisting)
        .set(resultsPermanentLiveField, permanentLive)
        .set(resultsPlantDensityField, plantDensityField)
        .apply {
          if (resultsPlantDensityStdDevField != null && plantDensityStdDevField != null) {
            set(resultsPlantDensityStdDevField, plantDensityStdDevField)
          }
        }
        .apply {
          if (resultsObservedDensityField != null && observedDensityField != null) {
            set(resultsObservedDensityField, observedDensityField)
          }
        }
        .onDuplicateKeyUpdate()
        .set(scope.observedTotalsScopeHistoryField, scope.scopeHistoryId)
        .set(resultsTotalLiveField, totalLive)
        .set(resultsTotalDeadField, totalDead)
        .set(resultsTotalExistingField, totalExisting)
        .set(resultsPermanentLiveField, permanentLive)
        .set(resultsPlantDensityField, plantDensityField)
        .apply {
          if (resultsPlantDensityStdDevField != null && plantDensityStdDevField != null) {
            set(resultsPlantDensityStdDevField, plantDensityStdDevField)
          }
        }
        .apply {
          if (resultsObservedDensityField != null && observedDensityField != null) {
            set(resultsObservedDensityField, observedDensityField)
          }
        }
        .execute()
  }

  private fun updateObservationResults(
      observationId: ObservationId,
      plantingSite: PlantingSitesRow,
      stratumId: StratumId?,
      stratumHistoryId: StratumHistoryId?,
      substratumId: SubstratumId?,
      substratumHistoryId: SubstratumHistoryId?,
      monitoringPlotId: MonitoringPlotId,
      isAdHoc: Boolean,
  ) {
    val monitoringPlotHistoryId =
        dslContext
            .select(OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID.asNonNullable())
            .from(OBSERVATION_PLOTS)
            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
            .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId))
            .fetchOne()!!
            .value1()

    updatePlotObservationResults(
        ObservationResultsPlot(monitoringPlotHistoryId, monitoringPlotId),
        observationId,
    )

    if (!isAdHoc) {
      if (substratumHistoryId != null) {
        updatePlotObservationResults(
            ObservationResultsSubstratum(substratumHistoryId, substratumId),
            observationId,
        )
      }

      if (stratumHistoryId != null) {
        updatePlotObservationResults(
            ObservationResultsStratum(stratumHistoryId, stratumId),
            observationId,
        )
      }

      val plantingSiteHistoryId =
          dslContext
              .select(OBSERVATIONS.PLANTING_SITE_HISTORY_ID)
              .from(OBSERVATIONS)
              .where(OBSERVATIONS.ID.eq(observationId))
              .fetchOne()!!
              .value1()!!

      updatePlotObservationResults(
          ObservationResultsSite(plantingSiteHistoryId, plantingSite.id!!),
          observationId,
      )
    }
  }

  private fun updateObservationResults(
      observationId: ObservationId,
      plantingSiteId: PlantingSiteId,
  ) {
    val plotTotals =
        DSL.select(
                OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID,
                rollup(OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_LIVE).`as`("total_live"),
                rollup(OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_DEAD).`as`("total_dead"),
                rollup(OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_EXISTING).`as`("total_existing"),
                rollup(OBSERVED_PLOT_SPECIES_TOTALS.PERMANENT_LIVE).`as`("permanent_live"),
                DSL.round(
                        rollup(OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_LIVE)
                            .cast(SQLDataType.NUMERIC)
                            .times(DSL.inline(SQUARE_METERS_PER_HECTARE))
                            .div(
                                MONITORING_PLOTS.SIZE_METERS.times(MONITORING_PLOTS.SIZE_METERS)
                                    .cast(SQLDataType.NUMERIC)
                            )
                    )
                    .cast(SQLDataType.INTEGER)
                    .`as`("plant_density"),
            )
            .from(OBSERVED_PLOT_SPECIES_TOTALS)
            .join(MONITORING_PLOTS)
            .on(MONITORING_PLOTS.ID.eq(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID))
            .where(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
            .groupBy(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID, MONITORING_PLOTS.SIZE_METERS)
            .asTable("plot_totals")

    val ptMonitoringPlotId = plotTotals.field(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID)!!
    val ptTotalLive = plotTotals.field("total_live", Int::class.java)!!
    val ptTotalDead = plotTotals.field("total_dead", Int::class.java)!!
    val ptTotalExisting = plotTotals.field("total_existing", Int::class.java)!!
    val ptPermanentLive = plotTotals.field("permanent_live", Int::class.java)!!
    val ptPlantDensity = plotTotals.field("plant_density", Int::class.java)!!

    dslContext
        .update(OBSERVATION_PLOT_RESULTS)
        .set(OBSERVATION_PLOT_RESULTS.TOTAL_LIVE, ptTotalLive)
        .set(OBSERVATION_PLOT_RESULTS.TOTAL_DEAD, ptTotalDead)
        .set(OBSERVATION_PLOT_RESULTS.TOTAL_EXISTING, ptTotalExisting)
        .set(OBSERVATION_PLOT_RESULTS.PERMANENT_LIVE, ptPermanentLive)
        .set(OBSERVATION_PLOT_RESULTS.PLANT_DENSITY, ptPlantDensity)
        .from(plotTotals)
        .where(OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(observationId))
        .and(OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_ID.eq(ptMonitoringPlotId))
        .execute()

    val substratumTotals =
        DSL.select(
                OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SUBSTRATUM_ID,
                rollup(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.TOTAL_LIVE).`as`("total_live"),
                rollup(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.TOTAL_DEAD).`as`("total_dead"),
                rollup(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.TOTAL_EXISTING).`as`("total_existing"),
                rollup(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.PERMANENT_LIVE).`as`("permanent_live"),
            )
            .from(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
            .where(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
            .groupBy(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SUBSTRATUM_ID)
            .asTable("substratum_totals")

    val substratumDensities =
        DSL.select(
                MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID,
                DSL.avg(OBSERVATION_PLOT_RESULTS.PLANT_DENSITY)
                    .cast(SQLDataType.INTEGER)
                    .`as`("plant_density"),
                DSL.stddevSamp(OBSERVATION_PLOT_RESULTS.PLANT_DENSITY)
                    .cast(SQLDataType.INTEGER)
                    .`as`("plant_density_std_dev"),
            )
            .from(OBSERVATION_PLOT_RESULTS)
            .join(MONITORING_PLOT_HISTORIES)
            .on(
                MONITORING_PLOT_HISTORIES.ID.eq(OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_HISTORY_ID)
            )
            .where(OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(observationId))
            .groupBy(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID)
            .asTable("substratum_densities")

    val stSubstratumId = substratumTotals.field(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SUBSTRATUM_ID)!!
    val stTotalLive = substratumTotals.field("total_live", Int::class.java)!!
    val stTotalDead = substratumTotals.field("total_dead", Int::class.java)!!
    val stTotalExisting = substratumTotals.field("total_existing", Int::class.java)!!
    val stPermanentLive = substratumTotals.field("permanent_live", Int::class.java)!!
    val sdSubstratumHistoryId =
        substratumDensities.field(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID)!!
    val sdPlantDensity = substratumDensities.field("plant_density", Int::class.java)
    val sdPlantDensityStdDev = substratumDensities.field("plant_density_std_dev", Int::class.java)

    dslContext
        .update(OBSERVATION_SUBSTRATUM_RESULTS)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.TOTAL_LIVE, stTotalLive)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.TOTAL_DEAD, stTotalDead)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.TOTAL_EXISTING, stTotalExisting)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.PERMANENT_LIVE, stPermanentLive)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.PLANT_DENSITY, sdPlantDensity)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.PLANT_DENSITY_STD_DEV, sdPlantDensityStdDev)
        .from(substratumTotals, substratumDensities)
        .where(OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID.eq(observationId))
        .and(OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_ID.eq(stSubstratumId))
        .and(OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID.eq(sdSubstratumHistoryId))
        .execute()

    val stratumTotals =
        DSL.select(
                OBSERVED_STRATUM_SPECIES_TOTALS.STRATUM_ID,
                rollup(OBSERVED_STRATUM_SPECIES_TOTALS.TOTAL_LIVE).`as`("total_live"),
                rollup(OBSERVED_STRATUM_SPECIES_TOTALS.TOTAL_DEAD).`as`("total_dead"),
                rollup(OBSERVED_STRATUM_SPECIES_TOTALS.TOTAL_EXISTING).`as`("total_existing"),
                rollup(OBSERVED_STRATUM_SPECIES_TOTALS.PERMANENT_LIVE).`as`("permanent_live"),
            )
            .from(OBSERVED_STRATUM_SPECIES_TOTALS)
            .where(OBSERVED_STRATUM_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
            .groupBy(OBSERVED_STRATUM_SPECIES_TOTALS.STRATUM_ID)
            .asTable("stratum_totals")

    val stratumDensities =
        DSL.select(
                SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID,
                DSL.avg(OBSERVATION_PLOT_RESULTS.PLANT_DENSITY)
                    .cast(SQLDataType.INTEGER)
                    .`as`("plant_density"),
                DSL.stddevSamp(OBSERVATION_PLOT_RESULTS.PLANT_DENSITY)
                    .cast(SQLDataType.INTEGER)
                    .`as`("plant_density_std_dev"),
            )
            .from(OBSERVATION_PLOT_RESULTS)
            .join(MONITORING_PLOT_HISTORIES)
            .on(
                MONITORING_PLOT_HISTORIES.ID.eq(OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_HISTORY_ID)
            )
            .join(SUBSTRATUM_HISTORIES)
            .on(SUBSTRATUM_HISTORIES.ID.eq(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID))
            .where(
                OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(
                    latestObservationForSubstratumField(
                        DSL.value(observationId, OBSERVATIONS.ID.dataType),
                        SUBSTRATUM_HISTORIES.SUBSTRATUM_ID,
                    )
                )
            )
            .groupBy(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID)
            .asTable("stratum_densities")

    val sdStratumId = stratumTotals.field(OBSERVED_STRATUM_SPECIES_TOTALS.STRATUM_ID)!!
    val sTotalLive = stratumTotals.field("total_live", Int::class.java)!!
    val sTotalDead = stratumTotals.field("total_dead", Int::class.java)!!
    val sTotalExisting = stratumTotals.field("total_existing", Int::class.java)!!
    val sPermanentLive = stratumTotals.field("permanent_live", Int::class.java)!!
    val sdStratumHistoryId = stratumDensities.field(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID)!!
    val sdStratumPlantDensity = stratumDensities.field("plant_density", Int::class.java)
    val sdStratumPlantDensityStdDev =
        stratumDensities.field("plant_density_std_dev", Int::class.java)

    val stratumObservedDensity =
        DSL.field(
            DSL.select(DSL.avg(OBSERVATION_PLOT_RESULTS.PLANT_DENSITY).cast(SQLDataType.INTEGER))
                .from(OBSERVATION_PLOT_RESULTS)
                .join(MONITORING_PLOT_HISTORIES)
                .on(
                    MONITORING_PLOT_HISTORIES.ID.eq(
                        OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_HISTORY_ID
                    )
                )
                .join(SUBSTRATUM_HISTORIES)
                .on(SUBSTRATUM_HISTORIES.ID.eq(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID))
                .where(
                    SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(
                        OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID
                    )
                )
                .and(OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(observationId))
        )

    dslContext
        .update(OBSERVATION_STRATUM_RESULTS)
        .set(OBSERVATION_STRATUM_RESULTS.TOTAL_LIVE, sTotalLive)
        .set(OBSERVATION_STRATUM_RESULTS.TOTAL_DEAD, sTotalDead)
        .set(OBSERVATION_STRATUM_RESULTS.TOTAL_EXISTING, sTotalExisting)
        .set(OBSERVATION_STRATUM_RESULTS.PERMANENT_LIVE, sPermanentLive)
        .set(OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY, sdStratumPlantDensity)
        .set(OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY_STD_DEV, sdStratumPlantDensityStdDev)
        .set(OBSERVATION_STRATUM_RESULTS.OBSERVED_DENSITY, stratumObservedDensity)
        .from(stratumTotals, stratumDensities)
        .where(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID.eq(observationId))
        .and(OBSERVATION_STRATUM_RESULTS.STRATUM_ID.eq(sdStratumId))
        .and(OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID.eq(sdStratumHistoryId))
        .execute()

    val siteTotals =
        DSL.select(
                rollup(OBSERVED_SITE_SPECIES_TOTALS.TOTAL_LIVE).`as`("total_live"),
                rollup(OBSERVED_SITE_SPECIES_TOTALS.TOTAL_DEAD).`as`("total_dead"),
                rollup(OBSERVED_SITE_SPECIES_TOTALS.TOTAL_EXISTING).`as`("total_existing"),
                rollup(OBSERVED_SITE_SPECIES_TOTALS.PERMANENT_LIVE).`as`("permanent_live"),
            )
            .from(OBSERVED_SITE_SPECIES_TOTALS)
            .where(OBSERVED_SITE_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
            .and(OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID.eq(plantingSiteId))
            .asTable("site_totals")

    val siteDensities =
        DSL.select(
                DSL.avg(OBSERVATION_PLOT_RESULTS.PLANT_DENSITY)
                    .cast(SQLDataType.INTEGER)
                    .`as`("plant_density"),
                DSL.stddevSamp(OBSERVATION_PLOT_RESULTS.PLANT_DENSITY)
                    .cast(SQLDataType.INTEGER)
                    .`as`("plant_density_std_dev"),
            )
            .from(OBSERVATION_PLOT_RESULTS)
            .join(MONITORING_PLOTS)
            .on(MONITORING_PLOTS.ID.eq(OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_ID))
            .join(MONITORING_PLOT_HISTORIES)
            .on(
                MONITORING_PLOT_HISTORIES.ID.eq(OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_HISTORY_ID)
            )
            .join(SUBSTRATUM_HISTORIES)
            .on(SUBSTRATUM_HISTORIES.ID.eq(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID))
            .where(MONITORING_PLOTS.PLANTING_SITE_ID.eq(plantingSiteId))
            .and(
                OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(
                    latestObservationForSubstratumField(
                        DSL.value(observationId, OBSERVATIONS.ID.dataType),
                        SUBSTRATUM_HISTORIES.SUBSTRATUM_ID,
                    )
                )
            )
            .asTable("site_densities")

    val siTotalLive = siteTotals.field("total_live", Int::class.java)!!
    val siTotalDead = siteTotals.field("total_dead", Int::class.java)!!
    val siTotalExisting = siteTotals.field("total_existing", Int::class.java)!!
    val siPermanentLive = siteTotals.field("permanent_live", Int::class.java)!!
    val siPlantDensity = siteDensities.field("plant_density", Int::class.java)
    val siPlantDensityStdDev = siteDensities.field("plant_density_std_dev", Int::class.java)

    // Observed density ignores last-observed carry-forward: just this observation's plots.
    val siteObservedDensity =
        DSL.field(
            DSL.select(DSL.avg(OBSERVATION_PLOT_RESULTS.PLANT_DENSITY).cast(SQLDataType.INTEGER))
                .from(OBSERVATION_PLOT_RESULTS)
                .where(OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(observationId))
        )

    dslContext
        .update(OBSERVATION_SITE_RESULTS)
        .set(OBSERVATION_SITE_RESULTS.TOTAL_LIVE, siTotalLive)
        .set(OBSERVATION_SITE_RESULTS.TOTAL_DEAD, siTotalDead)
        .set(OBSERVATION_SITE_RESULTS.TOTAL_EXISTING, siTotalExisting)
        .set(OBSERVATION_SITE_RESULTS.PERMANENT_LIVE, siPermanentLive)
        .set(OBSERVATION_SITE_RESULTS.PLANT_DENSITY, siPlantDensity)
        .set(OBSERVATION_SITE_RESULTS.PLANT_DENSITY_STD_DEV, siPlantDensityStdDev)
        .set(OBSERVATION_SITE_RESULTS.OBSERVED_DENSITY, siteObservedDensity)
        .from(siteTotals, siteDensities)
        .where(OBSERVATION_SITE_RESULTS.OBSERVATION_ID.eq(observationId))
        .execute()
  }

  /**
   * Updates one of the tables that holds the aggregated per-species plant totals from observations.
   *
   * These tables are all identical with the exception of one column that identifies the scope of
   * aggregation (monitoring plot, stratum, or planting site).
   */
  private fun <ID : Any, HistoryId : Any> updateSpeciesTotalsTable(
      observationId: ObservationId,
      isPermanent: Boolean,
      totals: Map<RecordedSpeciesKey, Map<RecordedPlantStatus, Int>>,
      plantingSite: PlantingSitesRow,
      updateScope: ObservationSpeciesScope<ID, HistoryId>,
  ) {
    val table = updateScope.observedTotalsTable
    val observationIdField = table.field("observation_id", OBSERVATIONS.ID.dataType)!!
    val certaintyField = table.field("certainty_id", RECORDED_PLANTS.CERTAINTY_ID.dataType)!!
    val speciesIdField = table.field("species_id", SPECIES.ID.dataType)!!
    val speciesNameField = table.field("species_name", String::class.java)!!
    val totalLiveField = table.field("total_live", Int::class.java)!!
    val totalDeadField = table.field("total_dead", Int::class.java)!!
    val totalExistingField = table.field("total_existing", Int::class.java)!!
    val permanentLiveField = table.field("permanent_live", Int::class.java)!!
    val survivalRateField = table.field("survival_rate", Int::class.java)!!

    val includesTempPlots = plantingSite.survivalRateIncludesTempPlots!!
    val speciesIds = totals.keys.mapNotNull { it.id }.toSet()
    val observationIdValue = DSL.value(observationId, OBSERVATIONS.ID.dataType)

    // Survival-rate denominators depend only on the scope, species, observation, and which plots
    // have completed observations; none of those change while we're writing the per-species totals
    // rows below. Compute them all up front.
    val permanentDenominators: Map<SpeciesId, BigDecimal> =
        if (speciesIds.isNotEmpty() && (includesTempPlots || isPermanent)) {
          getSurvivalRateDenominatorsBySpecies(updateScope, speciesIds, observationIdValue)
        } else {
          emptyMap()
        }
    val tempDenominators: Map<SpeciesId, BigDecimal> =
        if (speciesIds.isNotEmpty() && includesTempPlots) {
          getSurvivalRateTempDenominatorsBySpecies(updateScope, speciesIds, observationIdValue)
        } else {
          emptyMap()
        }

    dslContext.transaction { _ ->
      totals.forEach { (speciesKey, statusCounts) ->
        val totalLive = statusCounts.getOrDefault(RecordedPlantStatus.Live, 0)
        val totalDead = statusCounts.getOrDefault(RecordedPlantStatus.Dead, 0)
        val totalExisting = statusCounts.getOrDefault(RecordedPlantStatus.Existing, 0)
        val permanentLive: Int =
            if (isPermanent) {
              totalLive
            } else {
              0
            }

        val permanentDenominator = speciesKey.id?.let { permanentDenominators[it] }
        val tempDenominator = speciesKey.id?.let { tempDenominators[it] }
        val combinedDenominator =
            when {
              permanentDenominator != null && tempDenominator != null ->
                  permanentDenominator + tempDenominator
              permanentDenominator != null -> permanentDenominator
              else -> tempDenominator
            }

        val survivalRate =
            if (includesTempPlots && speciesKey.id != null) {
              survivalRateValue(DSL.value(totalLive), combinedDenominator)
            } else if (isPermanent && speciesKey.id != null) {
              survivalRateValue(DSL.value(permanentLive), combinedDenominator)
            } else {
              DSL.castNull(SQLDataType.INTEGER)
            }

        val rowsInserted =
            dslContext
                .insertInto(table)
                .set(observationIdField, observationId)
                .set(updateScope.observedTotalsScopeField, updateScope.scopeId)
                .set(updateScope.observedTotalsScopeHistoryField, updateScope.scopeHistoryId)
                .set(certaintyField, speciesKey.certainty)
                .set(speciesIdField, speciesKey.id)
                .set(speciesNameField, speciesKey.name)
                .set(totalLiveField, totalLive)
                .set(totalDeadField, totalDead)
                .set(totalExistingField, totalExisting)
                .set(permanentLiveField, permanentLive)
                .set(survivalRateField, survivalRate)
                .onConflictDoNothing()
                .execute()

        if (rowsInserted == 0) {
          val scopeIdAndSpeciesCondition =
              updateScope.observedTotalsScopeHistoryField
                  .eq(updateScope.scopeHistoryId)
                  .and(
                      if (speciesKey.id != null) speciesIdField.eq(speciesKey.id)
                      else speciesIdField.isNull
                  )
                  .and(
                      if (speciesKey.name != null) speciesNameField.eq(speciesKey.name)
                      else speciesNameField.isNull
                  )

          val survivalRate =
              if (includesTempPlots && speciesKey.id != null) {
                survivalRateValue(totalLiveField.plus(totalLive), combinedDenominator)
              } else if (isPermanent && speciesKey.id != null) {
                survivalRateValue(permanentLiveField.plus(permanentLive), permanentDenominator)
              } else {
                survivalRateField
              }

          val rowsUpdated =
              dslContext
                  .update(table)
                  .set(totalLiveField, totalLiveField.plus(totalLive))
                  .set(totalDeadField, totalDeadField.plus(totalDead))
                  .set(totalExistingField, totalExistingField.plus(totalExisting))
                  .set(permanentLiveField, permanentLiveField.plus(permanentLive))
                  .set(survivalRateField, survivalRate)
                  .where(observationIdField.eq(observationId))
                  .and(scopeIdAndSpeciesCondition)
                  .execute()

          if (rowsUpdated != 1) {
            log.withMDC(
                "table" to table.name,
                "observation" to observationId,
                "scope" to updateScope.scopeHistoryId,
                "species" to speciesKey,
            ) {
              log.error("BUG! Insert and update of species totals both failed")
            }
          }
        }
      }
    }
  }

  /**
   * Returns the survival-rate denominator (cumulative t0 plant density across this scope's
   * completed permanent plots) for each of the requested species.
   */
  private fun <ID : Any, HistoryId : Any> getSurvivalRateDenominatorsBySpecies(
      updateScope: ObservationSpeciesScope<ID, HistoryId>,
      speciesIds: Set<SpeciesId>,
      observationIdField: Field<ObservationId?>,
  ): Map<SpeciesId, BigDecimal> {
    val opPerm = OBSERVATION_PLOTS.`as`("opPerm")
    val denominatorField =
        DSL.sum(PLOT_T0_DENSITIES.PLOT_DENSITY).mul(DSL.inline(HECTARES_PER_PLOT))

    return dslContext
        .select(PLOT_T0_DENSITIES.SPECIES_ID, denominatorField)
        .from(PLOT_T0_DENSITIES)
        .join(opPerm)
        .on(opPerm.MONITORING_PLOT_ID.eq(PLOT_T0_DENSITIES.MONITORING_PLOT_ID))
        .where(updateScope.t0DensityCondition(opPerm))
        .and(PLOT_T0_DENSITIES.SPECIES_ID.`in`(speciesIds))
        .and(
            plotHasCompletedObservations(
                PLOT_T0_DENSITIES.MONITORING_PLOT_ID,
                true,
                updateScope.alternateCompletedCondition(PLOT_T0_DENSITIES.MONITORING_PLOT_ID),
            )
        )
        .and(
            opPerm.OBSERVATION_ID.eq(
                observationIdForPlot(
                    PLOT_T0_DENSITIES.MONITORING_PLOT_ID,
                    observationIdField,
                    true,
                )
            )
        )
        .groupBy(PLOT_T0_DENSITIES.SPECIES_ID)
        .fetch()
        .mapNotNull { record ->
          val speciesId = record.value1()
          val denominator = record.value2()
          if (speciesId != null && denominator != null) speciesId to denominator else null
        }
        .toMap()
  }

  /**
   * Returns the survival-rate temp-plot denominator (cumulative t0 stratum density across this
   * scope's completed temporary plots) for each of the requested species, computed in a single
   * grouped query. Bulk equivalent of [getSurvivalRateTempDenominator].
   */
  private fun <ID : Any, HistoryId : Any> getSurvivalRateTempDenominatorsBySpecies(
      updateScope: ObservationSpeciesScope<ID, HistoryId>,
      speciesIds: Set<SpeciesId>,
      observationIdField: Field<ObservationId?>,
  ): Map<SpeciesId, BigDecimal> {
    val opTemp = OBSERVATION_PLOTS.`as`("opTemp")
    return with(STRATUM_T0_TEMP_DENSITIES) {
      val denominatorField = DSL.sum(STRATUM_DENSITY).mul(DSL.inline(HECTARES_PER_PLOT))

      dslContext
          .select(STRATUM_T0_TEMP_DENSITIES.SPECIES_ID, denominatorField)
          .from(STRATUM_T0_TEMP_DENSITIES)
          .join(opTemp)
          .on(
              opTemp.monitoringPlotHistories.substratumHistories.stratumHistories.STRATUM_ID.eq(
                  STRATUM_T0_TEMP_DENSITIES.STRATUM_ID
              )
          )
          .where(STRATUM_T0_TEMP_DENSITIES.SPECIES_ID.`in`(speciesIds))
          .and(updateScope.tempStratumCondition(opTemp))
          .and(strata.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true))
          .and(
              plotHasCompletedObservations(
                  opTemp.MONITORING_PLOT_ID,
                  false,
                  updateScope.alternateCompletedCondition(opTemp.MONITORING_PLOT_ID),
              )
          )
          .and(
              opTemp.OBSERVATION_ID.eq(
                  observationIdForPlot(opTemp.MONITORING_PLOT_ID, observationIdField, false)
              )
          )
          .groupBy(STRATUM_T0_TEMP_DENSITIES.SPECIES_ID)
          .fetch()
          .mapNotNull { record ->
            val speciesId = record.value1()
            val denominator = record.value2()
            if (speciesId != null && denominator != null) speciesId to denominator else null
          }
          .toMap()
    }
  }

  /**
   * Returns the survival-rate column expression using a precomputed denominator. Returns SQL null
   * if [denominator] is null.
   */
  private fun survivalRateValue(numerator: Field<Int>, denominator: BigDecimal?): Field<Int> =
      if (denominator == null) {
        DSL.castNull(SQLDataType.INTEGER)
      } else {
        DSL.if_(
            DSL.value(denominator).eq(BigDecimal.ZERO),
            DSL.zero(),
            numerator.mul(100).div(DSL.value(denominator)),
        )
      }

  private fun plotHasCompletedObservations(
      monitoringPlotIdField: Field<MonitoringPlotId?>,
      isPermanent: Boolean,
      alternateCompleteCondition: Condition = DSL.falseCondition(),
  ): Condition =
      DSL.exists(
          DSL.selectOne()
              .from(
                  DSL.select(OBSERVATION_PLOTS.IS_PERMANENT)
                      .from(OBSERVATION_PLOTS)
                      .where(
                          OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotIdField)
                              .and(
                                  OBSERVATION_PLOTS.COMPLETED_TIME.isNotNull.or(
                                      alternateCompleteCondition
                                  )
                              )
                      )
                      .orderBy(
                          OBSERVATION_PLOTS.COMPLETED_TIME.desc().nullsFirst(),
                          OBSERVATION_PLOTS.OBSERVATION_ID.desc(),
                      )
                      .limit(1)
                      .asTable("most_recent")
              )
              .where(DSL.field("most_recent.IS_PERMANENT", Boolean::class.java).eq(isPermanent))
      )

  private fun <ID : Any, HistoryId : Any> getSurvivalRateDenominator(
      updateScope: ObservationSpeciesScope<ID, HistoryId>,
      condition: Condition,
      observationIdField: Field<ObservationId?>,
  ): Field<BigDecimal> {
    val opPerm = OBSERVATION_PLOTS.`as`("opPerm")
    return DSL.field(
        DSL.select(DSL.sum(PLOT_T0_DENSITIES.PLOT_DENSITY).mul(DSL.inline(HECTARES_PER_PLOT)))
            .from(PLOT_T0_DENSITIES)
            .join(opPerm)
            .on(opPerm.MONITORING_PLOT_ID.eq(PLOT_T0_DENSITIES.MONITORING_PLOT_ID))
            .where(updateScope.t0DensityCondition(opPerm))
            .and(condition)
            .and(
                plotHasCompletedObservations(
                    PLOT_T0_DENSITIES.MONITORING_PLOT_ID,
                    true,
                    updateScope.alternateCompletedCondition(PLOT_T0_DENSITIES.MONITORING_PLOT_ID),
                )
            )
            .and(
                opPerm.OBSERVATION_ID.eq(
                    observationIdForPlot(
                        PLOT_T0_DENSITIES.MONITORING_PLOT_ID,
                        observationIdField,
                        true,
                    )
                )
            )
    )
  }

  private fun <ID : Any, HistoryId : Any> getSurvivalRateTempDenominator(
      updateScope: ObservationSpeciesScope<ID, HistoryId>,
      condition: Condition,
      observationIdField: Field<ObservationId?>,
  ): Field<BigDecimal> {
    val opTemp = OBSERVATION_PLOTS.`as`("opTemp")
    return with(STRATUM_T0_TEMP_DENSITIES) {
      DSL.field(
          DSL.select(DSL.sum(STRATUM_DENSITY).mul(DSL.inline(HECTARES_PER_PLOT)))
              .from(STRATUM_T0_TEMP_DENSITIES)
              .join(opTemp)
              .on(
                  opTemp.monitoringPlotHistories.substratumHistories.stratumHistories.STRATUM_ID.eq(
                      STRATUM_T0_TEMP_DENSITIES.STRATUM_ID
                  )
              )
              .where(condition)
              .and(updateScope.tempStratumCondition(opTemp))
              .and(strata.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true))
              .and(
                  plotHasCompletedObservations(
                      opTemp.MONITORING_PLOT_ID,
                      false,
                      updateScope.alternateCompletedCondition(opTemp.MONITORING_PLOT_ID),
                  )
              )
              .and(
                  opTemp.OBSERVATION_ID.eq(
                      observationIdForPlot(
                          opTemp.MONITORING_PLOT_ID,
                          observationIdField,
                          false,
                      )
                  )
              )
      )
    }
  }

  /**
   * Computes the variance of survival rates weighted by planting density
   * https://en.wikipedia.org/wiki/Reduced_chi-squared_statistic
   */
  private fun <ID : Any, HistoryId : Any> getSurvivalRateWeightedStandardDeviation(
      updateScope: ObservationResultsScope<ID, HistoryId>,
  ): Field<Int> {
    val plotResults = OBSERVATION_PLOT_RESULTS.`as`("plotResults")
    val survivalRate = plotResults.SURVIVAL_RATE.cast(SQLDataType.NUMERIC)
    val weight = plotResults.PLANT_DENSITY.cast(SQLDataType.NUMERIC)
    val weightedSumOfSquares = DSL.sum(weight * survivalRate * survivalRate)
    val weightedSum = DSL.sum(weight * survivalRate)
    val weightedSumSquared = weightedSum * weightedSum
    val totalWeight = DSL.sum(weight)
    val totalWeightSquared = totalWeight * totalWeight
    val variance =
        ((weightedSumOfSquares * totalWeight) - weightedSumSquared).div(totalWeightSquared)
    val standardDeviation = DSL.cast(DSL.sqrt(variance), SQLDataType.INTEGER)

    return DSL.field(
        DSL.select(
                DSL.if_(
                    totalWeight.eq(BigDecimal.ZERO),
                    DSL.castNull(SQLDataType.INTEGER),
                    standardDeviation,
                )
            )
            .from(plotResults)
            .where(updateScope.observationPlotResultsCondition(plotResults))
            .and(plotResults.SURVIVAL_RATE.isNotNull)
    )
  }

  private fun getSurvivalRate(numerator: Field<Int>, denominator: Field<BigDecimal>) =
      DSL.if_(
          denominator.eq(BigDecimal.ZERO),
          DSL.zero(),
          numerator.mul(100).div(denominator),
      )

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

  private fun <T> withLockedSurvivalRateCalculation(plantingSiteId: PlantingSiteId, func: () -> T) =
      dslContext.transactionResult { _ ->
        // Lock the always-present planting_sites row as the mutex. Locking the
        // planting_site_survival_rate_calculations row would not serialize concurrent first-events
        // for a site, because SELECT ... FOR UPDATE locks nothing when that row does not yet exist.
        dslContext
            .selectOne()
            .from(PLANTING_SITES)
            .where(PLANTING_SITES.ID.eq(plantingSiteId))
            .forUpdate()
            .execute()

        func()
      }

  private fun survivalRateCalculationInProgress(plantingSiteId: PlantingSiteId) =
      with(PLANTING_SITE_SURVIVAL_RATE_CALCULATIONS) {
        dslContext.fetchExists(
            DSL.selectOne().from(this).where(PLANTING_SITE_ID.eq(plantingSiteId))
        )
      }

  private fun survivalRateAdditionalCalculationRequested(plantingSiteId: PlantingSiteId) =
      with(PLANTING_SITE_SURVIVAL_RATE_CALCULATIONS) {
        dslContext
            .select(ADDITIONAL_CALCULATION_REQUESTED)
            .from(this)
            .where(PLANTING_SITE_ID.eq(plantingSiteId))
            .fetchOne(ADDITIONAL_CALCULATION_REQUESTED) ?: false
      }

  private fun insertSurvivalRateCalculation(plantingSiteId: PlantingSiteId) =
      with(PLANTING_SITE_SURVIVAL_RATE_CALCULATIONS) {
        dslContext.insertInto(this).set(PLANTING_SITE_ID, plantingSiteId).execute()
      }

  private fun deleteSurvivalRateCalculation(plantingSiteId: PlantingSiteId) =
      with(PLANTING_SITE_SURVIVAL_RATE_CALCULATIONS) {
        dslContext.deleteFrom(this).where(PLANTING_SITE_ID.eq(plantingSiteId)).execute()
      }

  private fun setSurvivalRateAdditionalCalculationRequested(
      plantingSiteId: PlantingSiteId,
      additionalCalculationRequested: Boolean,
  ): Int =
      with(PLANTING_SITE_SURVIVAL_RATE_CALCULATIONS) {
        dslContext
            .update(this)
            .set(ADDITIONAL_CALCULATION_REQUESTED, additionalCalculationRequested)
            .where(PLANTING_SITE_ID.eq(plantingSiteId))
            .execute()
      }

  data class RecordedSpeciesKey(
      val certainty: RecordedSpeciesCertainty,
      val id: SpeciesId?,
      val name: String?,
  )
}
