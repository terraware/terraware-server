package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.EntityLocker
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.StratumId
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
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_DEPENDENT_SUBSTRATA
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
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_PLANTS
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.log.withMDC
import com.terraformation.backend.tracking.event.MonitoringSpeciesTotalsEditedEvent
import com.terraformation.backend.tracking.event.ObservationCompletedEvent
import com.terraformation.backend.tracking.event.ObservationPlotCoordinatesEditedEvent
import com.terraformation.backend.tracking.event.ObservationPlotCoordinatesEditedEventValues
import com.terraformation.backend.tracking.event.ObservationPlotEditedEvent
import com.terraformation.backend.tracking.event.ObservationStateUpdatedEvent
import com.terraformation.backend.tracking.model.EditableMonitoringSpeciesModel
import com.terraformation.backend.tracking.model.EditableObservationPlotDetailsModel
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.NewObservedPlotCoordinatesModel
import com.terraformation.backend.tracking.model.ObservationModel
import com.terraformation.backend.tracking.util.ObservationSpeciesPlot
import com.terraformation.backend.tracking.util.ObservationSpeciesScope
import com.terraformation.backend.util.HECTARES_PER_PLOT
import com.terraformation.backend.util.eqOrIsNull
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneOffset
import org.jooq.CaseWhenStep
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.locationtech.jts.geom.Point
import org.springframework.context.ApplicationEventPublisher

@Named
class ObservationStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val entityLocker: EntityLocker,
    private val eventPublisher: ApplicationEventPublisher,
    private val observationResultsInvalidator: ObservationResultsInvalidator,
    private val observationsDao: ObservationsDao,
    private val observationPlotConditionsDao: ObservationPlotConditionsDao,
    private val observationPlotsDao: ObservationPlotsDao,
    private val observationRequestedSubstrataDao: ObservationRequestedSubstrataDao,
    private val parentStore: ParentStore,
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
    } else if (!newModel.isAdHoc) {
      throw IllegalArgumentException("Must request at least one substratum for observation")
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

    entityLocker.withLockedObservation(observationId) { _ ->
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

    entityLocker.withLockedObservation(observationId) { observation ->
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

    return entityLocker.withLockedObservation(observationId) { observation ->
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
          substratumId,
          plantingSiteId,
      ) = dslContext
          .select(
              OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID.asNonNullable(),
              OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.SUBSTRATUM_ID,
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

      if (
          plants.isNotEmpty() &&
              fetchObservationType(observationId) == ObservationType.BiomassMeasurements
      ) {
        throw IllegalArgumentException("Biomass observations must not contain plants")
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

      observationPlotsDao.update(
          observationPlotsRow.copy(
              completedBy = currentUser().userId,
              completedTime = clock.instant(),
              notes = notes,
              observedTime = observedTime,
              statusId = ObservationPlotStatus.Completed,
          )
      )

      if (!isAdHoc) {
        recordSubstratumDependencies(observationId)
      }

      updateSpeciesTotals(
          observationId,
          plantingSite,
          monitoringPlotId,
          monitoringPlotHistoryId,
          observationPlotsRow.isPermanent!!,
          plantCountsBySpecies,
      )

      observationResultsInvalidator.invalidateObservationPlots(
          observationId,
          listOf(monitoringPlotId),
      )

      if (!isAdHoc && substratumId != null) {
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
        completeObservation(observationId, isAdHoc)
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
      otherSpeciesName: String,
      speciesId: SpeciesId,
  ) {
    val observationPlotDetails =
        dslContext
            .select(
                OBSERVATION_PLOTS.MONITORING_PLOT_ID,
                OBSERVATION_PLOTS.IS_PERMANENT,
                MONITORING_PLOT_HISTORIES.ID,
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
    // species in this observation. The aggregate statistics are rebuilt from the plot-level ones
    // by the results recalculation job.
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
      val monitoringPlotId = plotTotal.monitoringPlotId!!
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

      // Add the plot-level live/dead/existing counts to the target species.
      updateSpeciesTotals(
          observationId,
          plantingSite,
          monitoringPlotId,
          plotDetails[MONITORING_PLOT_HISTORIES.ID]!!,
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

    observationResultsInvalidator.invalidateObservationPlots(
        observationId,
        plotTotals.map { it.monitoringPlotId!! },
    )
  }

  /**
   * Returns the most recent completion time of an assigned Plant Monitoring observation at a site,
   * or null if there isn't one or if one is upcoming or in progress. Ad hoc and biomass
   * observations are ignored.
   */
  fun fetchLastCompletedObservationTime(plantingSiteId: PlantingSiteId): Instant? {
    return dslContext
        .select(DSL.max(OBSERVATIONS.COMPLETED_TIME))
        .from(OBSERVATIONS)
        .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
        .and(isAssignedPlantMonitoringObservationCondition)
        .andNotExists(
            DSL.selectOne()
                .from(OBSERVATIONS)
                .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
                .and(isAssignedPlantMonitoringObservationCondition)
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

    entityLocker.withLockedObservation(observationId) { observation ->
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

    entityLocker.withLockedObservation(observationId) { observation ->
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
          }
              ?: if (
                  speciesId == null ||
                      parentStore.getOrganizationId(speciesId) ==
                          parentStore.getOrganizationId(observationId)
              ) {
                EditableMonitoringSpeciesModel(0, 0, 0)
              } else {
                throw SpeciesNotFoundException(speciesId)
              }

      val updated = updateFunc(existing)

      if (updated != existing) {
        // We need to update the totals for the stratum and substratum the plot was in at the time
        // of the observation, which might not be where it currently is.
        val (
            isPermanent,
            monitoringPlotHistoryId,
        ) = dslContext
            .select(
                OBSERVATION_PLOTS.IS_PERMANENT.asNonNullable(),
                OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID.asNonNullable(),
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
            monitoringPlotId,
            monitoringPlotHistoryId,
            isPermanent,
            plantCountAdjustments,
        )

        observationResultsInvalidator.invalidateObservationPlots(
            observationId,
            listOf(monitoringPlotId),
        )

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

    val organizationId =
        parentStore.getOrganizationId(observationId)
            ?: throw ObservationNotFoundException(observationId)
    val plantingSiteId =
        parentStore.getPlantingSiteId(monitoringPlotId)
            ?: throw PlotNotFoundException(monitoringPlotId)

    dslContext.transaction { _ ->
      val existingCoordinates =
          dslContext
              .selectFrom(OBSERVED_PLOT_COORDINATES)
              .where(OBSERVED_PLOT_COORDINATES.OBSERVATION_ID.eq(observationId))
              .and(OBSERVED_PLOT_COORDINATES.MONITORING_PLOT_ID.eq(monitoringPlotId))
              .fetch()

      val coordinatesToDelete = existingCoordinates.filter { existing ->
        coordinates.none {
          it.position == existing.positionId && it.gpsCoordinates == existing.gpsCoordinates
        }
      }
      val coordinatesToInsert = coordinates.filter { desired ->
        existingCoordinates.none {
          it.positionId == desired.position && it.gpsCoordinates == desired.gpsCoordinates
        }
      }

      if (coordinatesToDelete.isNotEmpty()) {
        val coordinateIdsToDelete = coordinatesToDelete.map { it.id!! }

        dslContext
            .deleteFrom(OBSERVED_PLOT_COORDINATES)
            .where(OBSERVED_PLOT_COORDINATES.ID.`in`(coordinateIdsToDelete))
            .execute()

        coordinatesToDelete
            .filter { existing ->
              coordinates.none { desired -> existing.positionId == desired.position }
            }
            .forEach { existing ->
              eventPublisher.publishEvent(
                  ObservationPlotCoordinatesEditedEvent(
                      ObservationPlotCoordinatesEditedEventValues(existing.gpsCoordinates as Point),
                      ObservationPlotCoordinatesEditedEventValues(null),
                      monitoringPlotId,
                      observationId,
                      organizationId,
                      plantingSiteId,
                      existing.positionId!!,
                  )
              )
            }
      }

      coordinatesToInsert.forEach { desired ->
        dslContext
            .insertInto(OBSERVED_PLOT_COORDINATES)
            .set(OBSERVED_PLOT_COORDINATES.OBSERVATION_ID, observationId)
            .set(OBSERVED_PLOT_COORDINATES.MONITORING_PLOT_ID, monitoringPlotId)
            .set(OBSERVED_PLOT_COORDINATES.POSITION_ID, desired.position)
            .set(OBSERVED_PLOT_COORDINATES.GPS_COORDINATES, desired.gpsCoordinates)
            .execute()

        val existing = existingCoordinates.firstOrNull { it.positionId == desired.position }

        eventPublisher.publishEvent(
            ObservationPlotCoordinatesEditedEvent(
                ObservationPlotCoordinatesEditedEventValues(existing?.gpsCoordinates as? Point),
                ObservationPlotCoordinatesEditedEventValues(desired.gpsCoordinates),
                monitoringPlotId,
                observationId,
                organizationId,
                plantingSiteId,
                desired.position,
            )
        )
      }
    }
  }

  fun hasPlantMonitoringObservations(plantingSiteId: PlantingSiteId): Boolean {
    return dslContext.fetchExists(
        OBSERVATIONS,
        OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId),
        isAssignedPlantMonitoringObservationCondition,
    )
  }

  private val isAssignedPlantMonitoringObservationCondition =
      OBSERVATIONS.OBSERVATION_TYPE_ID.eq(ObservationType.Monitoring)
          .and(OBSERVATIONS.IS_AD_HOC.isFalse)

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
        recordSubstratumDependencies(observationId)
        observationResultsInvalidator.invalidateObservation(observationId)
      }
    } else {
      log.info("Deleting abandoned observation $observationId since it has no completed plots")
      deleteObservation(observationId)
    }
  }

  private fun completeObservation(
      observationId: ObservationId,
      isAdHoc: Boolean = false,
  ) {
    updateObservationState(observationId, ObservationState.Completed)
    if (!isAdHoc) {
      recordSubstratumDependencies(observationId)
      observationResultsInvalidator.invalidateObservation(observationId)
    }

    eventPublisher.publishEvent(ObservationCompletedEvent(observationId))
  }

  private fun computeLatestObservationForSubstratumField(
      observationId: ObservationId,
      substratumIdField: Field<SubstratumId?>,
  ): Field<ObservationId?> {
    val candidateObs = OBSERVATIONS.`as`("candidate_obs")
    val op = OBSERVATION_PLOTS.`as`("dep_op")
    val mph = MONITORING_PLOT_HISTORIES.`as`("dep_mph")

    return DSL.field(
        DSL.select(candidateObs.ID)
            .from(candidateObs)
            .whereExists(
                DSL.selectOne()
                    .from(op)
                    .join(mph)
                    .on(mph.ID.eq(op.MONITORING_PLOT_HISTORY_ID))
                    .where(op.OBSERVATION_ID.eq(candidateObs.ID))
                    .and(op.COMPLETED_TIME.isNotNull)
                    .and(mph.SUBSTRATUM_ID.eq(substratumIdField))
            )
            .and(
                DSL.or(
                    candidateObs.ID.eq(observationId),
                    candidateObs.COMPLETED_TIME.le(
                        DSL.select(OBSERVATIONS.COMPLETED_TIME)
                            .from(OBSERVATIONS)
                            .where(OBSERVATIONS.ID.eq(observationId))
                    ),
                )
            )
            .and(candidateObs.IS_AD_HOC.isFalse)
            .and(candidateObs.OBSERVATION_TYPE_ID.eq(ObservationType.Monitoring))
            .orderBy(
                // Prefer results from the same observation as the `observationId` if it exists.
                candidateObs.ID.eq(observationId).desc(),
                // Otherwise take the results from the next latest observation for that area.
                candidateObs.COMPLETED_TIME.desc(),
            )
            .limit(1)
    )
  }

  private fun recordSubstratumDependencies(observationId: ObservationId) {
    val observationType = fetchObservationType(observationId)
    if (observationType != ObservationType.Monitoring) {
      return
    }

    val consumingSsh = SUBSTRATUM_HISTORIES.`as`("consuming_ssh")
    val consumingSh = STRATUM_HISTORIES.`as`("consuming_sh")
    val srcSsh = SUBSTRATUM_HISTORIES.`as`("src_ssh")
    val srcSh = STRATUM_HISTORIES.`as`("src_sh")
    val srcObs = OBSERVATIONS.`as`("src_obs")

    val dependsOnObservationId =
        computeLatestObservationForSubstratumField(
            observationId,
            consumingSsh.SUBSTRATUM_ID,
        )

    val dependsOnSubstratumHistoryId =
        DSL.field(
            DSL.select(srcSsh.ID)
                .from(srcObs)
                .join(srcSh)
                .on(srcSh.PLANTING_SITE_HISTORY_ID.eq(srcObs.PLANTING_SITE_HISTORY_ID))
                .join(srcSsh)
                .on(srcSsh.STRATUM_HISTORY_ID.eq(srcSh.ID))
                .where(srcObs.ID.eq(dependsOnObservationId))
                .and(srcSsh.SUBSTRATUM_ID.eq(consumingSsh.SUBSTRATUM_ID))
        )

    dslContext
        .deleteFrom(OBSERVATION_DEPENDENT_SUBSTRATA)
        .where(OBSERVATION_DEPENDENT_SUBSTRATA.OBSERVATION_ID.eq(observationId))
        .execute()

    dslContext
        .insertInto(
            OBSERVATION_DEPENDENT_SUBSTRATA,
            OBSERVATION_DEPENDENT_SUBSTRATA.OBSERVATION_ID,
            OBSERVATION_DEPENDENT_SUBSTRATA.SUBSTRATUM_HISTORY_ID,
            OBSERVATION_DEPENDENT_SUBSTRATA.DEPENDS_ON_OBSERVATION_ID,
            OBSERVATION_DEPENDENT_SUBSTRATA.DEPENDS_ON_SUBSTRATUM_HISTORY_ID,
        )
        .select(
            DSL.select(
                    DSL.value(observationId, OBSERVATIONS.ID),
                    consumingSsh.ID,
                    dependsOnObservationId,
                    dependsOnSubstratumHistoryId,
                )
                .from(OBSERVATIONS)
                .join(consumingSh)
                .on(consumingSh.PLANTING_SITE_HISTORY_ID.eq(OBSERVATIONS.PLANTING_SITE_HISTORY_ID))
                .join(consumingSsh)
                .on(consumingSsh.STRATUM_HISTORY_ID.eq(consumingSh.ID))
                .where(OBSERVATIONS.ID.eq(observationId))
                .and(dependsOnObservationId.isNotNull)
        )
        .execute()
  }

  /**
   * Populates the `observation_*_results` tables for an observation whose plot completions predate
   * those tables existing, treating `observed_plot_species_totals` as the source of truth. The
   * results are filled in by the results recalculation job.
   */
  fun populateObservationResults(observationId: ObservationId) {
    requirePermissions { manageObservation(observationId) }

    val observation = fetchObservationById(observationId)

    dslContext.transaction { _ ->
      if (!observation.isAdHoc) {
        recordSubstratumDependencies(observationId)
      }

      observationResultsInvalidator.invalidateObservation(observationId)
    }
  }

  fun fetchSurvivalRateCalculationInProgress(plantingSiteId: PlantingSiteId): Boolean {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return observationResultsInvalidator.plantingSiteNeedsRecalculation(plantingSiteId)
  }

  /**
   * Returns the IDs of a planting site's monitoring plots that have been included in observations
   * as permanent plots. Includes plots from observations that haven't been completed.
   */
  /**
   * Merges the observation data from a source observation to a target.
   *
   * You probably want `ObservationService.mergeObservations` instead of this.
   *
   * Moves the raw per-plot data of completed plots from the source observation to the target
   * observation, repoints/drops t0 baselines, and deletes the source observation. Does NOT delete
   * media (the caller handles colliding target media) and does NOT rebuild derived aggregates (call
   * [recalculateObservationTotals] afterward).
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

    entityLocker.withLockedObservation(idsToLock[0]) {
      entityLocker.withLockedObservation(idsToLock[1]) {
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

        val dependentsOfSource =
            dslContext
                .selectDistinct(OBSERVATION_DEPENDENT_SUBSTRATA.OBSERVATION_ID)
                .from(OBSERVATION_DEPENDENT_SUBSTRATA)
                .where(
                    OBSERVATION_DEPENDENT_SUBSTRATA.DEPENDS_ON_OBSERVATION_ID.eq(
                        sourceObservationId
                    )
                )
                .and(OBSERVATION_DEPENDENT_SUBSTRATA.OBSERVATION_ID.ne(sourceObservationId))
                .fetch(OBSERVATION_DEPENDENT_SUBSTRATA.OBSERVATION_ID.asNonNullable())

        dslContext.deleteFrom(OBSERVATIONS).where(OBSERVATIONS.ID.eq(sourceObservationId)).execute()

        recordSubstratumDependencies(targetObservationId)

        // Recompute dependencies of downstream observations
        dependentsOfSource.forEach { recordSubstratumDependencies(it) }
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
   * Rebuilds plot-level species totals and flags results for the recalculator to rebuild
   * aggregates.
   */
  fun recalculateObservationTotals(observationId: ObservationId) {
    requirePermissions { manageObservation(observationId) }

    val plantingSiteId = fetchObservationById(observationId).plantingSiteId
    val plantingSite =
        dslContext
            .select()
            .from(PLANTING_SITES)
            .where(PLANTING_SITES.ID.eq(plantingSiteId))
            .fetchOneInto(PlantingSitesRow::class.java)
            ?: throw PlantingSiteNotFoundException(plantingSiteId)

    entityLocker.withLockedObservation(observationId) {
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

        val plantCounts = allPlantCounts[monitoringPlotId] ?: emptyMap()

        updateSpeciesTotals(
            observationId,
            plantingSite,
            monitoringPlotId,
            monitoringPlotHistoryId,
            isPermanent,
            plantCounts,
        )
      }

      observationResultsInvalidator.invalidateObservation(observationId)
    }
  }

  fun deleteObservation(observationId: ObservationId) {
    val t0PlotIds =
        dslContext
            .select(PLOT_T0_OBSERVATIONS.MONITORING_PLOT_ID)
            .from(PLOT_T0_OBSERVATIONS)
            .where(PLOT_T0_OBSERVATIONS.OBSERVATION_ID.eq(observationId))
            .fetch(PLOT_T0_OBSERVATIONS.MONITORING_PLOT_ID.asNonNullable())

    val dependentObservationIds =
        dslContext
            .selectDistinct(OBSERVATION_DEPENDENT_SUBSTRATA.OBSERVATION_ID)
            .from(OBSERVATION_DEPENDENT_SUBSTRATA)
            .where(OBSERVATION_DEPENDENT_SUBSTRATA.DEPENDS_ON_OBSERVATION_ID.eq(observationId))
            .and(OBSERVATION_DEPENDENT_SUBSTRATA.OBSERVATION_ID.ne(observationId))
            .fetch(OBSERVATION_DEPENDENT_SUBSTRATA.OBSERVATION_ID.asNonNullable())

    dslContext.transaction { _ ->
      if (t0PlotIds.isNotEmpty()) {
        dslContext
            .deleteFrom(PLOT_T0_DENSITIES)
            .where(PLOT_T0_DENSITIES.MONITORING_PLOT_ID.`in`(t0PlotIds))
            .execute()
      }

      dslContext.deleteFrom(OBSERVATIONS).where(OBSERVATIONS.ID.eq(observationId)).execute()

      // Recompute dependencies of downstream observations
      dependentObservationIds.forEach { recordSubstratumDependencies(it) }
    }
  }

  private fun fetchObservationType(observationId: ObservationId): ObservationType =
      dslContext.fetchValue(
          OBSERVATIONS.OBSERVATION_TYPE_ID,
          OBSERVATIONS.ID.eq(observationId),
      ) ?: throw ObservationNotFoundException(observationId)

  /**
   * Deletes incomplete plots from an observation. Optionally retains permanent plots that weren't
   * observed in a list of other observations.
   *
   * @param dryRun If true, calculate the lists of plot IDs that would be retained and deleted, but
   *   don't actually delete anything.
   * @return The list of retained plot IDs and the list of deleted plot IDs
   */
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

  /** Updates the tables that hold the aggregated per-species plant totals from observations. */
  private fun updateSpeciesTotals(
      observationId: ObservationId,
      plantingSite: PlantingSitesRow,
      monitoringPlotId: MonitoringPlotId,
      monitoringPlotHistoryId: MonitoringPlotHistoryId,
      isPermanent: Boolean,
      plantCountsBySpecies: Map<RecordedSpeciesKey, Map<RecordedPlantStatus, Int>>,
  ) {
    if (plantCountsBySpecies.isNotEmpty()) {
      updateSpeciesTotalsTable(
          observationId,
          isPermanent,
          plantCountsBySpecies,
          plantingSite,
          ObservationSpeciesPlot(monitoringPlotId, monitoringPlotHistoryId),
      )
    }
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
                .setNull(survivalRateField)
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
                "scope" to updateScope.scopeHistoryId,
                "species" to speciesKey,
            ) {
              log.error("BUG! Insert and update of species totals both failed")
            }
          }
        }
      }

      // The survival rate is computed from the per-plot totals of the plots that have t0 data, so
      // it can only be calculated once this plot's totals rows are in place. A temporary plot only
      // affects survival rates if the planting site includes temporary plots in them.
      if (speciesIds.isNotEmpty() && (includesTempPlots || isPermanent)) {
        val plotObservationCondition = latestObservationForPlotCondition(observationIdValue)
        val permanentTerms =
            getSurvivalRateTermsBySpecies(
                permanentT0PlotSet(updateScope, null, plotObservationCondition),
                speciesIds,
            )
        val tempTerms =
            if (includesTempPlots) {
              getSurvivalRateTermsBySpecies(
                  tempT0PlotSet(updateScope, null, plotObservationCondition),
                  speciesIds,
              )
            } else {
              emptyMap()
            }

        var survivalRateBySpecies: CaseWhenStep<SpeciesId?, Int>? = null

        speciesIds.forEach { speciesId ->
          val permanent = permanentTerms[speciesId]
          val temp = tempTerms[speciesId]
          val denominator =
              when {
                permanent != null && temp != null -> permanent.denominator + temp.denominator
                permanent != null -> permanent.denominator
                else -> temp?.denominator
              }
          val numerator = (permanent?.numerator ?: 0) + (temp?.numerator ?: 0)
          val survivalRate = survivalRateValue(DSL.value(numerator), denominator)

          survivalRateBySpecies =
              survivalRateBySpecies?.`when`(speciesId, survivalRate)
                  ?: DSL.case_(speciesIdField).`when`(speciesId, survivalRate)
        }

        dslContext
            .update(table)
            .set(survivalRateField, survivalRateBySpecies!!)
            .where(observationIdField.eq(observationId))
            .and(updateScope.observedTotalsScopeHistoryField.eq(updateScope.scopeHistoryId))
            .and(speciesIdField.`in`(speciesIds))
            .execute()
      }
    }
  }

  private data class SurvivalRateTerms(val denominator: BigDecimal, val numerator: Int)

  /**
   * Returns the survival-rate denominator (cumulative t0 plant density) and numerator (live plants
   * in plots that have t0 data) for each of the requested species in a t0 plot set.
   */
  private fun getSurvivalRateTermsBySpecies(
      plotSet: T0PlotSet,
      speciesIds: Set<SpeciesId>,
  ): Map<SpeciesId, SurvivalRateTerms> {
    val denominatorField = DSL.sum(plotSet.densityField).mul(DSL.inline(HECTARES_PER_PLOT))
    val numeratorField = DSL.sum(plotSet.liveField).cast(SQLDataType.INTEGER)

    return dslContext
        .select(plotSet.speciesIdField, denominatorField, numeratorField)
        .from(plotSet.table)
        .where(plotSet.condition)
        .and(plotSet.speciesIdField.`in`(speciesIds))
        .groupBy(plotSet.speciesIdField)
        .fetch()
        .mapNotNull { record ->
          val speciesId = record.value1()
          val denominator = record.value2()
          if (speciesId != null && denominator != null) {
            speciesId to SurvivalRateTerms(denominator, record.value3() ?: 0)
          } else {
            null
          }
        }
        .toMap()
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

  data class RecordedSpeciesKey(
      val certainty: RecordedSpeciesCertainty,
      val id: SpeciesId?,
      val name: String?,
  )
}
