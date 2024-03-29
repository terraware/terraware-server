package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.daos.MonitoringPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSeasonsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSubzonesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingZonesDao
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSeasonsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_NOTIFICATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_POPULATIONS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.event.PlantingSeasonEndedEvent
import com.terraformation.backend.tracking.event.PlantingSeasonRescheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonScheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteDeletionStartedEvent
import com.terraformation.backend.tracking.model.CannotUpdatePastPlantingSeasonException
import com.terraformation.backend.tracking.model.ExistingPlantingSeasonModel
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.PlantingSeasonsOverlapException
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteReportedPlantTotals
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.tracking.model.ReplacementResult
import com.terraformation.backend.tracking.model.UpdatedPlantingSeasonModel
import com.terraformation.backend.util.createRectangle
import com.terraformation.backend.util.toInstant
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel
import org.springframework.context.ApplicationEventPublisher

@Named
class PlantingSiteStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val monitoringPlotsDao: MonitoringPlotsDao,
    private val parentStore: ParentStore,
    private val plantingSeasonsDao: PlantingSeasonsDao,
    private val plantingSitesDao: PlantingSitesDao,
    private val plantingSubzonesDao: PlantingSubzonesDao,
    private val plantingZonesDao: PlantingZonesDao,
) {
  private val log = perClassLogger()

  private val monitoringPlotBoundaryField = MONITORING_PLOTS.BOUNDARY.forMultiset()
  private val plantingSubzoneBoundaryField = PLANTING_SUBZONES.BOUNDARY.forMultiset()
  private val plantingZonesBoundaryField = PLANTING_ZONES.BOUNDARY.forMultiset()

  fun fetchSiteById(
      plantingSiteId: PlantingSiteId,
      depth: PlantingSiteDepth,
  ): PlantingSiteModel {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return fetchSitesByCondition(PLANTING_SITES.ID.eq(plantingSiteId), depth).firstOrNull()
        ?: throw PlantingSiteNotFoundException(plantingSiteId)
  }

  fun fetchSitesByOrganizationId(
      organizationId: OrganizationId,
      depth: PlantingSiteDepth = PlantingSiteDepth.Site,
  ): List<PlantingSiteModel> {
    requirePermissions { readOrganization(organizationId) }

    return fetchSitesByCondition(PLANTING_SITES.ORGANIZATION_ID.eq(organizationId), depth)
  }

  fun fetchSitesByProjectId(
      projectId: ProjectId,
      depth: PlantingSiteDepth = PlantingSiteDepth.Site,
  ): List<PlantingSiteModel> {
    requirePermissions { readProject(projectId) }

    return fetchSitesByCondition(PLANTING_SITES.PROJECT_ID.eq(projectId), depth)
  }

  private fun fetchSitesByCondition(
      condition: Condition,
      depth: PlantingSiteDepth,
  ): List<PlantingSiteModel> {
    val zonesField =
        if (depth != PlantingSiteDepth.Site) {
          plantingZonesMultiset(depth)
        } else {
          null
        }

    return dslContext
        .select(PLANTING_SITES.asterisk(), plantingSeasonsMultiset, zonesField)
        .from(PLANTING_SITES)
        .where(condition)
        .orderBy(PLANTING_SITES.ID)
        .fetch { PlantingSiteModel(it, plantingSeasonsMultiset, zonesField) }
  }

  fun countMonitoringPlots(
      plantingSiteId: PlantingSiteId
  ): Map<PlantingZoneId, Map<PlantingSubzoneId, Int>> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    val countBySubzoneField =
        DSL.multiset(
                DSL.select(PLANTING_SUBZONES.ID.asNonNullable(), DSL.count())
                    .from(MONITORING_PLOTS)
                    .join(PLANTING_SUBZONES)
                    .on(MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
                    .where(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID))
                    .groupBy(PLANTING_SUBZONES.ID))
            .convertFrom { results -> results.associate { it.value1() to it.value2() } }

    return dslContext
        .select(PLANTING_ZONES.ID, countBySubzoneField)
        .from(PLANTING_ZONES)
        .where(PLANTING_ZONES.PLANTING_SITE_ID.eq(plantingSiteId))
        .fetchMap(PLANTING_ZONES.ID.asNonNullable(), countBySubzoneField)
  }

  fun countReportedPlantsInSubzones(plantingSiteId: PlantingSiteId): Map<PlantingSubzoneId, Long> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    val sumField = DSL.sum(PLANTINGS.NUM_PLANTS)
    return dslContext
        .select(PLANTING_SUBZONES.ID.asNonNullable(), sumField)
        .from(PLANTING_SUBZONES)
        .join(PLANTINGS)
        .on(PLANTING_SUBZONES.ID.eq(PLANTINGS.PLANTING_SUBZONE_ID))
        .where(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(plantingSiteId))
        .groupBy(PLANTING_SUBZONES.ID)
        .having(sumField.gt(BigDecimal.ZERO))
        .fetch()
        .associate { it.value1() to it.value2().toLong() }
  }

  fun countReportedPlants(plantingSiteId: PlantingSiteId): PlantingSiteReportedPlantTotals {
    requirePermissions { readPlantingSite(plantingSiteId) }

    val zoneTotalSinceField = DSL.sum(PLANTING_ZONE_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION)
    val zoneTotalPlantsField = DSL.sum(PLANTING_ZONE_POPULATIONS.TOTAL_PLANTS)
    val zoneTotals =
        dslContext
            .select(
                PLANTING_ZONES.ID,
                PLANTING_ZONES.AREA_HA,
                PLANTING_ZONES.TARGET_PLANTING_DENSITY,
                zoneTotalSinceField,
                zoneTotalPlantsField)
            .from(PLANTING_ZONES)
            .leftJoin(PLANTING_ZONE_POPULATIONS)
            .on(PLANTING_ZONE_POPULATIONS.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID))
            .where(PLANTING_ZONES.PLANTING_SITE_ID.eq(plantingSiteId))
            .groupBy(
                PLANTING_ZONES.ID, PLANTING_ZONES.AREA_HA, PLANTING_ZONES.TARGET_PLANTING_DENSITY)
            .orderBy(PLANTING_ZONES.ID)
            .fetch { record ->
              val targetPlants =
                  record[PLANTING_ZONES.AREA_HA.asNonNullable()] *
                      record[PLANTING_ZONES.TARGET_PLANTING_DENSITY.asNonNullable()]

              PlantingSiteReportedPlantTotals.PlantingZone(
                  id = record[PLANTING_ZONES.ID.asNonNullable()],
                  plantsSinceLastObservation = record[zoneTotalSinceField]?.toInt() ?: 0,
                  targetPlants = targetPlants.toInt(),
                  totalPlants = record[zoneTotalPlantsField]?.toInt() ?: 0,
              )
            }

    val siteTotalSinceField = DSL.sum(PLANTING_SITE_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION)
    val siteTotalPlantsField = DSL.sum(PLANTING_SITE_POPULATIONS.TOTAL_PLANTS)
    val siteTotalSpeciesField = DSL.count()

    return dslContext
        .select(siteTotalSinceField, siteTotalPlantsField, siteTotalSpeciesField)
        .from(PLANTING_SITE_POPULATIONS)
        .where(PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
        .fetchOne { record ->
          PlantingSiteReportedPlantTotals(
              id = plantingSiteId,
              plantingZones = zoneTotals,
              plantsSinceLastObservation = record[siteTotalSinceField]?.toInt() ?: 0,
              totalPlants = record[siteTotalPlantsField]?.toInt() ?: 0,
              totalSpecies = record[siteTotalSpeciesField] ?: 0,
          )
        } ?: PlantingSiteReportedPlantTotals(plantingSiteId, zoneTotals, 0, 0, 0)
  }

  fun createPlantingSite(
      organizationId: OrganizationId,
      name: String,
      description: String?,
      timeZone: ZoneId?,
      projectId: ProjectId?,
      boundary: MultiPolygon? = null,
      plantingSeasons: Collection<UpdatedPlantingSeasonModel> = emptyList(),
  ): PlantingSiteModel {
    requirePermissions {
      createPlantingSite(organizationId)
      projectId?.let { readProject(it) }
    }

    if (projectId != null && organizationId != parentStore.getOrganizationId(projectId)) {
      throw ProjectInDifferentOrganizationException()
    }

    val now = clock.instant()

    val plantingSitesRow =
        PlantingSitesRow(
            boundary = boundary,
            createdBy = currentUser().userId,
            createdTime = now,
            description = description,
            modifiedBy = currentUser().userId,
            modifiedTime = now,
            name = name,
            organizationId = organizationId,
            projectId = projectId,
            timeZone = timeZone,
        )

    dslContext.transaction { _ ->
      plantingSitesDao.insert(plantingSitesRow)

      val effectiveTimeZone = timeZone ?: parentStore.getEffectiveTimeZone(plantingSitesRow.id!!)

      if (!plantingSeasons.isEmpty()) {
        updatePlantingSeasons(plantingSitesRow.id!!, plantingSeasons, effectiveTimeZone)
      }
    }

    return fetchSiteById(plantingSitesRow.id!!, PlantingSiteDepth.Site)
  }

  fun updatePlantingSite(
      plantingSiteId: PlantingSiteId,
      plantingSeasons: Collection<UpdatedPlantingSeasonModel>,
      editFunc: (PlantingSiteModel) -> PlantingSiteModel,
  ) {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    val initial = fetchSiteById(plantingSiteId, PlantingSiteDepth.Zone)
    val edited = editFunc(initial)

    if (edited.projectId != null) {
      requirePermissions { readProject(edited.projectId) }

      if (initial.organizationId != parentStore.getOrganizationId(edited.projectId)) {
        throw ProjectInDifferentOrganizationException()
      }
    }

    val initialTimeZone = initial.timeZone ?: parentStore.getEffectiveTimeZone(plantingSiteId)
    val editedTimeZone = edited.timeZone ?: parentStore.getEffectiveTimeZone(plantingSiteId)

    dslContext.transaction { _ ->
      with(PLANTING_SITES) {
        dslContext
            .update(PLANTING_SITES)
            .set(DESCRIPTION, edited.description)
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, clock.instant())
            .set(NAME, edited.name)
            .set(PROJECT_ID, edited.projectId)
            .set(TIME_ZONE, edited.timeZone)
            .apply {
              // Boundaries can only be updated on simple planting sites.
              if (initial.plantingZones.isEmpty()) set(BOUNDARY, edited.boundary)
            }
            .where(ID.eq(plantingSiteId))
            .execute()
      }

      updatePlantingSeasons(
          plantingSiteId,
          plantingSeasons,
          edited.timeZone ?: parentStore.getEffectiveTimeZone(plantingSiteId),
          initial.plantingSeasons,
          initial.timeZone ?: parentStore.getEffectiveTimeZone(plantingSiteId),
      )

      if (initialTimeZone != editedTimeZone) {
        eventPublisher.publishEvent(
            PlantingSiteTimeZoneChangedEvent(edited, initialTimeZone, editedTimeZone))
      }
    }
  }

  fun updatePlantingSeasons(
      plantingSiteId: PlantingSiteId,
      desiredSeasons: Collection<UpdatedPlantingSeasonModel>,
      desiredTimeZone: ZoneId,
      existingSeasons: Collection<ExistingPlantingSeasonModel> = emptyList(),
      existingTimeZone: ZoneId? = null,
  ) {
    val now = clock.instant()
    val todayAtSite = now.atZone(desiredTimeZone).toLocalDate()

    val desiredSeasonsById = desiredSeasons.filter { it.id != null }.associateBy { it.id!! }
    val existingSeasonsById = existingSeasons.associateBy { it.id }

    validatePlantingSeasons(desiredSeasons, existingSeasonsById, todayAtSite)

    val pastSeasonIds: Set<PlantingSeasonId> =
        existingSeasons.filter { it.endDate < todayAtSite }.map { it.id }.toSet()

    val seasonIdsToDelete: Set<PlantingSeasonId> =
        existingSeasonsById.keys - desiredSeasonsById.keys - pastSeasonIds

    val seasonsToInsert: List<PlantingSeasonsRow> =
        desiredSeasons
            .filter { it.id == null }
            .map { desiredSeason ->
              val startTime = desiredSeason.startDate.toInstant(desiredTimeZone)
              val endTime = desiredSeason.endDate.plusDays(1).toInstant(desiredTimeZone)

              PlantingSeasonsRow(
                  endDate = desiredSeason.endDate,
                  endTime = endTime,
                  isActive = now >= startTime && now < endTime,
                  plantingSiteId = plantingSiteId,
                  startDate = desiredSeason.startDate,
                  startTime = startTime,
              )
            }

    val seasonsToUpdate: List<UpdatedPlantingSeasonModel> =
        desiredSeasonsById.values.filter { season ->
          val existingSeason =
              existingSeasonsById[season.id!!] ?: throw PlantingSeasonNotFoundException(season.id)
          (existingTimeZone != desiredTimeZone && existingSeason.endDate >= todayAtSite) ||
              season.startDate != existingSeason.startDate ||
              season.endDate != existingSeason.endDate
        }

    if (seasonIdsToDelete.isNotEmpty()) {
      plantingSeasonsDao.deleteById(seasonIdsToDelete)
    }

    seasonsToUpdate.forEach { desiredSeason ->
      val existingSeason = existingSeasonsById[desiredSeason.id]!!
      val startTime =
          if (existingSeason.startDate != desiredSeason.startDate ||
              existingSeason.startTime >= now && existingTimeZone != desiredTimeZone) {
            desiredSeason.startDate.toInstant(desiredTimeZone)
          } else {
            existingSeason.startTime
          }
      val endTime =
          if (existingSeason.endDate != desiredSeason.endDate ||
              existingSeason.endTime >= now && existingTimeZone != desiredTimeZone) {
            desiredSeason.endDate.plusDays(1).toInstant(desiredTimeZone)
          } else {
            existingSeason.endTime
          }

      with(PLANTING_SEASONS) {
        dslContext
            .update(PLANTING_SEASONS)
            .set(END_DATE, desiredSeason.endDate)
            .set(END_TIME, endTime)
            .set(IS_ACTIVE, now >= startTime && now < endTime)
            .set(START_DATE, desiredSeason.startDate)
            .set(START_TIME, startTime)
            .where(ID.eq(desiredSeason.id))
            .execute()
      }

      eventPublisher.publishEvent(
          PlantingSeasonRescheduledEvent(
              plantingSiteId,
              existingSeason.id,
              existingSeason.startDate,
              existingSeason.endDate,
              desiredSeason.startDate,
              desiredSeason.endDate))
    }

    if (seasonsToInsert.isNotEmpty()) {
      plantingSeasonsDao.insert(seasonsToInsert)

      seasonsToInsert.forEach { season ->
        eventPublisher.publishEvent(
            PlantingSeasonScheduledEvent(
                plantingSiteId, season.id!!, season.startDate!!, season.endDate!!))
      }
    }
  }

  fun updatePlantingZone(
      plantingZoneId: PlantingZoneId,
      editFunc: (PlantingZonesRow) -> PlantingZonesRow
  ) {
    requirePermissions { updatePlantingZone(plantingZoneId) }

    val initial =
        plantingZonesDao.fetchOneById(plantingZoneId)
            ?: throw PlantingZoneNotFoundException(plantingZoneId)
    val edited = editFunc(initial)

    with(PLANTING_ZONES) {
      dslContext
          .update(PLANTING_ZONES)
          .set(ERROR_MARGIN, edited.errorMargin)
          .set(EXTRA_PERMANENT_CLUSTERS, edited.extraPermanentClusters)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(NUM_PERMANENT_CLUSTERS, edited.numPermanentClusters)
          .set(NUM_TEMPORARY_PLOTS, edited.numTemporaryPlots)
          .set(STUDENTS_T, edited.studentsT)
          .set(TARGET_PLANTING_DENSITY, edited.targetPlantingDensity)
          .set(VARIANCE, edited.variance)
          .where(ID.eq(plantingZoneId))
          .execute()
    }
  }

  /**
   * Updates information about a planting subzone. The "planting completed time" value, though it's
   * a timestamp, is treated as a flag:
   * - If the existing planting completed time is null and the edited one is non-null, the planting
   *   completed time in the database is set to the current time.
   * - If the existing planting completed time is non-null and the edited one is null, the planting
   *   completed time in the database is cleared.
   * - Otherwise, the existing value is left as-is. That is, repeatedly calling this function with
   *   different non-null planting completed times will not cause the planting completed time in the
   *   database to change.
   */
  fun updatePlantingSubzone(
      plantingSubzoneId: PlantingSubzoneId,
      editFunc: (PlantingSubzonesRow) -> PlantingSubzonesRow
  ) {
    requirePermissions { updatePlantingSubzone(plantingSubzoneId) }

    val initial =
        plantingSubzonesDao.fetchOneById(plantingSubzoneId)
            ?: throw PlantingSubzoneNotFoundException(plantingSubzoneId)
    val edited = editFunc(initial)

    // Don't allow the planting-completed time to be adjusted, just cleared or set.
    val plantingCompletedTime =
        if (edited.plantingCompletedTime != null) initial.plantingCompletedTime ?: clock.instant()
        else null

    with(PLANTING_SUBZONES) {
      dslContext
          .update(PLANTING_SUBZONES)
          .set(PLANTING_COMPLETED_TIME, plantingCompletedTime)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .where(ID.eq(plantingSubzoneId))
          .execute()
    }
  }

  private fun setMonitoringPlotCluster(
      monitoringPlotId: MonitoringPlotId,
      permanentCluster: Int,
      permanentClusterSubplot: Int
  ) {
    with(MONITORING_PLOTS) {
      dslContext
          .update(MONITORING_PLOTS)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(PERMANENT_CLUSTER, permanentCluster)
          .set(PERMANENT_CLUSTER_SUBPLOT, permanentClusterSubplot)
          .where(ID.eq(monitoringPlotId))
          .execute()
    }
  }

  fun movePlantingSite(plantingSiteId: PlantingSiteId, organizationId: OrganizationId) {
    requirePermissions { movePlantingSiteToAnyOrg(plantingSiteId) }

    val userId = currentUser().userId

    log.info("User $userId moving planting site $plantingSiteId to organization $organizationId")

    with(PLANTING_SITES) {
      dslContext
          .update(PLANTING_SITES)
          .set(MODIFIED_BY, userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(ORGANIZATION_ID, organizationId)
          .where(ID.eq(plantingSiteId))
          .execute()
    }
  }

  fun deletePlantingSite(plantingSiteId: PlantingSiteId) {
    requirePermissions { deletePlantingSite(plantingSiteId) }

    // Inform the system that we're about to delete the planting site and that any external
    // resources tied to it should be cleaned up.
    //
    // This is not wrapped in a transaction because listeners are expected to delete external
    // resources and then update the database to remove the references to them; if that happened
    // inside an enclosing transaction, then a listener throwing an exception could cause the system
    // to roll back the updates that recorded the successful removal of external resources by an
    // earlier one.
    //
    // There's an unavoidable tradeoff here: if a listener fails, the planting site data will end up
    // partially deleted.
    eventPublisher.publishEvent(PlantingSiteDeletionStartedEvent(plantingSiteId))

    // Deleting the planting site will trigger cascading deletes of all the dependent data.
    plantingSitesDao.deleteById(plantingSiteId)
  }

  fun assignProject(projectId: ProjectId, plantingSiteIds: Collection<PlantingSiteId>) {
    requirePermissions { readProject(projectId) }

    if (plantingSiteIds.isEmpty()) {
      return
    }

    val projectOrganizationId =
        parentStore.getOrganizationId(projectId) ?: throw ProjectNotFoundException(projectId)
    val hasOtherOrganizationIds =
        dslContext
            .selectOne()
            .from(PLANTING_SITES)
            .where(PLANTING_SITES.ID.`in`(plantingSiteIds))
            .and(PLANTING_SITES.ORGANIZATION_ID.ne(projectOrganizationId))
            .limit(1)
            .fetch()
    if (hasOtherOrganizationIds.isNotEmpty) {
      throw ProjectInDifferentOrganizationException()
    }

    requirePermissions {
      // All planting sites are in the same organization, so it's sufficient to check permissions
      // on just one of them.
      updatePlantingSite(plantingSiteIds.first())
    }

    with(PLANTING_SITES) {
      dslContext
          .update(PLANTING_SITES)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(PROJECT_ID, projectId)
          .where(ID.`in`(plantingSiteIds))
          .execute()
    }
  }

  fun hasSubzonePlantings(plantingSiteId: PlantingSiteId): Boolean {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return dslContext.fetchExists(
        PLANTINGS,
        PLANTINGS.PLANTING_SITE_ID.eq(plantingSiteId),
        PLANTINGS.PLANTING_SUBZONE_ID.isNotNull,
    )
  }

  fun hasPlantings(plantingSiteId: PlantingSiteId): Boolean {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return dslContext.fetchExists(
        PLANTINGS,
        PLANTINGS.PLANTING_SITE_ID.eq(plantingSiteId),
    )
  }

  fun fetchOldestPlantingTime(plantingSiteId: PlantingSiteId): Instant? {
    return dslContext
        .select(DSL.min(PLANTINGS.CREATED_TIME))
        .from(PLANTINGS)
        .where(PLANTINGS.PLANTING_SITE_ID.eq(plantingSiteId))
        .fetchOne(DSL.min(PLANTINGS.CREATED_TIME))
  }

  fun fetchSitesWithSubzonePlantings(condition: Condition): List<PlantingSiteId> {
    requirePermissions { manageNotifications() }

    return dslContext
        .select(PLANTING_SITES.ID)
        .from(PLANTING_SITES)
        .where(condition)
        .andExists(
            DSL.selectOne()
                .from(PLANTINGS)
                .where(PLANTINGS.PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
                .and(PLANTINGS.PLANTING_SUBZONE_ID.isNotNull))
        .fetch(PLANTING_SITES.ID.asNonNullable())
  }

  fun fetchPartiallyPlantedDetailedSitesWithNoPlantingSeasons(
      weeksSinceCreation: Int,
      additionalCondition: Condition
  ): List<PlantingSiteId> {
    requirePermissions { manageNotifications() }

    val maxCreatedTime = clock.instant().minus(weeksSinceCreation * 7L, ChronoUnit.DAYS)

    return dslContext
        .select(PLANTING_SITES.ID)
        .from(PLANTING_SITES)
        .where(additionalCondition)
        .and(PLANTING_SITES.CREATED_TIME.le(maxCreatedTime))
        .andNotExists(
            DSL.selectOne()
                .from(PLANTING_SEASONS)
                .where(PLANTING_SITES.ID.eq(PLANTING_SEASONS.PLANTING_SITE_ID)))
        .andExists(
            DSL.selectOne()
                .from(PLANTING_SUBZONES)
                .where(PLANTING_SITES.ID.eq(PLANTING_SUBZONES.PLANTING_SITE_ID))
                .and(PLANTING_SUBZONES.PLANTING_COMPLETED_TIME.isNull))
        .orderBy(PLANTING_SITES.ID)
        .fetch(PLANTING_SITES.ID.asNonNullable())
  }

  fun fetchPartiallyPlantedDetailedSitesWithNoUpcomingPlantingSeasons(
      weeksSinceLastSeason: Int,
      additionalCondition: Condition
  ): List<PlantingSiteId> {
    requirePermissions { manageNotifications() }

    val maxEndTime = clock.instant().minus(weeksSinceLastSeason * 7L, ChronoUnit.DAYS)

    return dslContext
        .select(PLANTING_SITES.ID)
        .from(PLANTING_SITES)
        .where(additionalCondition)
        .and(
            DSL.field(
                    DSL.select(DSL.max(PLANTING_SEASONS.END_TIME))
                        .from(PLANTING_SEASONS)
                        .where(PLANTING_SITES.ID.eq(PLANTING_SEASONS.PLANTING_SITE_ID)))
                .le(maxEndTime))
        .andExists(
            DSL.selectOne()
                .from(PLANTING_SUBZONES)
                .where(PLANTING_SITES.ID.eq(PLANTING_SUBZONES.PLANTING_SITE_ID))
                .and(PLANTING_SUBZONES.PLANTING_COMPLETED_TIME.isNull))
        .orderBy(PLANTING_SITES.ID)
        .fetch(PLANTING_SITES.ID.asNonNullable())
  }

  fun transitionPlantingSeasons() {
    endPlantingSeasons()
    startPlantingSeasons()
  }

  fun markNotificationComplete(
      plantingSiteId: PlantingSiteId,
      notificationType: NotificationType,
      notificationNumber: Int,
  ) {
    requirePermissions {
      readPlantingSite(plantingSiteId)
      manageNotifications()
    }

    with(PLANTING_SITE_NOTIFICATIONS) {
      dslContext
          .insertInto(PLANTING_SITE_NOTIFICATIONS)
          .set(PLANTING_SITE_ID, plantingSiteId)
          .set(NOTIFICATION_TYPE_ID, notificationType)
          .set(NOTIFICATION_NUMBER, notificationNumber)
          .set(SENT_TIME, clock.instant())
          .execute()
    }
  }

  /**
   * Makes a monitoring plot unavailable for inclusion in future observations.
   *
   * The requested plot's "is available" flag is set to false.
   *
   * If the requested plot is part of a permanent cluster, the cluster is destroyed: the remaining
   * plots in the cluster are updated such that they're no longer in a permanent cluster at all (but
   * are still available for selection as temporary plots), and the highest-numbered permanent
   * cluster in the zone is updated to use the cluster number of the requested plot. In other words,
   * the requested plot's cluster is replaced by the highest-numbered cluster in the zone, if any.
   *
   * @return The plots that were modified. If the requested plot was part of a permanent cluster,
   *   the "added plots" property will be the IDs of the plots in the cluster that was swapped in to
   *   replace the original cluster (or an empty list if there wasn't a replacement cluster
   *   available), and the "removed plots" property will be the IDs of the plots in the same cluster
   *   as the requested plot. If the requested plot wasn't part of a permanent cluster, the "added
   *   plots" property will be empty and the "removed plots" property will only include the
   *   requested plot.
   */
  fun makePlotUnavailable(monitoringPlotId: MonitoringPlotId): ReplacementResult {
    val plotsRow =
        monitoringPlotsDao.fetchOneById(monitoringPlotId)
            ?: throw PlotNotFoundException(monitoringPlotId)
    val subzonesRow =
        plantingSubzonesDao.fetchOneById(plotsRow.plantingSubzoneId!!)
            ?: throw PlantingSubzoneNotFoundException(plotsRow.plantingSubzoneId!!)
    val permanentCluster = plotsRow.permanentCluster
    val plantingZoneId = subzonesRow.plantingZoneId!!

    requirePermissions { updatePlantingSite(subzonesRow.plantingSiteId!!) }

    if (plotsRow.isAvailable == false) {
      return ReplacementResult(emptySet(), emptySet())
    }

    return dslContext.transactionResult { _ ->
      dslContext
          .update(MONITORING_PLOTS)
          .set(MONITORING_PLOTS.IS_AVAILABLE, false)
          .set(MONITORING_PLOTS.MODIFIED_BY, currentUser().userId)
          .set(MONITORING_PLOTS.MODIFIED_TIME, clock.instant())
          .where(MONITORING_PLOTS.ID.eq(monitoringPlotId))
          .execute()

      if (permanentCluster == null) {
        ReplacementResult(
            addedMonitoringPlotIds = emptySet(), removedMonitoringPlotIds = setOf(monitoringPlotId))
      } else {
        // This plot is part of a permanent cluster; we need to make the other plots in this cluster
        // standalone ones and swap a higher-numbered cluster (if any) into this cluster's place in
        // the permanent cluster list.
        val clusterPlotIds = fetchPlotIdsForPermanentCluster(plantingZoneId, permanentCluster)

        val maxClusterInZone = fetchMaxPermanentCluster(plantingZoneId)

        dslContext
            .update(MONITORING_PLOTS)
            .set(MONITORING_PLOTS.MODIFIED_BY, currentUser().userId)
            .set(MONITORING_PLOTS.MODIFIED_TIME, clock.instant())
            .setNull(MONITORING_PLOTS.PERMANENT_CLUSTER)
            .setNull(MONITORING_PLOTS.PERMANENT_CLUSTER_SUBPLOT)
            .where(MONITORING_PLOTS.ID.`in`(clusterPlotIds))
            .execute()

        val replacementClusterPlotIds =
            if (permanentCluster < maxClusterInZone) {
              fetchPlotIdsForPermanentCluster(plantingZoneId, maxClusterInZone)
            } else {
              emptyList()
            }

        if (replacementClusterPlotIds.isNotEmpty()) {
          dslContext
              .update(MONITORING_PLOTS)
              .set(MONITORING_PLOTS.MODIFIED_BY, currentUser().userId)
              .set(MONITORING_PLOTS.MODIFIED_TIME, clock.instant())
              .set(MONITORING_PLOTS.PERMANENT_CLUSTER, permanentCluster)
              .where(MONITORING_PLOTS.ID.`in`(replacementClusterPlotIds))
              .execute()
        }

        ReplacementResult(
            addedMonitoringPlotIds = replacementClusterPlotIds.toSet(),
            removedMonitoringPlotIds = clusterPlotIds.toSet())
      }
    }
  }

  /**
   * Swaps a monitoring plot's permanent cluster position with the highest-numbered one in the zone.
   *
   * If the monitoring plot is not part of a permanent cluster, or is part of the highest-numbered
   * one, does nothing.
   *
   * @return A result whose "added plots" property has the IDs of the monitoring plots in the
   *   previously highest-numbered cluster, and whose "removed plots" property has the IDs of all
   *   the monitoring plots in the requested plot's cluster.
   */
  fun swapWithLastPermanentCluster(monitoringPlotId: MonitoringPlotId): ReplacementResult {
    val plotsRow =
        monitoringPlotsDao.fetchOneById(monitoringPlotId)
            ?: throw PlotNotFoundException(monitoringPlotId)
    val subzonesRow =
        plantingSubzonesDao.fetchOneById(plotsRow.plantingSubzoneId!!)
            ?: throw PlantingSubzoneNotFoundException(plotsRow.plantingSubzoneId!!)
    val plantingZoneId = subzonesRow.plantingZoneId!!

    requirePermissions { updatePlantingSite(subzonesRow.plantingSiteId!!) }

    val permanentCluster =
        plotsRow.permanentCluster ?: return ReplacementResult(emptySet(), emptySet())

    val clusterPlotIds = fetchPlotIdsForPermanentCluster(plantingZoneId, permanentCluster)
    val maxPermanentCluster = fetchMaxPermanentCluster(plantingZoneId)

    if (maxPermanentCluster == permanentCluster) {
      // There's no higher-numbered cluster to swap with this one; do nothing.
      return ReplacementResult(emptySet(), emptySet())
    }

    val maxClusterPlotIds = fetchPlotIdsForPermanentCluster(plantingZoneId, maxPermanentCluster)

    dslContext.transaction { _ ->
      dslContext
          .update(MONITORING_PLOTS)
          .set(MONITORING_PLOTS.PERMANENT_CLUSTER, maxPermanentCluster)
          .where(MONITORING_PLOTS.ID.`in`(clusterPlotIds))
          .execute()

      dslContext
          .update(MONITORING_PLOTS)
          .set(MONITORING_PLOTS.PERMANENT_CLUSTER, permanentCluster)
          .where(MONITORING_PLOTS.ID.`in`(maxClusterPlotIds))
          .execute()
    }

    return ReplacementResult(maxClusterPlotIds.toSet(), clusterPlotIds.toSet())
  }

  /**
   * Ensures that the required number of permanent clusters exists in each of a planting site's
   * zones. There need to be clusters with numbers from 1 to the zone's permanent cluster count.
   */
  fun ensurePermanentClustersExist(plantingSiteId: PlantingSiteId) {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    val userId = currentUser().userId
    val now = clock.instant()

    withLockedPlantingSite(plantingSiteId) {
      val plantingSite = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)
      if (plantingSite.gridOrigin == null) {
        throw IllegalStateException("Planting site $plantingSiteId has no grid origin")
      }

      val geometryFactory = GeometryFactory(PrecisionModel(), plantingSite.gridOrigin.srid)

      plantingSite.plantingZones.forEach { plantingZone ->
        var nextPlotNumber = plantingZone.getMaxPlotName() + 1

        val missingClusterNumbers: List<Int> =
            (1..plantingZone.numPermanentClusters).filterNot {
              plantingZone.permanentClusterExists(it)
            }

        // List of [boundary, cluster number]
        val clusterBoundaries: List<Pair<Polygon, Int>> =
            plantingZone
                .findUnusedSquares(
                    gridOrigin = plantingSite.gridOrigin,
                    sizeMeters = MONITORING_PLOT_SIZE * 2,
                    count = missingClusterNumbers.size,
                    excludeAllPermanentPlots = true,
                    exclusion = plantingSite.exclusion)
                .zip(missingClusterNumbers)

        clusterBoundaries.forEach { (clusterBoundary, clusterNumber) ->
          val westX = clusterBoundary.coordinates[0].x
          val eastX = clusterBoundary.coordinates[2].x
          val southY = clusterBoundary.coordinates[0].y
          val northY = clusterBoundary.coordinates[2].y
          val middleX = clusterBoundary.centroid.x
          val middleY = clusterBoundary.centroid.y
          val clusterPlots =
              listOf(
                  // The order is important here: southwest, southeast, northeast, northwest
                  // (the position in this list turns into the cluster subplot number).
                  geometryFactory.createRectangle(westX, southY, middleX, middleY),
                  geometryFactory.createRectangle(middleX, southY, eastX, middleY),
                  geometryFactory.createRectangle(middleX, middleY, eastX, northY),
                  geometryFactory.createRectangle(westX, middleY, middleX, northY),
              )

          clusterPlots.forEachIndexed { plotIndex, plotBoundary ->
            val existingPlot = plantingZone.findMonitoringPlot(plotBoundary.centroid)

            if (existingPlot != null) {
              if (existingPlot.permanentCluster != null) {
                throw IllegalStateException("Cannot place new permanent cluster over existing one")
              }

              setMonitoringPlotCluster(existingPlot.id, clusterNumber, plotIndex + 1)
            } else {
              val subzone =
                  plantingZone.findPlantingSubzone(plotBoundary)
                      ?: throw IllegalStateException(
                          "Planting zone ${plantingZone.id} not fully covered by subzones")
              val plotNumber = nextPlotNumber++

              monitoringPlotsDao.insert(
                  MonitoringPlotsRow(
                      boundary = plotBoundary,
                      createdBy = userId,
                      createdTime = now,
                      fullName = "${subzone.fullName}-$plotNumber",
                      modifiedBy = userId,
                      modifiedTime = now,
                      name = "$plotNumber",
                      permanentCluster = clusterNumber,
                      permanentClusterSubplot = plotIndex + 1,
                      plantingSubzoneId = subzone.id))
            }
          }
        }
      }
    }
  }

  private val plantingSeasonsMultiset =
      DSL.multiset(
              DSL.select(
                      PLANTING_SEASONS.END_DATE,
                      PLANTING_SEASONS.END_TIME,
                      PLANTING_SEASONS.ID,
                      PLANTING_SEASONS.IS_ACTIVE,
                      PLANTING_SEASONS.START_DATE,
                      PLANTING_SEASONS.START_TIME)
                  .from(PLANTING_SEASONS)
                  .where(PLANTING_SITES.ID.eq(PLANTING_SEASONS.PLANTING_SITE_ID))
                  .orderBy(PLANTING_SEASONS.START_DATE))
          .convertFrom { result ->
            result.map { record ->
              ExistingPlantingSeasonModel(
                  endDate = record[PLANTING_SEASONS.END_DATE]!!,
                  endTime = record[PLANTING_SEASONS.END_TIME]!!,
                  id = record[PLANTING_SEASONS.ID]!!,
                  isActive = record[PLANTING_SEASONS.IS_ACTIVE]!!,
                  startDate = record[PLANTING_SEASONS.START_DATE]!!,
                  startTime = record[PLANTING_SEASONS.START_TIME]!!,
              )
            }
          }

  private val monitoringPlotsMultiset =
      DSL.multiset(
              DSL.select(
                      MONITORING_PLOTS.ID,
                      MONITORING_PLOTS.FULL_NAME,
                      MONITORING_PLOTS.IS_AVAILABLE,
                      MONITORING_PLOTS.NAME,
                      MONITORING_PLOTS.PERMANENT_CLUSTER,
                      MONITORING_PLOTS.PERMANENT_CLUSTER_SUBPLOT,
                      monitoringPlotBoundaryField)
                  .from(MONITORING_PLOTS)
                  .where(PLANTING_SUBZONES.ID.eq(MONITORING_PLOTS.PLANTING_SUBZONE_ID))
                  .orderBy(MONITORING_PLOTS.FULL_NAME))
          .convertFrom { result ->
            result.map { record ->
              MonitoringPlotModel(
                  boundary = record[monitoringPlotBoundaryField]!! as Polygon,
                  id = record[MONITORING_PLOTS.ID]!!,
                  isAvailable = record[MONITORING_PLOTS.IS_AVAILABLE]!!,
                  fullName = record[MONITORING_PLOTS.FULL_NAME]!!,
                  name = record[MONITORING_PLOTS.NAME]!!,
                  permanentCluster = record[MONITORING_PLOTS.PERMANENT_CLUSTER],
                  permanentClusterSubplot = record[MONITORING_PLOTS.PERMANENT_CLUSTER_SUBPLOT],
              )
            }
          }

  private fun plantingSubzonesMultiset(
      depth: PlantingSiteDepth
  ): Field<List<PlantingSubzoneModel>> {
    val plotsField = if (depth == PlantingSiteDepth.Plot) monitoringPlotsMultiset else null

    return DSL.multiset(
            DSL.select(
                    PLANTING_SUBZONES.AREA_HA,
                    PLANTING_SUBZONES.PLANTING_COMPLETED_TIME,
                    PLANTING_SUBZONES.ID,
                    PLANTING_SUBZONES.FULL_NAME,
                    PLANTING_SUBZONES.NAME,
                    plantingSubzoneBoundaryField,
                    plotsField)
                .from(PLANTING_SUBZONES)
                .where(PLANTING_ZONES.ID.eq(PLANTING_SUBZONES.PLANTING_ZONE_ID))
                .orderBy(PLANTING_SUBZONES.FULL_NAME))
        .convertFrom { result ->
          result.map { record: Record ->
            PlantingSubzoneModel(
                record[PLANTING_SUBZONES.AREA_HA]!!,
                record[plantingSubzoneBoundaryField]!! as MultiPolygon,
                record[PLANTING_SUBZONES.ID]!!,
                record[PLANTING_SUBZONES.FULL_NAME]!!,
                record[PLANTING_SUBZONES.NAME]!!,
                record[PLANTING_SUBZONES.PLANTING_COMPLETED_TIME],
                plotsField?.let { record[it] } ?: emptyList(),
            )
          }
        }
  }

  private fun plantingZonesMultiset(depth: PlantingSiteDepth): Field<List<PlantingZoneModel>> {
    val subzonesField =
        if (depth == PlantingSiteDepth.Subzone || depth == PlantingSiteDepth.Plot) {
          plantingSubzonesMultiset(depth)
        } else {
          null
        }

    return DSL.multiset(
            DSL.select(
                    PLANTING_ZONES.AREA_HA,
                    PLANTING_ZONES.ERROR_MARGIN,
                    PLANTING_ZONES.EXTRA_PERMANENT_CLUSTERS,
                    PLANTING_ZONES.ID,
                    PLANTING_ZONES.NAME,
                    PLANTING_ZONES.NUM_PERMANENT_CLUSTERS,
                    PLANTING_ZONES.NUM_TEMPORARY_PLOTS,
                    PLANTING_ZONES.STUDENTS_T,
                    PLANTING_ZONES.TARGET_PLANTING_DENSITY,
                    PLANTING_ZONES.VARIANCE,
                    plantingZonesBoundaryField,
                    subzonesField)
                .from(PLANTING_ZONES)
                .where(PLANTING_SITES.ID.eq(PLANTING_ZONES.PLANTING_SITE_ID))
                .orderBy(PLANTING_ZONES.NAME))
        .convertFrom { result ->
          result.map { record: Record ->
            PlantingZoneModel(
                record[PLANTING_ZONES.AREA_HA]!!,
                record[plantingZonesBoundaryField]!! as MultiPolygon,
                record[PLANTING_ZONES.ERROR_MARGIN]!!,
                record[PLANTING_ZONES.EXTRA_PERMANENT_CLUSTERS]!!,
                record[PLANTING_ZONES.ID]!!,
                record[PLANTING_ZONES.NAME]!!,
                record[PLANTING_ZONES.NUM_PERMANENT_CLUSTERS]!!,
                record[PLANTING_ZONES.NUM_TEMPORARY_PLOTS]!!,
                subzonesField?.let { record[it] } ?: emptyList(),
                record[PLANTING_ZONES.STUDENTS_T]!!,
                record[PLANTING_ZONES.TARGET_PLANTING_DENSITY]!!,
                record[PLANTING_ZONES.VARIANCE]!!,
            )
          }
        }
  }

  private fun fetchPlotIdsForPermanentCluster(
      plantingZoneId: PlantingZoneId,
      permanentCluster: Int
  ): List<MonitoringPlotId> {
    return dslContext
        .select(MONITORING_PLOTS.ID)
        .from(MONITORING_PLOTS)
        .join(PLANTING_SUBZONES)
        .on(MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
        .where(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(plantingZoneId))
        .and(MONITORING_PLOTS.PERMANENT_CLUSTER.eq(permanentCluster))
        .fetch(MONITORING_PLOTS.ID.asNonNullable())
  }

  private fun fetchMaxPermanentCluster(plantingZoneId: PlantingZoneId): Int {
    return dslContext
        .select(DSL.max(MONITORING_PLOTS.PERMANENT_CLUSTER))
        .from(MONITORING_PLOTS)
        .join(PLANTING_SUBZONES)
        .on(MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
        .where(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(plantingZoneId))
        .fetchOne()
        ?.value1() ?: throw IllegalStateException("Could not query zone's permanent clusters")
  }

  private fun validatePlantingSeasons(
      desiredPlantingSeasons: Collection<UpdatedPlantingSeasonModel>,
      existingPlantingSeasons: Map<PlantingSeasonId, ExistingPlantingSeasonModel>,
      todayAtSite: LocalDate,
  ) {
    if (desiredPlantingSeasons.isNotEmpty()) {
      desiredPlantingSeasons.forEach { desiredSeason ->
        desiredSeason.validate(todayAtSite)
        desiredSeason.id
            ?.let { existingPlantingSeasons[it] }
            ?.let { existingSeason ->
              if (existingSeason.endDate < todayAtSite &&
                  (existingSeason.startDate != desiredSeason.startDate ||
                      existingSeason.endDate != desiredSeason.endDate)) {
                throw CannotUpdatePastPlantingSeasonException(
                    existingSeason.id, existingSeason.endDate)
              }
            }
      }

      desiredPlantingSeasons
          .sortedBy { it.startDate }
          .reduce { previous, next ->
            if (next.startDate <= previous.endDate) {
              throw PlantingSeasonsOverlapException(
                  previous.startDate, previous.endDate, next.startDate, next.endDate)
            }

            next
          }
    }
  }

  private fun startPlantingSeason(
      plantingSiteId: PlantingSiteId,
      plantingSeasonId: PlantingSeasonId
  ) {
    dslContext.transaction { _ ->
      val rowsUpdated =
          dslContext
              .update(PLANTING_SEASONS)
              .set(PLANTING_SEASONS.IS_ACTIVE, true)
              .where(PLANTING_SEASONS.ID.eq(plantingSeasonId))
              .and(PLANTING_SEASONS.PLANTING_SITE_ID.eq(plantingSiteId))
              .and(PLANTING_SEASONS.IS_ACTIVE.isFalse)
              .execute()

      if (rowsUpdated > 0) {
        log.info("Planting season $plantingSeasonId at site $plantingSiteId has started")

        eventPublisher.publishEvent(PlantingSeasonStartedEvent(plantingSiteId, plantingSeasonId))
      }
    }
  }

  private fun startPlantingSeasons() {
    val now = clock.instant()

    dslContext
        .select(
            PLANTING_SEASONS.PLANTING_SITE_ID.asNonNullable(), PLANTING_SEASONS.ID.asNonNullable())
        .from(PLANTING_SEASONS)
        .where(PLANTING_SEASONS.START_TIME.le(now))
        .and(PLANTING_SEASONS.END_TIME.gt(now))
        .and(PLANTING_SEASONS.IS_ACTIVE.isFalse)
        .fetch()
        .forEach { (plantingSiteId, plantingSeasonId) ->
          startPlantingSeason(plantingSiteId, plantingSeasonId)
        }
  }

  private fun endPlantingSeason(
      plantingSiteId: PlantingSiteId,
      plantingSeasonId: PlantingSeasonId
  ) {
    dslContext.transaction { _ ->
      val rowsUpdated =
          dslContext
              .update(PLANTING_SEASONS)
              .set(PLANTING_SEASONS.IS_ACTIVE, false)
              .where(PLANTING_SEASONS.ID.eq(plantingSeasonId))
              .and(PLANTING_SEASONS.PLANTING_SITE_ID.eq(plantingSiteId))
              .and(PLANTING_SEASONS.IS_ACTIVE.isTrue)
              .execute()

      if (rowsUpdated > 0) {
        log.info("Planting season $plantingSeasonId at site $plantingSiteId has ended")

        deleteRecurringPlantingSeasonNotifications(plantingSiteId)
        eventPublisher.publishEvent(PlantingSeasonEndedEvent(plantingSiteId, plantingSeasonId))
      }
    }
  }

  private fun endPlantingSeasons() {
    dslContext
        .select(
            PLANTING_SEASONS.PLANTING_SITE_ID.asNonNullable(), PLANTING_SEASONS.ID.asNonNullable())
        .from(PLANTING_SEASONS)
        .where(PLANTING_SEASONS.END_TIME.le(clock.instant()))
        .and(PLANTING_SEASONS.IS_ACTIVE.isTrue)
        .fetch()
        .forEach { (plantingSiteId, plantingSeasonId) ->
          endPlantingSeason(plantingSiteId, plantingSeasonId)
        }
  }

  /**
   * Deletes the records about planting-season-related notifications that can be sent for each
   * planting season. This is so that when the next planting season happens, the existing records
   * don't cause the system to think that it has already generated the necessary notifications.
   */
  private fun deleteRecurringPlantingSeasonNotifications(plantingSiteId: PlantingSiteId) {
    dslContext
        .deleteFrom(PLANTING_SITE_NOTIFICATIONS)
        .where(PLANTING_SITE_NOTIFICATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
        .and(
            PLANTING_SITE_NOTIFICATIONS.NOTIFICATION_TYPE_ID.`in`(
                NotificationType.SchedulePlantingSeason,
            ))
        .execute()
  }

  /**
   * Acquires a row lock on a planting site and executes a function in a transaction with the lock
   * held.
   */
  private fun withLockedPlantingSite(plantingSiteId: PlantingSiteId, func: () -> Unit) {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    dslContext.transaction { _ ->
      val rowsLocked =
          dslContext
              .selectOne()
              .from(PLANTING_SITES)
              .where(PLANTING_SITES.ID.eq(plantingSiteId))
              .forUpdate()
              .execute()

      if (rowsLocked != 1) {
        throw PlantingSiteNotFoundException(plantingSiteId)
      }

      func()
    }
  }
}
