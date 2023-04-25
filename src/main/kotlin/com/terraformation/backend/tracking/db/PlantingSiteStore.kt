package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitesDao
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.transformSrid
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import java.time.InstantSource
import java.time.ZoneId
import javax.inject.Named
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.locationtech.jts.geom.MultiPolygon

@Named
class PlantingSiteStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val plantingSitesDao: PlantingSitesDao,
) {
  private val log = perClassLogger()

  private val monitoringPlotBoundaryField =
      MONITORING_PLOTS.BOUNDARY.transformSrid(SRID.LONG_LAT).forMultiset()
  private val plantingSubzoneBoundaryField =
      PLANTING_SUBZONES.BOUNDARY.transformSrid(SRID.LONG_LAT).forMultiset()
  private val plantingSitesBoundaryField = PLANTING_SITES.BOUNDARY.transformSrid(SRID.LONG_LAT)
  private val plantingZonesBoundaryField =
      PLANTING_ZONES.BOUNDARY.transformSrid(SRID.LONG_LAT).forMultiset()

  fun fetchSiteById(
      plantingSiteId: PlantingSiteId,
      includePlots: Boolean = false,
  ): PlantingSiteModel {
    requirePermissions { readPlantingSite(plantingSiteId) }

    val zonesField = plantingZonesMultiset(includePlots)

    return dslContext
        .select(PLANTING_SITES.asterisk(), plantingSitesBoundaryField, zonesField)
        .from(PLANTING_SITES)
        .where(PLANTING_SITES.ID.eq(plantingSiteId))
        .fetchOne { record -> PlantingSiteModel(record, plantingSitesBoundaryField, zonesField) }
        ?: throw PlantingSiteNotFoundException(plantingSiteId)
  }

  fun fetchSitesByOrganizationId(
      organizationId: OrganizationId,
      includeZones: Boolean = false,
  ): List<PlantingSiteModel> {
    requirePermissions { readOrganization(organizationId) }

    val zonesField = if (includeZones) plantingZonesMultiset(false) else null

    return dslContext
        .select(PLANTING_SITES.asterisk(), plantingSitesBoundaryField, zonesField)
        .from(PLANTING_SITES)
        .where(PLANTING_SITES.ORGANIZATION_ID.eq(organizationId))
        .orderBy(PLANTING_SITES.ID)
        .fetch { PlantingSiteModel(it, plantingSitesBoundaryField, zonesField) }
  }

  fun createPlantingSite(
      organizationId: OrganizationId,
      name: String,
      description: String?,
      timeZone: ZoneId?,
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
            timeZone = timeZone,
        )

    plantingSitesDao.insert(plantingSitesRow)

    return fetchSiteById(plantingSitesRow.id!!)
  }

  fun updatePlantingSite(
      plantingSiteId: PlantingSiteId,
      name: String,
      description: String?,
      timeZone: ZoneId? = null,
  ) {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    with(PLANTING_SITES) {
      dslContext
          .update(PLANTING_SITES)
          .set(DESCRIPTION, description)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(NAME, name)
          .set(TIME_ZONE, timeZone)
          .where(ID.eq(plantingSiteId))
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
                      monitoringPlotBoundaryField)
                  .from(MONITORING_PLOTS)
                  .where(PLANTING_SUBZONES.ID.eq(MONITORING_PLOTS.PLANTING_SUBZONE_ID))
                  .orderBy(MONITORING_PLOTS.FULL_NAME))
          .convertFrom { result ->
            result.map { record ->
              MonitoringPlotModel(
                  record[monitoringPlotBoundaryField]!! as MultiPolygon,
                  record[MONITORING_PLOTS.ID]!!,
                  record[MONITORING_PLOTS.FULL_NAME]!!,
                  record[MONITORING_PLOTS.NAME]!!)
            }
          }

  private fun plantingSubzonesMultiset(includePlots: Boolean): Field<List<PlantingSubzoneModel>> {
    val plotsField = if (includePlots) monitoringPlotsMultiset else null

    return DSL.multiset(
            DSL.select(
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
                record[plantingSubzoneBoundaryField]!! as MultiPolygon,
                record[PLANTING_SUBZONES.ID]!!,
                record[PLANTING_SUBZONES.FULL_NAME]!!,
                record[PLANTING_SUBZONES.NAME]!!,
                plotsField?.let { record[it] } ?: emptyList(),
            )
          }
        }
  }

  private fun plantingZonesMultiset(includePlots: Boolean): Field<List<PlantingZoneModel>> {
    val subzonesField = plantingSubzonesMultiset(includePlots)

    return DSL.multiset(
            DSL.select(
                    PLANTING_ZONES.ID,
                    PLANTING_ZONES.NAME,
                    plantingZonesBoundaryField,
                    subzonesField)
                .from(PLANTING_ZONES)
                .where(PLANTING_SITES.ID.eq(PLANTING_ZONES.PLANTING_SITE_ID))
                .orderBy(PLANTING_ZONES.NAME))
        .convertFrom { result ->
          result.map { record ->
            PlantingZoneModel(
                record[plantingZonesBoundaryField]!! as MultiPolygon,
                record[PLANTING_ZONES.ID]!!,
                record[PLANTING_ZONES.NAME]!!,
                record[subzonesField] ?: emptyList(),
            )
          }
        }
  }
}
