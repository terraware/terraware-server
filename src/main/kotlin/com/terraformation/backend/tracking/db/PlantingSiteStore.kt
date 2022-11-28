package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitesDao
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLOTS
import com.terraformation.backend.db.transformSrid
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.tracking.model.PlotModel
import java.time.InstantSource
import javax.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.locationtech.jts.geom.MultiPolygon

@Named
class PlantingSiteStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val plantingSitesDao: PlantingSitesDao,
) {
  private val log = perClassLogger()

  private val plotsBoundaryField = PLOTS.BOUNDARY.transformSrid(SRID.LONG_LAT).forMultiset()
  private val plantingSitesBoundaryField = PLANTING_SITES.BOUNDARY.transformSrid(SRID.LONG_LAT)
  private val plantingZonesBoundaryField =
      PLANTING_ZONES.BOUNDARY.transformSrid(SRID.LONG_LAT).forMultiset()

  private val plotsMultiset =
      DSL.multiset(
              DSL.select(PLOTS.ID, PLOTS.FULL_NAME, PLOTS.NAME, plotsBoundaryField)
                  .from(PLOTS)
                  .where(PLANTING_ZONES.ID.eq(PLOTS.PLANTING_ZONE_ID))
                  .orderBy(PLOTS.FULL_NAME))
          .convertFrom { result ->
            result.map { record ->
              PlotModel(
                  record[plotsBoundaryField]!! as MultiPolygon,
                  record[PLOTS.ID]!!,
                  record[PLOTS.FULL_NAME]!!,
                  record[PLOTS.NAME]!!)
            }
          }

  private val plantingZonesMultiset =
      DSL.multiset(
              DSL.select(
                      PLANTING_ZONES.ID,
                      PLANTING_ZONES.NAME,
                      plantingZonesBoundaryField,
                      plotsMultiset)
                  .from(PLANTING_ZONES)
                  .where(PLANTING_SITES.ID.eq(PLANTING_ZONES.PLANTING_SITE_ID))
                  .orderBy(PLANTING_ZONES.NAME))
          .convertFrom { result ->
            result.map { record ->
              PlantingZoneModel(
                  record[plantingZonesBoundaryField]!! as MultiPolygon,
                  record[PLANTING_ZONES.ID]!!,
                  record[PLANTING_ZONES.NAME]!!,
                  record[plotsMultiset] ?: emptyList(),
              )
            }
          }

  fun fetchSiteById(plantingSiteId: PlantingSiteId): PlantingSiteModel {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return dslContext
        .select(PLANTING_SITES.asterisk(), plantingSitesBoundaryField, plantingZonesMultiset)
        .from(PLANTING_SITES)
        .where(PLANTING_SITES.ID.eq(plantingSiteId))
        .fetchOne { record ->
          PlantingSiteModel(record, plantingSitesBoundaryField, plantingZonesMultiset)
        }
        ?: throw PlantingSiteNotFoundException(plantingSiteId)
  }

  fun fetchSitesByOrganizationId(
      organizationId: OrganizationId,
      includeZones: Boolean = false
  ): List<PlantingSiteModel> {
    requirePermissions { readOrganization(organizationId) }

    val zonesField = if (includeZones) plantingZonesMultiset else null

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
        )

    plantingSitesDao.insert(plantingSitesRow)

    return fetchSiteById(plantingSitesRow.id!!)
  }

  fun updatePlantingSite(plantingSiteId: PlantingSiteId, name: String, description: String?) {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    with(PLANTING_SITES) {
      dslContext
          .update(PLANTING_SITES)
          .set(DESCRIPTION, description)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(NAME, name)
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
}
