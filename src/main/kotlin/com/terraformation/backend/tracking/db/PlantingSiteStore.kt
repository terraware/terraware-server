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
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.daos.MonitoringPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSubzonesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingZonesDao
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_NOTIFICATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_POPULATIONS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.event.PlantingSiteDeletionStartedEvent
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteReportedPlantTotals
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.tracking.model.ReplacementResult
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
import org.jooq.impl.DSL
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.springframework.context.ApplicationEventPublisher

@Named
class PlantingSiteStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val monitoringPlotsDao: MonitoringPlotsDao,
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
        }
        ?: PlantingSiteReportedPlantTotals(plantingSiteId, zoneTotals, 0, 0, 0)
  }

  /**
   * Returns a set of permanent monitoring plots for a planting zone. Only plots in subzones that
   * are known to have been planted are returned, meaning there may be fewer plots than requested
   * (or even none at all). If a cluster of permanent plots includes plots in more than one subzone,
   * all the subzones must be planted for the cluster to be selected.
   */
  fun fetchPermanentPlotIds(
      plantingZoneId: PlantingZoneId,
      maxPermanentCluster: Int,
      minPermanentCluster: Int = 1,
  ): Set<MonitoringPlotId> {
    requirePermissions { readPlantingZone(plantingZoneId) }

    val clusters =
        dslContext
            .select(MONITORING_PLOTS.PERMANENT_CLUSTER, MONITORING_PLOTS.ID)
            .from(MONITORING_PLOTS)
            .where(MONITORING_PLOTS.plantingSubzones.PLANTING_ZONE_ID.eq(plantingZoneId))
            .and(
                MONITORING_PLOTS.PERMANENT_CLUSTER.between(
                    minPermanentCluster, maxPermanentCluster))
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

      if (initialTimeZone != editedTimeZone) {
        eventPublisher.publishEvent(
            PlantingSiteTimeZoneChangedEvent(edited, initialTimeZone, editedTimeZone))
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
        ?.value1()
        ?: throw IllegalStateException("Could not query zone's permanent clusters")
  }
}
