package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSubzonesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingZonesDao
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.db.tracking.tables.records.PlantingSitesRecord
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_POPULATIONS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteReportedPlantTotals
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.Instant
import java.time.InstantSource
import java.time.Month
import java.time.ZoneId
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.springframework.context.ApplicationEventPublisher

@Named
class PlantingSiteStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val parentStore: ParentStore,
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

    val zonesField = if (depth != PlantingSiteDepth.Site) plantingZonesMultiset(depth) else null

    return dslContext
        .select(PLANTING_SITES.asterisk(), zonesField)
        .from(PLANTING_SITES)
        .where(PLANTING_SITES.ID.eq(plantingSiteId))
        .fetchOne { record -> PlantingSiteModel(record, zonesField) }
        ?: throw PlantingSiteNotFoundException(plantingSiteId)
  }

  fun fetchSitesByOrganizationId(
      organizationId: OrganizationId,
      depth: PlantingSiteDepth = PlantingSiteDepth.Site,
  ): List<PlantingSiteModel> {
    requirePermissions { readOrganization(organizationId) }

    val zonesField =
        if (depth != PlantingSiteDepth.Site) {
          plantingZonesMultiset(depth)
        } else {
          null
        }

    return dslContext
        .select(PLANTING_SITES.asterisk(), zonesField)
        .from(PLANTING_SITES)
        .where(PLANTING_SITES.ORGANIZATION_ID.eq(organizationId))
        .orderBy(PLANTING_SITES.ID)
        .fetch { PlantingSiteModel(it, zonesField) }
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

    return dslContext
        .select(siteTotalSinceField, siteTotalPlantsField)
        .from(PLANTING_SITE_POPULATIONS)
        .where(PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
        .fetchOne { record ->
          PlantingSiteReportedPlantTotals(
              id = plantingSiteId,
              plantingZones = zoneTotals,
              plantsSinceLastObservation = record[siteTotalSinceField]?.toInt() ?: 0,
              totalPlants = record[siteTotalPlantsField]?.toInt() ?: 0,
          )
        }
        ?: PlantingSiteReportedPlantTotals(plantingSiteId, zoneTotals, 0, 0)
  }

  /**
   * Returns a set of permanent monitoring plots for a planting zone. Only plots in subzones that
   * are known to have been planted are returned, meaning there may be fewer plots than requested
   * (or even none at all). If a cluster of permanent plots includes plots in more than one subzone,
   * all the subzones must be planted for the cluster to be selected.
   */
  fun fetchPermanentPlotIds(
      plantingZoneId: PlantingZoneId,
      numClusters: Int
  ): Set<MonitoringPlotId> {
    requirePermissions { readPlantingZone(plantingZoneId) }

    val clusters =
        dslContext
            .select(MONITORING_PLOTS.PERMANENT_CLUSTER, MONITORING_PLOTS.ID)
            .from(MONITORING_PLOTS)
            .where(MONITORING_PLOTS.plantingSubzones.PLANTING_ZONE_ID.eq(plantingZoneId))
            .and(MONITORING_PLOTS.PERMANENT_CLUSTER.le(numClusters))
            .and(
                MONITORING_PLOTS.PLANTING_SUBZONE_ID.`in`(
                    DSL.select(PLANTING_SUBZONES.ID)
                        .from(PLANTING_SUBZONES)
                        .join(PLANTINGS)
                        .on(PLANTING_SUBZONES.ID.eq(PLANTINGS.PLANTING_SUBZONE_ID))
                        .where(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(plantingZoneId))
                        .groupBy(PLANTING_SUBZONES.ID)
                        .having(DSL.sum(PLANTINGS.NUM_PLANTS).gt(BigDecimal.ZERO))))
            .fetchGroups(MONITORING_PLOTS.PERMANENT_CLUSTER, MONITORING_PLOTS.ID.asNonNullable())

    // Only return clusters where all four plots are in planted subzones. Non-planted ones will have
    // been filtered out by the query, so this is just all clusters where the query returned four
    // plot IDs.
    return clusters.filterValues { it.size == 4 }.values.flatten().toSet()
  }

  fun createPlantingSite(
      organizationId: OrganizationId,
      name: String,
      description: String?,
      timeZone: ZoneId?,
      plantingSeasonEndMonth: Month? = null,
      plantingSeasonStartMonth: Month? = null,
      projectId: ProjectId?,
      boundary: MultiPolygon? = null,
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
            plantingSeasonEndMonth = plantingSeasonEndMonth,
            plantingSeasonStartMonth = plantingSeasonStartMonth,
            projectId = projectId,
            timeZone = timeZone,
        )

    plantingSitesDao.insert(plantingSitesRow)

    return fetchSiteById(plantingSitesRow.id!!, PlantingSiteDepth.Site)
  }

  fun updatePlantingSite(
      plantingSiteId: PlantingSiteId,
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

    dslContext.transaction { _ ->
      with(PLANTING_SITES) {
        dslContext
            .update(PLANTING_SITES)
            .set(DESCRIPTION, edited.description)
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, clock.instant())
            .set(NAME, edited.name)
            .set(PLANTING_SEASON_END_MONTH, edited.plantingSeasonEndMonth)
            .set(PLANTING_SEASON_START_MONTH, edited.plantingSeasonStartMonth)
            .set(PROJECT_ID, edited.projectId)
            .set(TIME_ZONE, edited.timeZone)
            .apply {
              // Boundaries can only be updated on simple planting sites.
              if (initial.plantingZones.isEmpty()) set(BOUNDARY, edited.boundary)
            }
            .where(ID.eq(plantingSiteId))
            .execute()
      }

      if (initial.timeZone != edited.timeZone) {
        eventPublisher.publishEvent(PlantingSiteTimeZoneChangedEvent(edited))
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

  fun fetchOldestPlantingTime(plantingSiteId: PlantingSiteId): Instant? {
    return dslContext
        .select(DSL.min(PLANTINGS.CREATED_TIME))
        .from(PLANTINGS)
        .where(PLANTINGS.PLANTING_SITE_ID.eq(plantingSiteId))
        .fetchOne(DSL.min(PLANTINGS.CREATED_TIME))
  }

  fun fetchNonNotifiedSitesToScheduleObservations(): List<PlantingSiteId> {
    requirePermissions { manageNotifications() }

    return fetchSitesWithSubzonePlantings(
        DSL.condition(PLANTING_SITES.SCHEDULE_OBSERVATION_NOTIFICATION_SENT_TIME.isNull))
  }

  fun fetchNonNotifiedSitesToRemindSchedulingObservations(): List<PlantingSiteId> {
    requirePermissions { manageNotifications() }

    return fetchSitesWithSubzonePlantings(
        DSL.condition(PLANTING_SITES.SCHEDULE_OBSERVATION_NOTIFICATION_SENT_TIME.isNotNull)
            .and(PLANTING_SITES.SCHEDULE_OBSERVATION_REMINDER_NOTIFICATION_SENT_TIME.isNull))
  }

  fun fetchNonNotifiedSitesForObservationNotScheduledFirstNotification(): List<PlantingSiteId> {
    requirePermissions { manageNotifications() }

    return fetchSitesWithSubzonePlantings(
        DSL.condition(PLANTING_SITES.OBSERVATION_NOT_SCHEDULED_FIRST_NOTIFICATION_SENT_TIME.isNull))
  }

  fun fetchNonNotifiedSitesForObservationNotScheduledSecondNotification(): List<PlantingSiteId> {
    requirePermissions { manageNotifications() }

    return fetchSitesWithSubzonePlantings(
        DSL.condition(
                PLANTING_SITES.OBSERVATION_NOT_SCHEDULED_FIRST_NOTIFICATION_SENT_TIME.isNotNull)
            .and(PLANTING_SITES.OBSERVATION_NOT_SCHEDULED_SECOND_NOTIFICATION_SENT_TIME.isNull))
  }

  fun markScheduleObservationNotificationComplete(plantingSiteId: PlantingSiteId) {
    requirePermissions { manageNotifications() }

    markNotificationComplete(
        plantingSiteId, PLANTING_SITES.SCHEDULE_OBSERVATION_NOTIFICATION_SENT_TIME)
  }

  fun markScheduleObservationReminderNotificationComplete(plantingSiteId: PlantingSiteId) {
    requirePermissions { manageNotifications() }

    markNotificationComplete(
        plantingSiteId, PLANTING_SITES.SCHEDULE_OBSERVATION_REMINDER_NOTIFICATION_SENT_TIME)
  }

  fun markObservationNotScheduledFirstNotificationComplete(plantingSiteId: PlantingSiteId) {
    requirePermissions { manageNotifications() }

    markNotificationComplete(
        plantingSiteId, PLANTING_SITES.OBSERVATION_NOT_SCHEDULED_FIRST_NOTIFICATION_SENT_TIME)
  }

  fun markObservationNotScheduledSecondNotificationComplete(plantingSiteId: PlantingSiteId) {
    requirePermissions { manageNotifications() }

    markNotificationComplete(
        plantingSiteId, PLANTING_SITES.OBSERVATION_NOT_SCHEDULED_SECOND_NOTIFICATION_SENT_TIME)
  }

  private fun fetchSitesWithSubzonePlantings(condition: Condition): List<PlantingSiteId> {
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

  private fun markNotificationComplete(
      plantingSiteId: PlantingSiteId,
      notificationProperty: TableField<PlantingSitesRecord, Instant?>
  ) {
    dslContext
        .update(PLANTING_SITES)
        .set(notificationProperty, clock.instant())
        .where(PLANTING_SITES.ID.eq(plantingSiteId))
        .execute()
  }

  private val monitoringPlotsMultiset =
      DSL.multiset(
              DSL.select(
                      MONITORING_PLOTS.ID,
                      MONITORING_PLOTS.FULL_NAME,
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
}
