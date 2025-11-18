package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.attach
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesIdConverter
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationIdConverter
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.RecordedSpeciesCertaintyConverter
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotConditionsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationRequestedSubzonesDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationsDao
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotConditionsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationRequestedSubzonesRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassDetailsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadratDetailsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadratSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.ObservedPlotSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.RecordedPlantsRecord
import com.terraformation.backend.db.tracking.tables.records.RecordedTreesRecord
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_REQUESTED_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_COORDINATES
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_ZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_T0_TEMP_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_PLANTS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.log.withMDC
import com.terraformation.backend.tracking.event.BiomassDetailsCreatedEvent
import com.terraformation.backend.tracking.event.BiomassDetailsUpdatedEvent
import com.terraformation.backend.tracking.event.BiomassDetailsUpdatedEventValues
import com.terraformation.backend.tracking.event.BiomassQuadratCreatedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratDetailsUpdatedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratDetailsUpdatedEventValues
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.ObservationStateUpdatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeCreatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeUpdatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeUpdatedEventValues
import com.terraformation.backend.tracking.event.T0PlotDataAssignedEvent
import com.terraformation.backend.tracking.event.T0ZoneDataAssignedEvent
import com.terraformation.backend.tracking.model.AssignedPlotDetails
import com.terraformation.backend.tracking.model.BiomassSpeciesKey
import com.terraformation.backend.tracking.model.EditableBiomassDetailsModel
import com.terraformation.backend.tracking.model.EditableBiomassQuadratDetailsModel
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.ExistingRecordedTreeModel
import com.terraformation.backend.tracking.model.NewBiomassDetailsModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.NewObservedPlotCoordinatesModel
import com.terraformation.backend.tracking.model.ObservationModel
import com.terraformation.backend.tracking.model.ObservationPlotCounts
import com.terraformation.backend.tracking.model.ObservationPlotModel
import com.terraformation.backend.tracking.model.RecordedTreeModel
import com.terraformation.backend.tracking.util.ObservationSpeciesPlot
import com.terraformation.backend.tracking.util.ObservationSpeciesScope
import com.terraformation.backend.tracking.util.ObservationSpeciesSite
import com.terraformation.backend.tracking.util.ObservationSpeciesSubzone
import com.terraformation.backend.tracking.util.ObservationSpeciesZone
import com.terraformation.backend.util.HECTARES_PER_PLOT
import com.terraformation.backend.util.eqOrIsNull
import com.terraformation.backend.util.nullIfEquals
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@Named
class ObservationStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val observationsDao: ObservationsDao,
    private val observationPlotConditionsDao: ObservationPlotConditionsDao,
    private val observationPlotsDao: ObservationPlotsDao,
    private val observationRequestedSubzonesDao: ObservationRequestedSubzonesDao,
    private val parentStore: ParentStore,
) {
  private val requestedSubzoneIdsField: Field<Set<PlantingSubzoneId>> =
      with(OBSERVATION_REQUESTED_SUBZONES) {
        DSL.multiset(
                DSL.select(PLANTING_SUBZONE_ID)
                    .from(OBSERVATION_REQUESTED_SUBZONES)
                    .where(OBSERVATION_ID.eq(OBSERVATIONS.ID))
            )
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
            OBSERVATION_PLOTS.monitoringPlots.plantingSubzones.FULL_NAME,
            OBSERVATION_PLOTS.monitoringPlots.plantingSubzones.ID,
            OBSERVATION_PLOTS.monitoringPlots.plantingSubzones.plotsPlantingZoneIdFkey.NAME,
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
              plantingSubzoneId = record[OBSERVATION_PLOTS.monitoringPlots.plantingSubzones.ID]!!,
              plantingSubzoneName =
                  record[OBSERVATION_PLOTS.monitoringPlots.plantingSubzones.FULL_NAME]!!,
              plantingZoneName =
                  record[
                      OBSERVATION_PLOTS.monitoringPlots.plantingSubzones.plotsPlantingZoneIdFkey
                          .NAME]!!,
              plotNumber = record[OBSERVATION_PLOTS.monitoringPlots.PLOT_NUMBER]!!,
              sizeMeters = record[OBSERVATION_PLOTS.monitoringPlots.SIZE_METERS]!!,
          )
        }
  }

  /** Evaluates to true if an observation has requested subzones. */
  private val observationHasRequestedSubzones: Condition =
      DSL.exists(
          DSL.selectOne()
              .from(OBSERVATION_REQUESTED_SUBZONES)
              .where(OBSERVATION_REQUESTED_SUBZONES.OBSERVATION_ID.eq(OBSERVATIONS.ID))
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
            observationHasRequestedSubzones,
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
            observationHasRequestedSubzones,
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

  /**
   * Returns the IDs of any active assigned observations of a planting site that include unobserved
   * plots in specific planting zones.
   */
  fun fetchActiveObservationIds(
      plantingSiteId: PlantingSiteId,
      plantingZoneIds: Collection<PlantingZoneId>,
  ): List<ObservationId> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    if (plantingZoneIds.isEmpty()) {
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
                          OBSERVATION_PLOTS.monitoringPlots.plantingSubzones.PLANTING_ZONE_ID.`in`(
                              plantingZoneIds
                          )
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

  fun fetchRecordedTree(
      observationId: ObservationId,
      recordedTreeId: RecordedTreeId,
  ): ExistingRecordedTreeModel {
    requirePermissions { readObservation(observationId) }

    return with(RECORDED_TREES) {
      dslContext
          .select(
              ID,
              DESCRIPTION,
              DIAMETER_AT_BREAST_HEIGHT_CM,
              GPS_COORDINATES,
              HEIGHT_M,
              IS_DEAD,
              POINT_OF_MEASUREMENT_M,
              SHRUB_DIAMETER_CM,
              recordedTreesBiomassSpeciesIdFkey.SPECIES_ID,
              recordedTreesBiomassSpeciesIdFkey.SCIENTIFIC_NAME,
              TREE_GROWTH_FORM_ID,
              TREE_NUMBER,
              TRUNK_NUMBER,
          )
          .from(RECORDED_TREES)
          .where(ID.eq(recordedTreeId))
          .and(OBSERVATION_ID.eq(observationId))
          .fetchOne { RecordedTreeModel.of(it) }
          ?: throw RecordedTreeNotFoundException(recordedTreeId)
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

  /**
   * Locks an observation and calls a function. Starts a database transaction; the function is
   * called with the transaction open, such that the lock is held while the function runs.
   */
  fun <T> withLockedObservation(
      observationId: ObservationId,
      func: (ExistingObservationModel) -> T,
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
      endDate: LocalDate,
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
      val (plantingSubzoneId, plantingZoneId, plantingSiteId, isAdHoc) =
          dslContext
              .select(
                  MONITORING_PLOTS.PLANTING_SUBZONE_ID,
                  MONITORING_PLOTS.plantingSubzones.PLANTING_ZONE_ID,
                  MONITORING_PLOTS.PLANTING_SITE_ID.asNonNullable(),
                  MONITORING_PLOTS.IS_AD_HOC.asNonNullable(),
              )
              .from(MONITORING_PLOTS)
              .where(MONITORING_PLOTS.ID.eq(monitoringPlotId))
              .fetchOne()!!

      // We will be calculating cumulative totals across all observations of a planting site and
      // across monitoring plots and planting zones; guard against multiple submissions arriving at
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

      val plantsRecords =
          plants.map { plantsRow ->
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
          plantingZoneId,
          plantingSubzoneId,
          monitoringPlotId,
          isAdHoc,
          observationPlotsRow.isPermanent!!,
          plantCountsBySpecies,
          cumulativeDeadFromCurrentObservation = true,
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

      if (plantingSubzoneId != null) {
        updateSubzoneObservedTime(plantingSubzoneId, observedTime)
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
      }
    }
  }

  /**
   * Updates the observed time of a planting subzone if it wasn't already observed more recently.
   */
  private fun updateSubzoneObservedTime(
      plantingSubzoneId: PlantingSubzoneId,
      observedTime: Instant,
  ) {
    with(PLANTING_SUBZONES) {
      dslContext
          .update(PLANTING_SUBZONES)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(OBSERVED_TIME, observedTime)
          .where(ID.eq(plantingSubzoneId))
          .and(OBSERVED_TIME.isNull.or(OBSERVED_TIME.lt(observedTime)))
          .execute()
    }
  }

  fun insertBiomassDetails(
      observationId: ObservationId,
      plotId: MonitoringPlotId,
      model: NewBiomassDetailsModel,
  ) {
    requirePermissions { updateObservation(observationId) }

    val plantingSiteId =
        parentStore.getPlantingSiteId(plotId) ?: throw PlotNotFoundException(plotId)
    val organizationId =
        parentStore.getOrganizationId(plantingSiteId) ?: throw PlotNotFoundException(plotId)

    val (observationType, observationState) =
        with(OBSERVATION_PLOTS) {
          dslContext
              .select(
                  observations.OBSERVATION_TYPE_ID.asNonNullable(),
                  observations.STATE_ID.asNonNullable(),
              )
              .from(this)
              .where(OBSERVATION_ID.eq(observationId))
              .and(MONITORING_PLOT_ID.eq(plotId))
              .fetchOne()
              ?: throw IllegalStateException(
                  "Plot $plotId is not part of observation $observationId"
              )
        }

    if (observationState == ObservationState.Completed) {
      throw IllegalStateException("Observation $observationId is already completed.")
    }

    if (observationType != ObservationType.BiomassMeasurements) {
      throw IllegalStateException("Observation $observationId is not a biomass measurement")
    }

    model.validate()

    dslContext.transaction { _ ->
      val observationBiomassDetailsRecord =
          ObservationBiomassDetailsRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  description = model.description,
                  forestTypeId = model.forestType,
                  smallTreesCountLow = model.smallTreeCountRange.first,
                  smallTreesCountHigh = model.smallTreeCountRange.second,
                  herbaceousCoverPercent = model.herbaceousCoverPercent,
                  soilAssessment = model.soilAssessment,
                  waterDepthCm =
                      if (model.forestType == BiomassForestType.Mangrove) model.waterDepthCm
                      else null,
                  salinityPpt =
                      if (model.forestType == BiomassForestType.Mangrove) model.salinityPpt
                      else null,
                  ph = if (model.forestType == BiomassForestType.Mangrove) model.ph else null,
                  tideId = if (model.forestType == BiomassForestType.Mangrove) model.tide else null,
                  tideTime =
                      if (model.forestType == BiomassForestType.Mangrove) model.tideTime else null,
              )
              .attach(dslContext)

      observationBiomassDetailsRecord.insert()

      eventPublisher.publishEvent(
          BiomassDetailsCreatedEvent(
              description = model.description,
              forestType = model.forestType,
              herbaceousCoverPercent = model.herbaceousCoverPercent,
              monitoringPlotId = plotId,
              observationId = observationId,
              organizationId = organizationId,
              ph = observationBiomassDetailsRecord.ph,
              plantingSiteId = plantingSiteId,
              salinityPpt = observationBiomassDetailsRecord.salinityPpt,
              smallTreesCountHigh = model.smallTreeCountRange.second,
              smallTreesCountLow = model.smallTreeCountRange.first,
              soilAssessment = model.soilAssessment,
              tide = observationBiomassDetailsRecord.tideId,
              tideTime = observationBiomassDetailsRecord.tideTime,
              waterDepthCm = observationBiomassDetailsRecord.waterDepthCm,
          )
      )

      model.species.forEach { speciesModel ->
        val record =
            ObservationBiomassSpeciesRecord(
                    observationId = observationId,
                    monitoringPlotId = plotId,
                    commonName = speciesModel.commonName,
                    isInvasive = speciesModel.isInvasive,
                    isThreatened = speciesModel.isThreatened,
                    scientificName = speciesModel.scientificName,
                    speciesId = speciesModel.speciesId,
                )
                .attach(dslContext)

        record.insert()

        eventPublisher.publishEvent(
            BiomassSpeciesCreatedEvent(
                biomassSpeciesId = record.id!!,
                commonName = speciesModel.commonName,
                isInvasive = speciesModel.isInvasive,
                isThreatened = speciesModel.isThreatened,
                monitoringPlotId = plotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                scientificName = speciesModel.scientificName,
                speciesId = speciesModel.speciesId,
            )
        )
      }

      val biomassSpeciesIdsBySpeciesIdentifiers =
          with(OBSERVATION_BIOMASS_SPECIES) {
            dslContext
                .select(ID.asNonNullable(), SCIENTIFIC_NAME, SPECIES_ID)
                .from(this)
                .where(OBSERVATION_ID.eq(observationId))
                .fetch()
                .associate {
                  BiomassSpeciesKey(it[SPECIES_ID], it[SCIENTIFIC_NAME]) to it[ID.asNonNullable()]
                }
          }

      model.quadrats.forEach { (position, details) ->
        val record =
            ObservationBiomassQuadratDetailsRecord(
                    observationId = observationId,
                    monitoringPlotId = plotId,
                    positionId = position,
                    description = details.description,
                )
                .attach(dslContext)

        record.insert()

        eventPublisher.publishEvent(
            BiomassQuadratCreatedEvent(
                description = details.description,
                monitoringPlotId = plotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                position = position,
            )
        )
      }

      val quadratSpeciesRecords =
          model.quadrats.flatMap { (position, details) ->
            details.species.map {
              ObservationBiomassQuadratSpeciesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = position,
                  biomassSpeciesId =
                      biomassSpeciesIdsBySpeciesIdentifiers[
                          BiomassSpeciesKey(it.speciesId, it.speciesName)]
                          ?: throw IllegalArgumentException(
                              "Biomass species ${it.speciesName ?: "#${it.speciesId}"} not found."
                          ),
                  abundancePercent = it.abundancePercent,
              )
            }
          }
      dslContext.batchInsert(quadratSpeciesRecords).execute()

      model.trees.forEach { treeModel ->
        val record =
            RecordedTreesRecord(
                    observationId = observationId,
                    monitoringPlotId = plotId,
                    biomassSpeciesId =
                        biomassSpeciesIdsBySpeciesIdentifiers[
                            BiomassSpeciesKey(treeModel.speciesId, treeModel.speciesName)]
                            ?: throw IllegalArgumentException(
                                "Biomass species ${treeModel.speciesName ?: "#${treeModel.speciesId}"} not found."
                            ),
                    treeNumber = treeModel.treeNumber,
                    trunkNumber = treeModel.trunkNumber,
                    treeGrowthFormId = treeModel.treeGrowthForm,
                    gpsCoordinates = treeModel.gpsCoordinates,
                    isDead = treeModel.isDead,
                    diameterAtBreastHeightCm =
                        if (
                            treeModel.treeGrowthForm == TreeGrowthForm.Tree ||
                                treeModel.treeGrowthForm == TreeGrowthForm.Trunk
                        )
                            treeModel.diameterAtBreastHeightCm
                        else null,
                    pointOfMeasurementM =
                        if (
                            treeModel.treeGrowthForm == TreeGrowthForm.Tree ||
                                treeModel.treeGrowthForm == TreeGrowthForm.Trunk
                        )
                            treeModel.pointOfMeasurementM
                        else null,
                    heightM =
                        if (
                            treeModel.treeGrowthForm == TreeGrowthForm.Tree ||
                                treeModel.treeGrowthForm == TreeGrowthForm.Trunk
                        )
                            treeModel.heightM
                        else null,
                    shrubDiameterCm =
                        if (treeModel.treeGrowthForm == TreeGrowthForm.Shrub)
                            treeModel.shrubDiameterCm
                        else null,
                    description = treeModel.description,
                )
                .attach(dslContext)

        record.insert()

        eventPublisher.publishEvent(
            RecordedTreeCreatedEvent(
                biomassSpeciesId = record.biomassSpeciesId!!,
                description = treeModel.description,
                diameterAtBreastHeightCm = record.diameterAtBreastHeightCm,
                gpsCoordinates = treeModel.gpsCoordinates,
                heightM = record.heightM,
                isDead = treeModel.isDead,
                monitoringPlotId = plotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                pointOfMeasurementM = record.pointOfMeasurementM,
                recordedTreeId = record.id!!,
                shrubDiameterCm = record.shrubDiameterCm,
                speciesId = treeModel.speciesId,
                speciesName = treeModel.speciesName,
                treeGrowthForm = treeModel.treeGrowthForm,
                treeNumber = treeModel.treeNumber,
                trunkNumber = treeModel.trunkNumber,
            )
        )
      }
    }
  }

  fun updateBiomassDetails(
      observationId: ObservationId,
      plotId: MonitoringPlotId,
      updateFunc: (EditableBiomassDetailsModel) -> EditableBiomassDetailsModel,
  ) {
    requirePermissions { updateObservation(observationId) }

    withLockedObservation(observationId) { _ ->
      val existing =
          with(OBSERVATION_BIOMASS_DETAILS) {
            dslContext
                .select(DESCRIPTION, SOIL_ASSESSMENT)
                .from(OBSERVATION_BIOMASS_DETAILS)
                .where(OBSERVATION_ID.eq(observationId))
                .and(MONITORING_PLOT_ID.eq(plotId))
                .fetchOne { EditableBiomassDetailsModel.of(it) }
                ?: throw ObservationPlotNotFoundException(observationId, plotId)
          }

      val updated = updateFunc(existing)

      val changedFrom =
          BiomassDetailsUpdatedEventValues(
              description = existing.description.nullIfEquals(updated.description),
              soilAssessment = existing.soilAssessment.nullIfEquals(updated.soilAssessment),
          )
      val changedTo =
          BiomassDetailsUpdatedEventValues(
              description = updated.description.nullIfEquals(existing.description),
              soilAssessment = updated.soilAssessment.nullIfEquals(existing.soilAssessment),
          )

      if (changedFrom != changedTo) {
        with(OBSERVATION_BIOMASS_DETAILS) {
          dslContext
              .update(OBSERVATION_BIOMASS_DETAILS)
              .set(DESCRIPTION, updated.description)
              .set(SOIL_ASSESSMENT, updated.soilAssessment)
              .where(OBSERVATION_ID.eq(observationId))
              .and(MONITORING_PLOT_ID.eq(plotId))
              .execute()

          val (plantingSiteId, organizationId) =
              dslContext
                  .select(
                      monitoringPlots.plantingSites.ID.asNonNullable(),
                      monitoringPlots.plantingSites.ORGANIZATION_ID.asNonNullable(),
                  )
                  .from(OBSERVATION_BIOMASS_DETAILS)
                  .where(OBSERVATION_ID.eq(observationId))
                  .and(MONITORING_PLOT_ID.eq(plotId))
                  .fetchSingle()

          eventPublisher.publishEvent(
              BiomassDetailsUpdatedEvent(
                  changedFrom = changedFrom,
                  changedTo = changedTo,
                  monitoringPlotId = plotId,
                  observationId = observationId,
                  organizationId = organizationId,
                  plantingSiteId = plantingSiteId,
              )
          )
        }
      }
    }
  }

  fun updateBiomassQuadratDetails(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      position: ObservationPlotPosition,
      updateFunc: (EditableBiomassQuadratDetailsModel) -> EditableBiomassQuadratDetailsModel,
  ) {
    requirePermissions { updateObservation(observationId) }

    val organizationId =
        parentStore.getOrganizationId(observationId)
            ?: throw ObservationNotFoundException(observationId)
    val plantingSiteId =
        parentStore.getPlantingSiteId(observationId)
            ?: throw ObservationNotFoundException(observationId)

    withLockedObservation(observationId) { _ ->
      val existing =
          with(OBSERVATION_BIOMASS_QUADRAT_DETAILS) {
            dslContext
                .select(DESCRIPTION)
                .from(OBSERVATION_BIOMASS_QUADRAT_DETAILS)
                .where(OBSERVATION_ID.eq(observationId))
                .and(MONITORING_PLOT_ID.eq(monitoringPlotId))
                .and(POSITION_ID.eq(position))
                .fetchOne { EditableBiomassQuadratDetailsModel.of(it) }
          }

      val editable = existing ?: EditableBiomassQuadratDetailsModel()
      val updated = updateFunc(editable)

      val changedFrom =
          BiomassQuadratDetailsUpdatedEventValues(
              description = editable.description.nullIfEquals(updated.description),
          )
      val changedTo =
          BiomassQuadratDetailsUpdatedEventValues(
              description = updated.description.nullIfEquals(editable.description),
          )

      if (changedFrom != changedTo) {
        with(OBSERVATION_BIOMASS_QUADRAT_DETAILS) {
          if (existing == null) {
            dslContext
                .insertInto(OBSERVATION_BIOMASS_QUADRAT_DETAILS)
                .set(DESCRIPTION, updated.description)
                .set(MONITORING_PLOT_ID, monitoringPlotId)
                .set(OBSERVATION_ID, observationId)
                .set(POSITION_ID, position)
                .execute()

            eventPublisher.publishEvent(
                BiomassQuadratCreatedEvent(
                    updated.description,
                    monitoringPlotId,
                    observationId,
                    organizationId,
                    plantingSiteId,
                    position,
                )
            )
          } else {
            dslContext
                .update(OBSERVATION_BIOMASS_QUADRAT_DETAILS)
                .set(DESCRIPTION, updated.description)
                .where(MONITORING_PLOT_ID.eq(monitoringPlotId))
                .and(OBSERVATION_ID.eq(observationId))
                .and(POSITION_ID.eq(position))
                .execute()

            eventPublisher.publishEvent(
                BiomassQuadratDetailsUpdatedEvent(
                    changedFrom,
                    changedTo,
                    monitoringPlotId,
                    observationId,
                    organizationId,
                    plantingSiteId,
                    position,
                )
            )
          }
        }
      }
    }
  }

  fun updateRecordedTree(
      observationId: ObservationId,
      recordedTreeId: RecordedTreeId,
      updateFunc: (ExistingRecordedTreeModel) -> ExistingRecordedTreeModel,
  ) {
    requirePermissions { updateObservation(observationId) }

    withLockedObservation(observationId) { observation ->
      val existing = fetchRecordedTree(observationId, recordedTreeId)
      val updated = updateFunc(existing)

      val changedFrom =
          RecordedTreeUpdatedEventValues(
              description = existing.description.nullIfEquals(updated.description),
          )
      val changedTo =
          RecordedTreeUpdatedEventValues(
              description = updated.description.nullIfEquals(existing.description),
          )

      if (changedFrom != changedTo) {
        with(RECORDED_TREES) {
          dslContext
              .update(RECORDED_TREES)
              .set(DESCRIPTION, updated.description)
              .where(ID.eq(recordedTreeId))
              .execute()

          val (monitoringPlotId, organizationId) =
              dslContext
                  .select(
                      MONITORING_PLOT_ID.asNonNullable(),
                      monitoringPlots.plantingSites.ORGANIZATION_ID.asNonNullable(),
                  )
                  .from(RECORDED_TREES)
                  .where(ID.eq(recordedTreeId))
                  .fetchSingle()

          eventPublisher.publishEvent(
              RecordedTreeUpdatedEvent(
                  changedFrom = changedFrom,
                  changedTo = changedTo,
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId,
                  organizationId = organizationId,
                  plantingSiteId = observation.plantingSiteId,
                  recordedTreeId = recordedTreeId,
              )
          )
        }
      }
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
      speciesId: SpeciesId,
  ) {
    requirePermissions {
      updateObservation(observationId)
      updateSpecies(speciesId)
    }

    if (parentStore.getOrganizationId(observationId) != parentStore.getOrganizationId(speciesId)) {
      throw SpeciesInWrongOrganizationException(speciesId)
    }

    withLockedObservation(observationId) { observation ->
      when (observation.observationType) {
        ObservationType.BiomassMeasurements ->
            mergeOtherSpeciesForBiomass(observation, speciesId, otherSpeciesName)
        ObservationType.Monitoring ->
            mergeOtherSpeciesForMonitoring(observation, speciesId, otherSpeciesName)
      }
    }
  }

  private fun mergeOtherSpeciesForBiomass(
      observation: ExistingObservationModel,
      speciesId: SpeciesId,
      otherSpeciesName: String,
  ) {
    val observationId = observation.id

    val otherBiomassSpeciesId =
        with(OBSERVATION_BIOMASS_SPECIES) {
          dslContext.fetchValue(
              ID,
              SCIENTIFIC_NAME.eq(otherSpeciesName).and(OBSERVATION_ID.eq(observationId)),
          )
        }

    if (otherBiomassSpeciesId == null) {
      log.warn(
          "Biomass observation $observationId does not contain species name $otherSpeciesName; " +
              "merge is a no-op"
      )
      return
    }

    val targetBiomassSpeciesId =
        with(OBSERVATION_BIOMASS_SPECIES) {
          dslContext.fetchValue(ID, SPECIES_ID.eq(speciesId).and(OBSERVATION_ID.eq(observationId)))
        }

    if (targetBiomassSpeciesId == null) {
      // The target species wasn't present at all in the observation, so there's no need to merge
      // anything: we can just update the biomass species to point to the target species ID instead
      // of using a name.
      with(OBSERVATION_BIOMASS_SPECIES) {
        dslContext
            .update(OBSERVATION_BIOMASS_SPECIES)
            .set(SPECIES_ID, speciesId)
            .setNull(SCIENTIFIC_NAME)
            .where(ID.eq(otherBiomassSpeciesId))
            .execute()
      }

      return
    }

    // Recorded trees are a simple replacement of the biomass species ID.
    with(RECORDED_TREES) {
      dslContext
          .update(RECORDED_TREES)
          .set(BIOMASS_SPECIES_ID, targetBiomassSpeciesId)
          .where(BIOMASS_SPECIES_ID.eq(otherBiomassSpeciesId))
          .execute()
    }

    // For quadrat species, we need to add the abundance percentages of the numbered species and the
    // "Other" one if both exist in the quadrat. If only the "Other" one exists, then we can just
    // switch its biomass species ID in place.
    with(OBSERVATION_BIOMASS_QUADRAT_SPECIES) {
      val quadratSpeciesTable2 = OBSERVATION_BIOMASS_QUADRAT_SPECIES.`as`("quadrat2")

      dslContext
          .update(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
          .set(BIOMASS_SPECIES_ID, targetBiomassSpeciesId)
          .where(BIOMASS_SPECIES_ID.eq(otherBiomassSpeciesId))
          .andNotExists(
              DSL.selectOne()
                  .from(quadratSpeciesTable2)
                  .where(quadratSpeciesTable2.BIOMASS_SPECIES_ID.eq(targetBiomassSpeciesId))
                  .and(quadratSpeciesTable2.POSITION_ID.eq(POSITION_ID))
          )
          .execute()

      dslContext
          .update(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
          .set(
              ABUNDANCE_PERCENT,
              ABUNDANCE_PERCENT.plus(
                  DSL.field(
                      DSL.select(quadratSpeciesTable2.ABUNDANCE_PERCENT)
                          .from(quadratSpeciesTable2)
                          .where(quadratSpeciesTable2.BIOMASS_SPECIES_ID.eq(otherBiomassSpeciesId))
                          .and(quadratSpeciesTable2.POSITION_ID.eq(POSITION_ID))
                  )
              ),
          )
          .where(BIOMASS_SPECIES_ID.eq(targetBiomassSpeciesId))
          .andExists(
              DSL.selectOne()
                  .from(quadratSpeciesTable2)
                  .where(quadratSpeciesTable2.BIOMASS_SPECIES_ID.eq(otherBiomassSpeciesId))
                  .and(quadratSpeciesTable2.POSITION_ID.eq(POSITION_ID))
          )
          .execute()
    }

    // Invasive and threatened should be true if they're true on either version of the species.
    with(OBSERVATION_BIOMASS_SPECIES) {
      dslContext
          .update(OBSERVATION_BIOMASS_SPECIES)
          .set(
              IS_INVASIVE,
              DSL.select(DSL.boolOr(IS_INVASIVE))
                  .from(OBSERVATION_BIOMASS_SPECIES)
                  .where(ID.`in`(otherBiomassSpeciesId, targetBiomassSpeciesId)),
          )
          .set(
              IS_THREATENED,
              DSL.select(DSL.boolOr(IS_THREATENED))
                  .from(OBSERVATION_BIOMASS_SPECIES)
                  .where(ID.`in`(otherBiomassSpeciesId, targetBiomassSpeciesId)),
          )
          .where(ID.eq(targetBiomassSpeciesId))
          .execute()
    }

    // ON DELETE CASCADE will remove the data for the "Other" species from all the biomass tables.
    dslContext
        .deleteFrom(OBSERVATION_BIOMASS_SPECIES)
        .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(otherBiomassSpeciesId))
        .execute()
  }

  private fun mergeOtherSpeciesForMonitoring(
      observation: ExistingObservationModel,
      speciesId: SpeciesId,
      otherSpeciesName: String,
  ) {
    val observationId = observation.id
    val observationPlotDetails =
        dslContext
            .select(
                OBSERVATION_PLOTS.MONITORING_PLOT_ID,
                OBSERVATION_PLOTS.IS_PERMANENT,
                MONITORING_PLOTS.PLANTING_SUBZONE_ID,
                PLANTING_SUBZONES.PLANTING_ZONE_ID,
            )
            .from(OBSERVATION_PLOTS)
            .join(MONITORING_PLOTS)
            .on(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
            .leftJoin(PLANTING_SUBZONES)
            .on(MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
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
                      "in observation $observationId but is not in observation",
              )

      val plantingSite =
          dslContext
              .select()
              .from(PLANTING_SITES)
              .where(PLANTING_SITES.ID.eq(observation.plantingSiteId))
              .fetchOneInto(PlantingSitesRow::class.java)
              ?: throw PlantingSiteNotFoundException(observation.plantingSiteId)

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
          plantingSite,
          plotDetails[PLANTING_SUBZONES.PLANTING_ZONE_ID],
          plotDetails[MONITORING_PLOTS.PLANTING_SUBZONE_ID],
          monitoringPlotId,
          observation.isAdHoc,
          plotDetails[OBSERVATION_PLOTS.IS_PERMANENT]!!,
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
                      RecordedPlantStatus.Existing to (plotTotal.totalExisting ?: 0),
                  ),
          ),
      )
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

    with(OBSERVED_SUBZONE_SPECIES_TOTALS) {
      dslContext
          .deleteFrom(OBSERVED_SUBZONE_SPECIES_TOTALS)
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

  fun updatePlotObservation(
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
      recalculateSurvivalMortalityRates(observationId, plantingSiteId)
    }
  }

  /**
   * Recalculates the zone- and site-level mortality rates for an observation.
   *
   * When an observation starts, [populateCumulativeDead] inserts rows into the "observed species
   * totals" tables for the observation's plots, subzones, zones, and the site as a whole, mostly so
   * that the cumulative dead plant counts from earlier observations of the same plots can be
   * carried forward to the current observation.
   *
   * Those tables are updated incrementally as the observation proceeds, such that after the last
   * plot is completed, they have correct plot-level and subzone-level mortality rates based on the
   * plant counts from the just-observed plots.
   *
   * However, at the zone and site levels, we want mortality rates to also include plants from
   * subzones that weren't requested in the current observation but were observed in the past. That
   * is, the zone- and site-level mortality rates should be based on the most recent observation
   * data from all the subzones that have ever been observed, not just the ones in the current
   * observation.
   *
   * One approach would be to include the cumulative dead and permanent live values for other
   * subzones in the initial data set that's inserted by [populateCumulativeDead], such that the
   * incremental updates would take the additional plants into account.
   *
   * But that approach wouldn't give us the best available results in the face of concurrent
   * observations: if each of two subzones has its own active observation at the same time, the
   * zone- and site-level mortality rates for whichever observation finishes last should incorporate
   * the plant counts from the other observation. Incorporating those plant counts when the
   * observation finishes, rather than when it starts, means we can include data from observations
   * that weren't done yet at the time the current one started.
   */
  fun recalculateSurvivalMortalityRates(
      observationId: ObservationId,
      plantingSiteId: PlantingSiteId,
  ) {
    data class SubzoneSpeciesRecord(
        val certaintyId: RecordedSpeciesCertainty,
        val speciesId: SpeciesId?,
        val speciesName: String?,
        val plantingZoneId: PlantingZoneId,
        val permanentLive: Int,
        val cumulativeDead: Int,
        val totalLive: Int,
        val survivalRateIncludesTempPlots: Boolean,
    )

    val liveAndDeadTotals:
        Map<RecordedSpeciesKey, Map<PlantingZoneId, List<SubzoneSpeciesRecord>>> =
        with(OBSERVED_SUBZONE_SPECIES_TOTALS) {
          dslContext
              .select(
                  CERTAINTY_ID.asNonNullable(),
                  SPECIES_ID,
                  SPECIES_NAME,
                  PLANTING_SUBZONES.PLANTING_ZONE_ID.asNonNullable(),
                  PERMANENT_LIVE.asNonNullable(),
                  CUMULATIVE_DEAD.asNonNullable(),
                  TOTAL_LIVE.asNonNullable(),
                  PLANTING_SUBZONES.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.asNonNullable(),
              )
              .from(OBSERVED_SUBZONE_SPECIES_TOTALS)
              .join(PLANTING_SUBZONES)
              .on(PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
              .where(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(plantingSiteId))
              .and(OBSERVATION_ID.eq(latestObservationForSubzoneField(DSL.inline(observationId))))
              .and(CUMULATIVE_DEAD.plus(PERMANENT_LIVE).plus(TOTAL_LIVE).gt(0))
              .fetch { record ->
                SubzoneSpeciesRecord(
                    certaintyId = record[CERTAINTY_ID.asNonNullable()],
                    speciesId = record[SPECIES_ID],
                    speciesName = record[SPECIES_NAME],
                    plantingZoneId = record[PLANTING_SUBZONES.PLANTING_ZONE_ID.asNonNullable()],
                    permanentLive = record[PERMANENT_LIVE.asNonNullable()],
                    cumulativeDead = record[CUMULATIVE_DEAD.asNonNullable()],
                    totalLive = record[TOTAL_LIVE.asNonNullable()],
                    survivalRateIncludesTempPlots =
                        record[
                            PLANTING_SUBZONES.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
                                .asNonNullable()],
                )
              }
              .groupBy { record ->
                RecordedSpeciesKey(record.certaintyId, record.speciesId, record.speciesName)
              }
              .mapValues { (_, recordsForSpecies) ->
                recordsForSpecies.groupBy { it.plantingZoneId }
              }
        }

    liveAndDeadTotals.forEach { (speciesKey, zoneToLiveAndDead) ->
      zoneToLiveAndDead.forEach { (plantingZoneId, liveAndDeadForZone) ->
        val totalPermanentLive = liveAndDeadForZone.sumOf { it.permanentLive }
        val totalLive = liveAndDeadForZone.sumOf { it.totalLive }
        val totalDead = liveAndDeadForZone.sumOf { it.cumulativeDead }
        val zoneMortalityRate =
            if (totalPermanentLive + totalDead > 0) {
              ((totalDead * 100.0) / (totalPermanentLive + totalDead).toDouble()).roundToInt()
            } else {
              null
            }

        with(OBSERVED_ZONE_SPECIES_TOTALS) {
          val updateScope = ObservationSpeciesZone(plantingZoneId)
          val survivalRatePermanentDenominator =
              getSurvivalRateDenominator(
                  updateScope,
                  PLOT_T0_DENSITIES.SPECIES_ID.eq(speciesKey.id),
              )
          val survivalRateTempDenominator =
              getSurvivalRateTempDenominator(
                  updateScope,
                  PLANTING_ZONE_T0_TEMP_DENSITIES.SPECIES_ID.eq(speciesKey.id),
              )
          val survivalRateDenominator =
              DSL.coalesce(
                  survivalRatePermanentDenominator.plus(survivalRateTempDenominator),
                  survivalRatePermanentDenominator,
                  survivalRateTempDenominator,
              )
          val survivalRate =
              if (liveAndDeadForZone.first().survivalRateIncludesTempPlots) {
                getSurvivalRate(DSL.value(totalLive), survivalRateDenominator)
              } else {
                getSurvivalRate(DSL.value(totalPermanentLive), survivalRateDenominator)
              }

          val rowsInserted =
              dslContext
                  .insertInto(OBSERVED_ZONE_SPECIES_TOTALS)
                  .set(CERTAINTY_ID, speciesKey.certainty)
                  .set(CUMULATIVE_DEAD, totalDead)
                  .set(MORTALITY_RATE, zoneMortalityRate)
                  .set(OBSERVATION_ID, observationId)
                  .set(PERMANENT_LIVE, totalPermanentLive)
                  .set(PLANTING_ZONE_ID, plantingZoneId)
                  .set(SPECIES_ID, speciesKey.id)
                  .set(SPECIES_NAME, speciesKey.name)
                  .set(SURVIVAL_RATE, survivalRate)
                  .onConflictDoNothing()
                  .execute()
          if (rowsInserted == 0) {
            dslContext
                .update(OBSERVED_ZONE_SPECIES_TOTALS)
                .set(CUMULATIVE_DEAD, totalDead)
                .set(MORTALITY_RATE, zoneMortalityRate?.let { DSL.inline(it) } ?: MORTALITY_RATE)
                .set(PERMANENT_LIVE, totalPermanentLive)
                .set(SURVIVAL_RATE, survivalRate)
                .where(OBSERVATION_ID.eq(observationId))
                .and(PLANTING_ZONE_ID.eq(plantingZoneId))
                .and(CERTAINTY_ID.eq(speciesKey.certainty))
                .and(SPECIES_ID.eqOrIsNull(speciesKey.id))
                .and(SPECIES_NAME.eqOrIsNull(speciesKey.name))
                .execute()
          }
        }
      }

      val totalPermanentLive = zoneToLiveAndDead.flatMap { it.value }.sumOf { it.permanentLive }
      val totalLive = zoneToLiveAndDead.flatMap { it.value }.sumOf { it.totalLive }
      val totalDead = zoneToLiveAndDead.flatMap { it.value }.sumOf { it.cumulativeDead }
      val siteMortalityRate =
          if (totalDead > 0) {
            ((totalDead * 100.0) / (totalPermanentLive + totalDead).toDouble()).roundToInt()
          } else {
            0
          }

      with(OBSERVED_SITE_SPECIES_TOTALS) {
        val updateScope = ObservationSpeciesSite(plantingSiteId)
        val survivalRatePermanentDenominator =
            getSurvivalRateDenominator(
                updateScope,
                PLOT_T0_DENSITIES.SPECIES_ID.eq(speciesKey.id),
            )
        val survivalRateTempDenominator =
            getSurvivalRateTempDenominator(
                updateScope,
                PLANTING_ZONE_T0_TEMP_DENSITIES.SPECIES_ID.eq(speciesKey.id),
            )
        val survivalRateDenominator =
            DSL.coalesce(
                survivalRatePermanentDenominator.plus(survivalRateTempDenominator),
                survivalRatePermanentDenominator,
                survivalRateTempDenominator,
            )
        val survivalRate =
            if (zoneToLiveAndDead.flatMap { it.value }.first().survivalRateIncludesTempPlots) {
              getSurvivalRate(DSL.value(totalLive), survivalRateDenominator)
            } else {
              getSurvivalRate(DSL.value(totalPermanentLive), survivalRateDenominator)
            }

        val rowsInserted =
            dslContext
                .insertInto(OBSERVED_SITE_SPECIES_TOTALS)
                .set(CERTAINTY_ID, speciesKey.certainty)
                .set(CUMULATIVE_DEAD, totalDead)
                .set(MORTALITY_RATE, siteMortalityRate)
                .set(OBSERVATION_ID, observationId)
                .set(PERMANENT_LIVE, totalPermanentLive)
                .set(PLANTING_SITE_ID, plantingSiteId)
                .set(SPECIES_ID, speciesKey.id)
                .set(SPECIES_NAME, speciesKey.name)
                .set(SURVIVAL_RATE, survivalRate)
                .onConflictDoNothing()
                .execute()
        if (rowsInserted == 0) {
          dslContext
              .update(OBSERVED_SITE_SPECIES_TOTALS)
              .set(CUMULATIVE_DEAD, totalDead)
              .set(MORTALITY_RATE, siteMortalityRate)
              .set(PERMANENT_LIVE, totalPermanentLive)
              .set(SURVIVAL_RATE, survivalRate)
              .where(OBSERVATION_ID.eq(observationId))
              .and(PLANTING_SITE_ID.eq(plantingSiteId))
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

    recalculateSurvivalRate(ObservationSpeciesSubzone(monitoringPlotId))

    recalculateSurvivalRate(ObservationSpeciesZone(monitoringPlotId))

    recalculateSurvivalRate(ObservationSpeciesSite(monitoringPlotId))
  }

  fun recalculateSurvivalRates(plantingZoneId: PlantingZoneId) {
    val subzoneGroups: Map<PlantingSubzoneId, List<MonitoringPlotId>> =
        dslContext
            .select(PLANTING_SUBZONES.ID, MONITORING_PLOTS.ID)
            .from(MONITORING_PLOTS)
            .join(PLANTING_SUBZONES)
            .on(PLANTING_SUBZONES.ID.eq(MONITORING_PLOTS.PLANTING_SUBZONE_ID))
            .where(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(plantingZoneId))
            .fetchGroups(PLANTING_SUBZONES.ID.asNonNullable(), MONITORING_PLOTS.ID.asNonNullable())

    subzoneGroups.values.flatten().forEach { recalculateSurvivalRate(ObservationSpeciesPlot(it)) }

    subzoneGroups.keys.forEach { recalculateSurvivalRate(ObservationSpeciesSubzone(it)) }

    recalculateSurvivalRate(ObservationSpeciesZone(plantingZoneId))

    recalculateSurvivalRate(ObservationSpeciesSite(plantingZoneId))
  }

  private fun <ID : Any> recalculateSurvivalRate(updateScope: ObservationSpeciesScope<ID>) {
    val table = updateScope.observedTotalsTable
    val speciesIdField =
        table.field("species_id", SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()))!!
    val survivalRatePermanentDenominator =
        getSurvivalRateDenominator(
            updateScope,
            PLOT_T0_DENSITIES.SPECIES_ID.eq(speciesIdField),
        )
    val survivalRateTempDenominator =
        getSurvivalRateTempDenominator(
            updateScope,
            PLANTING_ZONE_T0_TEMP_DENSITIES.SPECIES_ID.eq(speciesIdField),
        )
    val survivalRateDenominator =
        DSL.coalesce(
            survivalRatePermanentDenominator.plus(survivalRateTempDenominator),
            survivalRatePermanentDenominator,
            survivalRateTempDenominator,
        )
    val survivalRateField = table.field("survival_rate", Int::class.java)!!
    val permanentLiveField = table.field("permanent_live", Int::class.java)!!
    val totalLiveField = table.field("total_live", Int::class.java)!!

    dslContext
        .update(table)
        .set(
            survivalRateField,
            DSL.case_()
                .`when`(
                    survivalRateDenominator.eq(BigDecimal.ZERO),
                    DSL.castNull(SQLDataType.INTEGER),
                )
                .else_(
                    DSL.case_()
                        .`when`(
                            updateScope.observedTotalsPlantingSiteTempCondition,
                            (totalLiveField
                                .mul(BigDecimal.valueOf(100))
                                .div(survivalRateDenominator)),
                        )
                        .else_(
                            (permanentLiveField
                                .mul(BigDecimal.valueOf(100))
                                .div(survivalRateDenominator)),
                        )
                ),
        )
        .where(updateScope.observedTotalsCondition)
        .execute()
  }

  @EventListener
  fun on(event: T0PlotDataAssignedEvent) {
    recalculateSurvivalRates(event.monitoringPlotId)
  }

  @EventListener
  fun on(event: T0ZoneDataAssignedEvent) {
    recalculateSurvivalRates(event.plantingZoneId)
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
                    .where(PLANTING_ZONES.PLANTING_SITE_ID.eq(plantingSiteId))
            )
        )
        .execute()

    dslContext
        .update(PLANTING_SUBZONE_POPULATIONS)
        .set(PLANTING_SUBZONE_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION, 0)
        .where(
            PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID.`in`(
                DSL.select(PLANTING_SUBZONES.ID)
                    .from(PLANTING_SUBZONES)
                    .where(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(plantingSiteId))
            )
        )
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

    dslContext.transaction { _ ->
      with(OBSERVED_PLOT_SPECIES_TOTALS) {
        dslContext
            .insertInto(
                this,
                CERTAINTY_ID,
                CUMULATIVE_DEAD,
                MONITORING_PLOT_ID,
                MORTALITY_RATE,
                OBSERVATION_ID,
                SPECIES_ID,
                SPECIES_NAME,
            )
            .select(
                DSL.select(
                        CERTAINTY_ID,
                        CUMULATIVE_DEAD,
                        MONITORING_PLOT_ID,
                        DSL.value(100),
                        DSL.value(observationId),
                        SPECIES_ID,
                        SPECIES_NAME,
                    )
                    .distinctOn(MONITORING_PLOT_ID, CERTAINTY_ID, SPECIES_ID, SPECIES_NAME)
                    .from(OBSERVED_PLOT_SPECIES_TOTALS)
                    .join(OBSERVATION_PLOTS)
                    .on(MONITORING_PLOT_ID.eq(OBSERVATION_PLOTS.MONITORING_PLOT_ID))
                    .join(OBSERVATIONS)
                    .on(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                    .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
                    .and(OBSERVATION_PLOTS.IS_PERMANENT)
                    .and(CUMULATIVE_DEAD.gt(0))
                    .orderBy(
                        MONITORING_PLOT_ID,
                        CERTAINTY_ID,
                        SPECIES_ID,
                        SPECIES_NAME,
                        OBSERVATIONS.COMPLETED_TIME.desc(),
                    )
            )
            .execute()
      }

      // Roll up the just-inserted plot totals (which only include plots that are currently
      // permanent and that had dead plants previously) to get the subzone totals.

      with(OBSERVED_SUBZONE_SPECIES_TOTALS) {
        dslContext
            .insertInto(
                this,
                CERTAINTY_ID,
                CUMULATIVE_DEAD,
                MORTALITY_RATE,
                OBSERVATION_ID,
                PLANTING_SUBZONE_ID,
                SPECIES_ID,
                SPECIES_NAME,
            )
            .select(
                DSL.select(
                        OBSERVED_PLOT_SPECIES_TOTALS.CERTAINTY_ID,
                        DSL.sum(OBSERVED_PLOT_SPECIES_TOTALS.CUMULATIVE_DEAD)
                            .cast(SQLDataType.INTEGER),
                        DSL.value(100),
                        DSL.value(observationId),
                        MONITORING_PLOTS.PLANTING_SUBZONE_ID,
                        OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID,
                        OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_NAME,
                    )
                    .from(OBSERVED_PLOT_SPECIES_TOTALS)
                    .join(MONITORING_PLOTS)
                    .on(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                    .where(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
                    .groupBy(
                        OBSERVED_PLOT_SPECIES_TOTALS.CERTAINTY_ID,
                        MONITORING_PLOTS.PLANTING_SUBZONE_ID,
                        OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID,
                        OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_NAME,
                    )
            )
            .execute()
      }

      // Roll up the just-inserted subzone totals to get the zone totals.

      with(OBSERVED_ZONE_SPECIES_TOTALS) {
        dslContext
            .insertInto(
                this,
                CERTAINTY_ID,
                CUMULATIVE_DEAD,
                MORTALITY_RATE,
                OBSERVATION_ID,
                PLANTING_ZONE_ID,
                SPECIES_ID,
                SPECIES_NAME,
            )
            .select(
                DSL.select(
                        OBSERVED_SUBZONE_SPECIES_TOTALS.CERTAINTY_ID,
                        DSL.sum(OBSERVED_SUBZONE_SPECIES_TOTALS.CUMULATIVE_DEAD)
                            .cast(SQLDataType.INTEGER),
                        DSL.value(100),
                        DSL.value(observationId),
                        PLANTING_SUBZONES.PLANTING_ZONE_ID,
                        OBSERVED_SUBZONE_SPECIES_TOTALS.SPECIES_ID,
                        OBSERVED_SUBZONE_SPECIES_TOTALS.SPECIES_NAME,
                    )
                    .from(OBSERVED_SUBZONE_SPECIES_TOTALS)
                    .join(PLANTING_SUBZONES)
                    .on(
                        PLANTING_SUBZONES.ID.eq(OBSERVED_SUBZONE_SPECIES_TOTALS.PLANTING_SUBZONE_ID)
                    )
                    .where(OBSERVED_SUBZONE_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
                    .groupBy(
                        OBSERVED_SUBZONE_SPECIES_TOTALS.CERTAINTY_ID,
                        PLANTING_SUBZONES.PLANTING_ZONE_ID,
                        OBSERVED_SUBZONE_SPECIES_TOTALS.SPECIES_ID,
                        OBSERVED_SUBZONE_SPECIES_TOTALS.SPECIES_NAME,
                    )
            )
            .execute()
      }

      // Roll up the just-inserted zone totals to get the site totals.

      with(OBSERVED_SITE_SPECIES_TOTALS) {
        dslContext
            .insertInto(
                this,
                CERTAINTY_ID,
                CUMULATIVE_DEAD,
                MORTALITY_RATE,
                OBSERVATION_ID,
                PLANTING_SITE_ID,
                SPECIES_ID,
                SPECIES_NAME,
            )
            .select(
                DSL.select(
                        OBSERVED_ZONE_SPECIES_TOTALS.CERTAINTY_ID,
                        DSL.sum(OBSERVED_ZONE_SPECIES_TOTALS.CUMULATIVE_DEAD)
                            .cast(SQLDataType.INTEGER),
                        DSL.value(100),
                        DSL.value(observationId),
                        DSL.value(observation.plantingSiteId),
                        OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_ID,
                        OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_NAME,
                    )
                    .from(OBSERVED_ZONE_SPECIES_TOTALS)
                    .where(OBSERVED_ZONE_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
                    .groupBy(
                        OBSERVED_ZONE_SPECIES_TOTALS.CERTAINTY_ID,
                        OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_ID,
                        OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_NAME,
                    )
            )
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
      isPermanent: Boolean,
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
                    .where(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(plotId)),
            )
            .set(MONITORING_PLOT_ID, plotId)
            .set(OBSERVATION_ID, observationId)
            .set(STATUS_ID, ObservationPlotStatus.Unclaimed)
            .execute()
      }
    }
  }

  /**
   * Updates the tables that hold the aggregated per-species plant totals from observations.
   *
   * @param cumulativeDeadFromCurrentObservation If true, only use [observationId]'s totals as the
   *   starting point for the cumulative dead count at the subzone, zone, and site level. If false,
   *   use the most recently-created observation of the subzone, zone, or site, up to and including
   *   [observationId].
   */
  private fun updateSpeciesTotals(
      observationId: ObservationId,
      plantingSite: PlantingSitesRow,
      plantingZoneId: PlantingZoneId?,
      plantingSubzoneId: PlantingSubzoneId?,
      monitoringPlotId: MonitoringPlotId?,
      isAdHoc: Boolean,
      isPermanent: Boolean,
      plantCountsBySpecies: Map<RecordedSpeciesKey, Map<RecordedPlantStatus, Int>>,
      cumulativeDeadFromCurrentObservation: Boolean = false,
  ) {
    if (plantCountsBySpecies.isNotEmpty()) {
      if (monitoringPlotId != null) {
        updateSpeciesTotalsTable(
            observationId,
            isPermanent,
            plantCountsBySpecies,
            false,
            plantingSite,
            ObservationSpeciesPlot(monitoringPlotId),
        )
      }

      if (!isAdHoc) {
        if (plantingSubzoneId != null) {
          updateSpeciesTotalsTable(
              observationId,
              isPermanent,
              plantCountsBySpecies,
              cumulativeDeadFromCurrentObservation,
              plantingSite,
              ObservationSpeciesSubzone(plantingSubzoneId, monitoringPlotId),
          )
        }

        if (plantingZoneId != null) {
          updateSpeciesTotalsTable(
              observationId,
              isPermanent,
              plantCountsBySpecies,
              cumulativeDeadFromCurrentObservation,
              plantingSite,
              ObservationSpeciesZone(plantingZoneId, monitoringPlotId),
          )
        }

        updateSpeciesTotalsTable(
            observationId,
            isPermanent,
            plantCountsBySpecies,
            cumulativeDeadFromCurrentObservation,
            plantingSite,
            ObservationSpeciesSite(plantingSite.id!!, monitoringPlotId),
        )
      }
    }
  }

  /**
   * Updates one of the tables that holds the aggregated per-species plant totals from observations.
   *
   * These tables are all identical with the exception of one column that identifies the scope of
   * aggregation (monitoring plot, planting zone, or planting site).
   *
   * @param cumulativeDeadFromCurrentObservation If true, only use [observationId]'s totals as the
   *   starting point for the cumulative dead count. If false, use the most recently-created
   *   observation of the area, up to and including [observationId].
   */
  private fun <ID : Any> updateSpeciesTotalsTable(
      observationId: ObservationId,
      isPermanent: Boolean,
      totals: Map<RecordedSpeciesKey, Map<RecordedPlantStatus, Int>>,
      cumulativeDeadFromCurrentObservation: Boolean = false,
      plantingSite: PlantingSitesRow,
      updateScope: ObservationSpeciesScope<ID>,
  ) {
    val table = updateScope.observedTotalsTable
    val observationIdField =
        table.field(
            "observation_id",
            SQLDataType.BIGINT.asConvertedDataType(ObservationIdConverter()),
        )!!
    val certaintyField =
        table.field(
            "certainty_id",
            SQLDataType.INTEGER.asConvertedDataType(RecordedSpeciesCertaintyConverter()),
        )!!
    val speciesIdField =
        table.field("species_id", SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()))!!
    val speciesNameField = table.field("species_name", String::class.java)!!
    val totalLiveField = table.field("total_live", Int::class.java)!!
    val totalDeadField = table.field("total_dead", Int::class.java)!!
    val totalExistingField = table.field("total_existing", Int::class.java)!!
    val mortalityRateField = table.field("mortality_rate", Int::class.java)!!
    val cumulativeDeadField = table.field("cumulative_dead", Int::class.java)!!
    val permanentLiveField = table.field("permanent_live", Int::class.java)!!
    val survivalRateField = table.field("survival_rate", Int::class.java)!!

    val observationIdCondition =
        if (cumulativeDeadFromCurrentObservation) {
          observationIdField.eq(observationId)
        } else {
          observationIdField.le(observationId)
        }

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
                  .where(updateScope.observedTotalsScopeField.eq(updateScope.scopeId))
                  .and(observationIdCondition)
                  .and(certaintyField.eq(speciesKey.certainty))
                  .and(
                      if (speciesKey.id != null) speciesIdField.eq(speciesKey.id)
                      else speciesIdField.isNull
                  )
                  .and(
                      if (speciesKey.name != null) speciesNameField.eq(speciesKey.name)
                      else speciesNameField.isNull
                  )
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

        val survivalRatePermanentDenominator =
            getSurvivalRateDenominator(
                updateScope,
                PLOT_T0_DENSITIES.SPECIES_ID.eq(speciesKey.id),
            )
        val survivalRateTempDenominator =
            getSurvivalRateTempDenominator(
                updateScope,
                PLANTING_ZONE_T0_TEMP_DENSITIES.SPECIES_ID.eq(speciesKey.id),
            )
        val survivalRateDenominator =
            DSL.coalesce(
                survivalRatePermanentDenominator.plus(survivalRateTempDenominator),
                survivalRatePermanentDenominator,
                survivalRateTempDenominator,
            )
        val survivalRate =
            if (plantingSite.survivalRateIncludesTempPlots!! && speciesKey.id != null) {
              getSurvivalRate(DSL.value(totalLive), survivalRateDenominator)
            } else if (isPermanent && speciesKey.id != null) {
              getSurvivalRate(DSL.value(permanentLive), survivalRateDenominator)
            } else {
              DSL.castNull(SQLDataType.INTEGER)
            }

        val rowsInserted =
            dslContext
                .insertInto(table)
                .set(observationIdField, observationId)
                .set(updateScope.observedTotalsScopeField, updateScope.scopeId)
                .set(certaintyField, speciesKey.certainty)
                .set(speciesIdField, speciesKey.id)
                .set(speciesNameField, speciesKey.name)
                .set(totalLiveField, totalLive)
                .set(totalDeadField, totalDead)
                .set(totalExistingField, totalExisting)
                .set(cumulativeDeadField, cumulativeDead)
                .set(permanentLiveField, permanentLive)
                .set(mortalityRateField, mortalityRate)
                .set(survivalRateField, survivalRate)
                .onConflictDoNothing()
                .execute()

        if (rowsInserted == 0) {
          val scopeIdAndSpeciesCondition =
              updateScope.observedTotalsScopeField
                  .eq(updateScope.scopeId)
                  .and(
                      if (speciesKey.id != null) speciesIdField.eq(speciesKey.id)
                      else speciesIdField.isNull
                  )
                  .and(
                      if (speciesKey.name != null) speciesNameField.eq(speciesKey.name)
                      else speciesNameField.isNull
                  )

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
                        DSL.case_(observationIdField).`when`(observationId, permanentLive).else_(0)
                    )

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
                                .cast(SQLDataType.INTEGER)
                        ),
                )
                .where(observationIdField.ge(observationId))
                .and(scopeIdAndSpeciesCondition)
                .execute()
          }

          val survivalRate =
              if (plantingSite.survivalRateIncludesTempPlots!! && speciesKey.id != null) {
                DSL.if_(
                    survivalRateDenominator.eq(BigDecimal.ZERO),
                    DSL.zero(),
                    totalLiveField.plus(totalLive).mul(100).div(survivalRateDenominator),
                )
              } else if (isPermanent && speciesKey.id != null) {
                DSL.if_(
                    survivalRatePermanentDenominator.eq(BigDecimal.ZERO),
                    DSL.zero(),
                    permanentLiveField
                        .plus(permanentLive)
                        .mul(100)
                        .div(survivalRatePermanentDenominator),
                )
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
                "scope" to updateScope.scopeId,
                "species" to speciesKey,
            ) {
              log.error("BUG! Insert and update of species totals both failed")
            }
          }
        }
      }
    }
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

  private fun <ID : Any> getSurvivalRateDenominator(
      updateScope: ObservationSpeciesScope<ID>,
      condition: Condition,
  ): Field<BigDecimal> =
      DSL.field(
          DSL.select(DSL.sum(PLOT_T0_DENSITIES.PLOT_DENSITY).mul(DSL.inline(HECTARES_PER_PLOT)))
              .from(PLOT_T0_DENSITIES)
              .where(updateScope.t0DensityCondition)
              .and(condition)
              .and(
                  plotHasCompletedObservations(
                      PLOT_T0_DENSITIES.MONITORING_PLOT_ID,
                      true,
                      updateScope.alternateCompletedCondition,
                  )
              )
      )

  private fun <ID : Any> getSurvivalRateTempDenominator(
      updateScope: ObservationSpeciesScope<ID>,
      condition: Condition,
  ): Field<BigDecimal> =
      with(PLANTING_ZONE_T0_TEMP_DENSITIES) {
        DSL.field(
            DSL.select(DSL.sum(ZONE_DENSITY).mul(DSL.inline(HECTARES_PER_PLOT)))
                .from(PLANTING_ZONE_T0_TEMP_DENSITIES)
                .join(MONITORING_PLOTS)
                .on(
                    MONITORING_PLOTS.plantingSubzones.PLANTING_ZONE_ID.eq(
                        PLANTING_ZONE_T0_TEMP_DENSITIES.PLANTING_ZONE_ID
                    )
                )
                .where(condition)
                .and(updateScope.tempZoneCondition)
                .and(plantingZones.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true))
                .and(
                    plotHasCompletedObservations(
                        MONITORING_PLOTS.ID,
                        false,
                        updateScope.alternateCompletedCondition,
                    )
                )
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
            MONITORING_PLOTS.plantingSubzones.PLANTING_SITE_ID,
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

  data class RecordedSpeciesKey(
      val certainty: RecordedSpeciesCertainty,
      val id: SpeciesId?,
      val name: String?,
  )
}
