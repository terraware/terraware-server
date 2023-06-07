package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
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
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import java.math.BigDecimal
import java.time.InstantSource
import java.time.Month
import java.time.ZoneId
import javax.inject.Named
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
  ): PlantingSiteModel {
    requirePermissions { createPlantingSite(organizationId) }

    val now = clock.instant()
    val plantingSitesRow =
        PlantingSitesRow(
            createdBy = currentUser().userId,
            createdTime = now,
            description = description,
            modifiedBy = currentUser().userId,
            modifiedTime = now,
            name = name,
            organizationId = organizationId,
            plantingSeasonEndMonth = plantingSeasonEndMonth,
            plantingSeasonStartMonth = plantingSeasonStartMonth,
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

    val initial = fetchSiteById(plantingSiteId, PlantingSiteDepth.Site)
    val edited = editFunc(initial)

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
            .set(TIME_ZONE, edited.timeZone)
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
   * Updates information about a planting subzone. The "finished planting time" value, though it's a
   * timestamp, is treated as a flag:
   * - If the existing finished planting time is null and the edited one is non-null, the finished
   *   planting time in the database is set to the current time.
   * - If the existing finished planting time is non-null and the edited one is null, the finished
   *   planting time in the database is cleared.
   * - Otherwise, the existing value is left as-is. That is, repeatedly calling this function with
   *   different non-null finished planting times will not cause the finished planting time in the
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

    // Don't allow the finished-planting time to be adjusted, just cleared or set.
    val finishedPlantingTime =
        if (edited.finishedPlantingTime != null) initial.finishedPlantingTime ?: clock.instant()
        else null

    with(PLANTING_SUBZONES) {
      dslContext
          .update(PLANTING_SUBZONES)
          .set(FINISHED_PLANTING_TIME, finishedPlantingTime)
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
                    PLANTING_SUBZONES.FINISHED_PLANTING_TIME,
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
                record[PLANTING_SUBZONES.FINISHED_PLANTING_TIME],
                record[PLANTING_SUBZONES.ID]!!,
                record[PLANTING_SUBZONES.FULL_NAME]!!,
                record[PLANTING_SUBZONES.NAME]!!,
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
