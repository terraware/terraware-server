package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.NumericIdentifierType
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.attach
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.MonitoringPlotIdConverter
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneHistoryId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneHistoryId
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
import com.terraformation.backend.db.tracking.tables.records.PlantingSiteHistoriesRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingSubzoneHistoriesRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingZoneHistoriesRecord
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_NOTIFICATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_POPULATIONS
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.edit.MonitoringPlotEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.PlantingSubzoneEdit
import com.terraformation.backend.tracking.edit.PlantingZoneEdit
import com.terraformation.backend.tracking.event.PlantingSeasonEndedEvent
import com.terraformation.backend.tracking.event.PlantingSeasonRescheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonScheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteDeletionStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteMapEditedEvent
import com.terraformation.backend.tracking.model.AnyPlantingSiteModel
import com.terraformation.backend.tracking.model.AnyPlantingSubzoneModel
import com.terraformation.backend.tracking.model.AnyPlantingZoneModel
import com.terraformation.backend.tracking.model.CannotUpdatePastPlantingSeasonException
import com.terraformation.backend.tracking.model.ExistingPlantingSeasonModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingPlantingSubzoneModel
import com.terraformation.backend.tracking.model.ExistingPlantingZoneModel
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.MonitoringPlotHistoryModel
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.NewPlantingSiteModel
import com.terraformation.backend.tracking.model.NewPlantingSubzoneModel
import com.terraformation.backend.tracking.model.NewPlantingZoneModel
import com.terraformation.backend.tracking.model.PlantingSeasonsOverlapException
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteHistoryModel
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteReportedPlantTotals
import com.terraformation.backend.tracking.model.PlantingSubzoneHistoryModel
import com.terraformation.backend.tracking.model.PlantingZoneHistoryModel
import com.terraformation.backend.tracking.model.ReplacementResult
import com.terraformation.backend.tracking.model.UpdatedPlantingSeasonModel
import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.equalsIgnoreScale
import com.terraformation.backend.util.equalsOrBothNull
import com.terraformation.backend.util.toInstant
import com.terraformation.backend.util.toMultiPolygon
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.geotools.referencing.CRS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.springframework.context.ApplicationEventPublisher

@Named
class PlantingSiteStore(
    private val clock: InstantSource,
    private val countryDetector: CountryDetector,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val identifierGenerator: IdentifierGenerator,
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
  ): ExistingPlantingSiteModel {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return fetchSitesByCondition(PLANTING_SITES.ID.eq(plantingSiteId), depth).firstOrNull()
        ?: throw PlantingSiteNotFoundException(plantingSiteId)
  }

  fun fetchSitesByOrganizationId(
      organizationId: OrganizationId,
      depth: PlantingSiteDepth = PlantingSiteDepth.Site,
  ): List<ExistingPlantingSiteModel> {
    requirePermissions { readOrganization(organizationId) }

    return fetchSitesByCondition(PLANTING_SITES.ORGANIZATION_ID.eq(organizationId), depth)
  }

  fun fetchSitesByProjectId(
      projectId: ProjectId,
      depth: PlantingSiteDepth = PlantingSiteDepth.Site,
  ): List<ExistingPlantingSiteModel> {
    requirePermissions { readProject(projectId) }

    return fetchSitesByCondition(PLANTING_SITES.PROJECT_ID.eq(projectId), depth)
  }

  fun fetchSiteHistoryById(
      plantingSiteId: PlantingSiteId,
      plantingSiteHistoryId: PlantingSiteHistoryId,
      depth: PlantingSiteDepth,
  ): PlantingSiteHistoryModel {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return fetchSiteHistoriesByCondition(
            PLANTING_SITE_HISTORIES.PLANTING_SITE_ID.eq(plantingSiteId)
                .and(PLANTING_SITE_HISTORIES.ID.eq(plantingSiteHistoryId)),
            depth)
        .firstOrNull() ?: throw PlantingSiteHistoryNotFoundException(plantingSiteHistoryId)
  }

  private fun fetchSitesByCondition(
      condition: Condition,
      depth: PlantingSiteDepth
  ): List<ExistingPlantingSiteModel> {
    val zonesField =
        if (depth != PlantingSiteDepth.Site) {
          plantingZonesMultiset(depth)
        } else {
          null
        }
    val exteriorPlotsField =
        if (depth == PlantingSiteDepth.Plot) {
          monitoringPlotsMultiset(
              PLANTING_SITES.ID.eq(MONITORING_PLOTS.PLANTING_SITE_ID)
                  .and(MONITORING_PLOTS.PLANTING_SUBZONE_ID.isNull))
        } else {
          null
        }

    return dslContext
        .select(PLANTING_SITES.asterisk(), plantingSeasonsMultiset, zonesField, exteriorPlotsField)
        .from(PLANTING_SITES)
        .where(condition)
        .orderBy(PLANTING_SITES.ID)
        .fetch { PlantingSiteModel.of(it, plantingSeasonsMultiset, zonesField, exteriorPlotsField) }
  }

  private fun fetchSiteHistoriesByCondition(
      condition: Condition,
      depth: PlantingSiteDepth
  ): List<PlantingSiteHistoryModel> {
    val boundaryField = PLANTING_SITE_HISTORIES.BOUNDARY.forMultiset()
    val exclusionField = PLANTING_SITE_HISTORIES.EXCLUSION.forMultiset()
    val gridOriginField = PLANTING_SITE_HISTORIES.GRID_ORIGIN.forMultiset()

    val zonesField =
        if (depth != PlantingSiteDepth.Site) {
          plantingZoneHistoriesMultiset(depth)
        } else {
          null
        }

    return dslContext
        .select(
            PLANTING_SITE_HISTORIES.ID,
            PLANTING_SITE_HISTORIES.PLANTING_SITE_ID,
            boundaryField,
            exclusionField,
            gridOriginField,
            zonesField)
        .from(PLANTING_SITE_HISTORIES)
        .where(condition)
        .orderBy(PLANTING_SITE_HISTORIES.ID)
        .fetch { record ->
          PlantingSiteHistoryModel(
              record[boundaryField] as MultiPolygon,
              record[exclusionField] as? MultiPolygon,
              record[gridOriginField] as Point,
              record[PLANTING_SITE_HISTORIES.ID]!!,
              record[PLANTING_SITE_HISTORIES.PLANTING_SITE_ID]!!,
              zonesField?.let { record[it] } ?: emptyList(),
          )
        }
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

  /**
   * Returns the number of plants currently known to be planted in the subzones at a planting site.
   * Subzones that do not currently have any plants are not included in the return value. If subzone
   * A had a planting but all the plants were late reassigned to subzone B, subzone A will not be
   * included in the return value.
   */
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
    val subzoneTotalPlantsField = DSL.sum(PLANTING_SUBZONE_POPULATIONS.TOTAL_PLANTS)
    val subzonesField =
        DSL.multiset(
                DSL.select(
                        PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID, subzoneTotalPlantsField)
                    .from(PLANTING_SUBZONE_POPULATIONS)
                    .join(PLANTING_SUBZONES)
                    .on(PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
                    .where(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID))
                    .groupBy(PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID)
                    .having(subzoneTotalPlantsField.gt(BigDecimal.ZERO)))
            .convertFrom { result ->
              result.map { record ->
                PlantingSiteReportedPlantTotals.PlantingSubzone(
                    record[PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID]!!,
                    record[subzoneTotalPlantsField]!!.toInt())
              }
            }

    val zoneTotals =
        dslContext
            .select(
                PLANTING_ZONES.ID,
                PLANTING_ZONES.AREA_HA,
                PLANTING_ZONES.TARGET_PLANTING_DENSITY,
                zoneTotalSinceField,
                zoneTotalPlantsField,
                subzonesField)
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
                  plantingSubzones = record[subzonesField] ?: emptyList(),
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
      newModel: NewPlantingSiteModel,
      plantingSeasons: Collection<UpdatedPlantingSeasonModel> = emptyList(),
  ): ExistingPlantingSiteModel {
    requirePermissions {
      createPlantingSite(newModel.organizationId)
      newModel.projectId?.let { readProject(it) }
    }

    if (newModel.projectId != null &&
        newModel.organizationId != parentStore.getOrganizationId(newModel.projectId)) {
      throw ProjectInDifferentOrganizationException()
    }

    val now = clock.instant()

    val problems = newModel.validate()
    if (problems != null) {
      throw PlantingSiteMapInvalidException(problems)
    }

    val countryCode =
        newModel.boundary?.let { boundary -> countryDetector.getCountries(boundary).singleOrNull() }

    val plantingSitesRow =
        PlantingSitesRow(
            areaHa = newModel.areaHa,
            boundary = newModel.boundary,
            countryCode = countryCode,
            createdBy = currentUser().userId,
            createdTime = now,
            description = newModel.description,
            exclusion = newModel.exclusion,
            gridOrigin = newModel.gridOrigin,
            modifiedBy = currentUser().userId,
            modifiedTime = now,
            name = newModel.name,
            organizationId = newModel.organizationId,
            projectId = newModel.projectId,
            timeZone = newModel.timeZone,
        )

    return dslContext.transactionResult { _ ->
      plantingSitesDao.insert(plantingSitesRow)
      val plantingSiteId = plantingSitesRow.id!!

      var siteHistoryId: PlantingSiteHistoryId? = null

      if (newModel.boundary != null && newModel.gridOrigin != null) {
        siteHistoryId =
            insertPlantingSiteHistory(newModel, newModel.gridOrigin, now, plantingSiteId)

        newModel.plantingZones.forEach { zone ->
          val zoneId = createPlantingZone(zone, plantingSiteId, now)
          val zoneHistoryId = insertPlantingZoneHistory(zone, siteHistoryId, zoneId)

          zone.plantingSubzones.forEach { subzone ->
            val subzoneId = createPlantingSubzone(subzone, plantingSiteId, zoneId, now)
            insertPlantingSubzoneHistory(subzone, zoneHistoryId, subzoneId)
          }
        }
      }

      val effectiveTimeZone = newModel.timeZone ?: parentStore.getEffectiveTimeZone(plantingSiteId)

      if (!plantingSeasons.isEmpty()) {
        updatePlantingSeasons(plantingSiteId, plantingSeasons, effectiveTimeZone)
      }

      log.info("Created planting site $plantingSiteId for organization ${newModel.organizationId}")

      fetchSiteById(plantingSiteId, PlantingSiteDepth.Subzone).copy(historyId = siteHistoryId)
    }
  }

  fun updatePlantingSite(
      plantingSiteId: PlantingSiteId,
      plantingSeasons: Collection<UpdatedPlantingSeasonModel>,
      editFunc: (ExistingPlantingSiteModel) -> ExistingPlantingSiteModel,
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
    val editedArea = edited.boundary?.calculateAreaHectares()

    val now = clock.instant()

    dslContext.transaction { _ ->
      with(PLANTING_SITES) {
        dslContext
            .update(PLANTING_SITES)
            .set(DESCRIPTION, edited.description)
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, now)
            .set(NAME, edited.name)
            .set(PROJECT_ID, edited.projectId)
            .set(TIME_ZONE, edited.timeZone)
            .apply {
              // Boundaries can only be updated on simple planting sites.
              if (initial.plantingZones.isEmpty()) {
                set(AREA_HA, editedArea)
                set(BOUNDARY, edited.boundary)
              }
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

      if (edited.boundary != null && !edited.boundary.equalsOrBothNull(initial.boundary) ||
          !edited.exclusion.equalsOrBothNull(initial.exclusion)) {
        val historiesRecord =
            PlantingSiteHistoriesRecord(
                    areaHa = editedArea,
                    boundary = edited.boundary,
                    createdBy = currentUser().userId,
                    createdTime = now,
                    exclusion = edited.exclusion,
                    gridOrigin = initial.gridOrigin,
                    plantingSiteId = plantingSiteId,
                )
                .attach(dslContext)

        historiesRecord.insert()
      }

      if (initialTimeZone != editedTimeZone) {
        eventPublisher.publishEvent(
            PlantingSiteTimeZoneChangedEvent(edited, initialTimeZone, editedTimeZone))
      }
    }
  }

  fun applyPlantingSiteEdit(
      plantingSiteEdit: PlantingSiteEdit,
      subzonesToMarkIncomplete: Set<PlantingSubzoneId> = emptySet()
  ): ExistingPlantingSiteModel {
    val plantingSiteId = plantingSiteEdit.existingModel.id

    requirePermissions { updatePlantingSite(plantingSiteId) }

    val countryCode =
        plantingSiteEdit.desiredModel.boundary?.let {
          countryDetector.getCountries(it).singleOrNull()
        }

    return withLockedPlantingSite(plantingSiteId) {
      val existing = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)
      val now = clock.instant()
      val userId = currentUser().userId
      val replacementResults = mutableListOf<ReplacementResult>()

      if (plantingSiteEdit.plantingZoneEdits.isEmpty() &&
          existing.boundary.equalsOrBothNull(plantingSiteEdit.desiredModel.boundary) &&
          existing.exclusion.equalsOrBothNull(plantingSiteEdit.desiredModel.exclusion)) {
        log.debug("No-op edit for planting site $plantingSiteId")
        return@withLockedPlantingSite existing
      }

      with(PLANTING_SITES) {
        dslContext
            .update(PLANTING_SITES)
            .set(AREA_HA, plantingSiteEdit.desiredModel.areaHa)
            .set(BOUNDARY, plantingSiteEdit.desiredModel.boundary)
            .set(COUNTRY_CODE, countryCode)
            .set(EXCLUSION, plantingSiteEdit.desiredModel.exclusion)
            .set(MODIFIED_BY, userId)
            .set(MODIFIED_TIME, now)
            .where(ID.eq(plantingSiteId))
            .execute()
      }

      val plantingSiteHistoryId =
          with(PLANTING_SITE_HISTORIES) {
            dslContext
                .insertInto(PLANTING_SITE_HISTORIES)
                .set(AREA_HA, plantingSiteEdit.desiredModel.areaHa)
                .set(BOUNDARY, plantingSiteEdit.desiredModel.boundary)
                .set(CREATED_BY, userId)
                .set(CREATED_TIME, now)
                .set(EXCLUSION, plantingSiteEdit.desiredModel.exclusion)
                .set(GRID_ORIGIN, existing.gridOrigin)
                .set(PLANTING_SITE_ID, plantingSiteId)
                .returning(ID)
                .fetchOne(ID)!!
          }

      plantingSiteEdit.plantingZoneEdits.forEach {
        replacementResults.add(
            applyPlantingZoneEdit(
                it, plantingSiteId, plantingSiteHistoryId, subzonesToMarkIncomplete, now))
      }

      // If any zones weren't edited, we still want to include them and their subzones in the site's
      // map history.
      existing.plantingZones.forEach { zone ->
        if (plantingSiteEdit.plantingZoneEdits.none { it.existingModel?.id == zone.id }) {
          val plantingZoneHistoryId = insertPlantingZoneHistory(zone, plantingSiteHistoryId)
          zone.plantingSubzones.forEach { subzone ->
            insertPlantingSubzoneHistory(subzone, plantingZoneHistoryId)
          }
        }
      }

      // If any monitoring plots weren't edited, we still want to include them in the history too.
      dslContext
          .select(MONITORING_PLOTS.ID.asNonNullable(), MONITORING_PLOTS.PLANTING_SUBZONE_ID)
          .from(MONITORING_PLOTS)
          .where(MONITORING_PLOTS.PLANTING_SITE_ID.eq(plantingSiteId))
          .andNotExists(
              DSL.selectOne()
                  .from(MONITORING_PLOT_HISTORIES)
                  .where(
                      MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(plantingSiteHistoryId))
                  .and(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID)))
          .fetch()
          .forEach { (monitoringPlotId, plantingSubzoneId) ->
            insertMonitoringPlotHistory(monitoringPlotId, plantingSiteId, plantingSubzoneId)
          }

      sanityCheckAfterEdit(plantingSiteId)

      val edited = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)

      eventPublisher.publishEvent(
          PlantingSiteMapEditedEvent(
              edited, plantingSiteEdit, ReplacementResult.merge(replacementResults)))

      edited
    }
  }

  private fun applyPlantingZoneEdit(
      edit: PlantingZoneEdit,
      plantingSiteId: PlantingSiteId,
      plantingSiteHistoryId: PlantingSiteHistoryId,
      subzonesToMarkIncomplete: Set<PlantingSubzoneId>,
      now: Instant
  ): ReplacementResult {
    val replacementResults = mutableListOf<ReplacementResult>()

    when (edit) {
      is PlantingZoneEdit.Create -> {
        val plantingZoneId = createPlantingZone(edit.desiredModel.toNew(), plantingSiteId, now)
        val plantingZoneHistoryId =
            insertPlantingZoneHistory(edit.desiredModel, plantingSiteHistoryId, plantingZoneId)
        edit.plantingSubzoneEdits.forEach {
          applyPlantingSubzoneEdit(
              it, plantingSiteId, plantingZoneId, plantingZoneHistoryId, emptySet(), now)
        }
      }
      is PlantingZoneEdit.Delete -> {
        replacementResults.addAll(
            edit.plantingSubzoneEdits.map {
              applyPlantingSubzoneEdit(
                  edit = it,
                  plantingSiteId = plantingSiteId,
                  plantingZoneId = edit.existingModel.id,
                  plantingZoneHistoryId = null,
                  subzonesToMarkIncomplete = emptySet(),
                  now = now,
              )
            })

        val rowsDeleted =
            dslContext
                .deleteFrom(PLANTING_ZONES)
                .where(PLANTING_ZONES.ID.eq(edit.existingModel.id))
                .execute()
        if (rowsDeleted != 1) {
          throw PlantingZoneNotFoundException(edit.existingModel.id)
        }
      }
      is PlantingZoneEdit.Update -> {
        with(PLANTING_ZONES) {
          val boundaryChanged =
              !edit.existingModel.boundary.equalsOrBothNull(edit.desiredModel.boundary)
          val rowsUpdated =
              dslContext
                  .update(PLANTING_ZONES)
                  .set(AREA_HA, edit.desiredModel.areaHa)
                  .set(BOUNDARY, edit.desiredModel.boundary)
                  .apply {
                    if (boundaryChanged) {
                      set(BOUNDARY_MODIFIED_BY, currentUser().userId)
                          .set(BOUNDARY_MODIFIED_TIME, now)
                    }
                  }
                  .set(ERROR_MARGIN, edit.desiredModel.errorMargin)
                  .set(MODIFIED_BY, currentUser().userId)
                  .set(MODIFIED_TIME, now)
                  .set(NAME, edit.desiredModel.name)
                  .set(NUM_PERMANENT_PLOTS, edit.desiredModel.numPermanentPlots)
                  .set(STUDENTS_T, edit.desiredModel.studentsT)
                  .set(TARGET_PLANTING_DENSITY, edit.desiredModel.targetPlantingDensity)
                  .set(VARIANCE, edit.desiredModel.variance)
                  .where(ID.eq(edit.existingModel.id))
                  .execute()
          if (rowsUpdated != 1) {
            throw PlantingZoneNotFoundException(edit.existingModel.id)
          }
        }
        val plantingZoneHistoryId =
            insertPlantingZoneHistory(
                edit.desiredModel, plantingSiteHistoryId, edit.existingModel.id)

        replacementResults.addAll(
            edit.plantingSubzoneEdits.map { subzoneEdit ->
              applyPlantingSubzoneEdit(
                  edit = subzoneEdit,
                  plantingSiteId = plantingSiteId,
                  plantingZoneId = edit.existingModel.id,
                  plantingZoneHistoryId = plantingZoneHistoryId,
                  subzonesToMarkIncomplete = subzonesToMarkIncomplete,
                  now = now,
              )
            })

        // If any subzones weren't edited, we still want to include them in the site's map history.
        edit.existingModel.plantingSubzones.forEach { existingSubzone ->
          val subzoneStillInThisZone =
              edit.desiredModel.plantingSubzones.any { it.name == existingSubzone.name }
          val noEditForThisSubzone =
              edit.plantingSubzoneEdits.none { it.existingModel?.id == existingSubzone.id }

          if (subzoneStillInThisZone && noEditForThisSubzone) {
            insertPlantingSubzoneHistory(existingSubzone, plantingZoneHistoryId)
          }
        }

        // Need to create permanent clusters using the updated subzones since we need their IDs.
        val updatedSite = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)
        val updatedZone = updatedSite.plantingZones.single { it.id == edit.existingModel.id }

        edit.monitoringPlotEdits
            .filter { it.permanentCluster != null }
            .groupBy { it.region }
            .forEach { (region, plotEdits) ->
              val newPlotIds =
                  createPermanentClusters(
                      updatedSite, updatedZone, plotEdits.map { it.permanentCluster!! }, region)
              replacementResults.add(ReplacementResult(newPlotIds.toSet(), emptySet()))
            }
      }
    }

    return ReplacementResult.merge(replacementResults)
  }

  private fun applyPlantingSubzoneEdit(
      edit: PlantingSubzoneEdit,
      plantingSiteId: PlantingSiteId,
      plantingZoneId: PlantingZoneId,
      plantingZoneHistoryId: PlantingZoneHistoryId?,
      subzonesToMarkIncomplete: Set<PlantingSubzoneId>,
      now: Instant
  ): ReplacementResult {
    val replacementResults = mutableListOf<ReplacementResult>()

    when (edit) {
      is PlantingSubzoneEdit.Create -> {
        if (plantingZoneHistoryId == null) {
          throw IllegalArgumentException("Subzone creation requires planting zone history ID")
        }

        val plantingSubzoneId =
            createPlantingSubzone(edit.desiredModel.toNew(), plantingSiteId, plantingZoneId, now)
        insertPlantingSubzoneHistory(edit.desiredModel, plantingZoneHistoryId, plantingSubzoneId)

        edit.monitoringPlotEdits.forEach { plotEdit ->
          replacementResults.add(
              applyMonitoringPlotEdit(plotEdit, plantingSiteId, plantingSubzoneId, now))
        }
      }
      is PlantingSubzoneEdit.Delete -> {
        // Plots will be deleted by ON DELETE CASCADE. This may legitimately delete 0 rows if the
        // parent zone has already been deleted.
        dslContext
            .deleteFrom(PLANTING_SUBZONES)
            .where(PLANTING_SUBZONES.ID.eq(edit.existingModel.id))
            .execute()

        replacementResults.add(
            ReplacementResult(emptySet(), edit.existingModel.monitoringPlots.map { it.id }.toSet()))
      }
      is PlantingSubzoneEdit.Update -> {
        if (plantingZoneHistoryId == null) {
          throw IllegalArgumentException("Subzone update requires planting zone history ID")
        }

        val plantingSubzoneId = edit.existingModel.id
        val markIncomplete =
            !edit.addedRegion.isEmpty && plantingSubzoneId in subzonesToMarkIncomplete

        with(PLANTING_SUBZONES) {
          val rowsUpdated =
              dslContext
                  .update(PLANTING_SUBZONES)
                  .set(AREA_HA, edit.desiredModel.areaHa)
                  .set(BOUNDARY, edit.desiredModel.boundary)
                  .set(FULL_NAME, edit.desiredModel.fullName)
                  .set(MODIFIED_BY, currentUser().userId)
                  .set(MODIFIED_TIME, now)
                  .set(NAME, edit.desiredModel.name)
                  .let { if (markIncomplete) it.setNull(PLANTING_COMPLETED_TIME) else it }
                  .set(PLANTING_ZONE_ID, plantingZoneId)
                  .where(ID.eq(plantingSubzoneId))
                  .execute()
          if (rowsUpdated != 1) {
            throw PlantingSubzoneNotFoundException(plantingSubzoneId)
          }
        }

        insertPlantingSubzoneHistory(edit.desiredModel, plantingZoneHistoryId, plantingSubzoneId)

        edit.monitoringPlotEdits.forEach { plotEdit ->
          replacementResults.add(
              applyMonitoringPlotEdit(plotEdit, plantingSiteId, plantingSubzoneId, now))
        }
      }
    }

    return ReplacementResult.merge(replacementResults)
  }

  private fun applyMonitoringPlotEdit(
      edit: MonitoringPlotEdit,
      plantingSiteId: PlantingSiteId,
      plantingSubzoneId: PlantingSubzoneId,
      now: Instant,
  ): ReplacementResult {
    return when (edit) {
      is MonitoringPlotEdit.Adopt -> {
        with(MONITORING_PLOTS) {
          dslContext
              .update(MONITORING_PLOTS)
              .set(PERMANENT_CLUSTER, edit.permanentCluster)
              .set(PLANTING_SUBZONE_ID, plantingSubzoneId)
              .set(MODIFIED_BY, currentUser().userId)
              .set(MODIFIED_TIME, now)
              .where(ID.eq(edit.monitoringPlotId))
              .execute()
        }

        insertMonitoringPlotHistory(edit.monitoringPlotId, plantingSiteId, plantingSubzoneId)

        ReplacementResult(emptySet(), emptySet())
      }

      is MonitoringPlotEdit.Create ->
          throw IllegalStateException(
              "BUG! Monitoring plot creation should be handled at zone level")

      is MonitoringPlotEdit.Eject -> {
        with(MONITORING_PLOTS) {
          dslContext
              .update(MONITORING_PLOTS)
              .setNull(PERMANENT_CLUSTER)
              .setNull(PLANTING_SUBZONE_ID)
              .set(MODIFIED_BY, currentUser().userId)
              .set(MODIFIED_TIME, now)
              .where(ID.eq(edit.monitoringPlotId))
              .execute()
        }

        insertMonitoringPlotHistory(edit.monitoringPlotId, plantingSiteId, null)

        ReplacementResult(emptySet(), setOf(edit.monitoringPlotId))
      }
    }
  }

  /**
   * Do sanity checks after applying a planting site edit. Edits can be complex and we want to abort
   * them rather than leaving planting sites in inconsistent or invalid states.
   *
   * @throws IllegalStateException A sanity check failed.
   */
  private fun sanityCheckAfterEdit(plantingSiteId: PlantingSiteId) {
    // Make sure we haven't assigned the same permanent cluster number to two plots in a
    // planting zone. We don't enforce this with a database constraint because duplicate cluster
    // numbers are a valid intermediate state while a complex edit is being applied.
    val zonesWithDuplicateClusterNumbers =
        dslContext
            .select(PLANTING_ZONES.ID, PLANTING_ZONES.NAME, MONITORING_PLOTS.PERMANENT_CLUSTER)
            .from(PLANTING_ZONES)
            .join(PLANTING_SUBZONES)
            .on(PLANTING_ZONES.ID.eq(PLANTING_SUBZONES.PLANTING_ZONE_ID))
            .join(MONITORING_PLOTS)
            .on(PLANTING_SUBZONES.ID.eq(MONITORING_PLOTS.PLANTING_SUBZONE_ID))
            .where(PLANTING_ZONES.PLANTING_SITE_ID.eq(plantingSiteId))
            .and(MONITORING_PLOTS.PERMANENT_CLUSTER.isNotNull)
            .groupBy(PLANTING_ZONES.ID, PLANTING_ZONES.NAME, MONITORING_PLOTS.PERMANENT_CLUSTER)
            .having(DSL.count().gt(1))
            .fetch { record ->
              "planting zone ${record[PLANTING_ZONES.ID]} (${record[PLANTING_ZONES.NAME]}) " +
                  "cluster ${record[MONITORING_PLOTS.PERMANENT_CLUSTER]}"
            }
    if (zonesWithDuplicateClusterNumbers.isNotEmpty()) {
      val details = zonesWithDuplicateClusterNumbers.joinToString()
      throw IllegalStateException(
          "BUG! Edit resulted in duplicate permanent cluster numbers: $details")
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
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(NUM_PERMANENT_PLOTS, edited.numPermanentPlots)
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

  private fun createPlantingZone(
      zone: NewPlantingZoneModel,
      plantingSiteId: PlantingSiteId,
      now: Instant = clock.instant()
  ): PlantingZoneId {
    val userId = currentUser().userId

    val zonesRow =
        PlantingZonesRow(
            areaHa = zone.areaHa,
            boundary = zone.boundary,
            boundaryModifiedBy = userId,
            boundaryModifiedTime = now,
            createdBy = userId,
            createdTime = now,
            errorMargin = zone.errorMargin,
            modifiedBy = userId,
            modifiedTime = now,
            name = zone.name,
            numPermanentPlots = zone.numPermanentPlots,
            numTemporaryPlots = zone.numTemporaryPlots,
            plantingSiteId = plantingSiteId,
            studentsT = zone.studentsT,
            targetPlantingDensity = zone.targetPlantingDensity,
            variance = zone.variance,
        )

    plantingZonesDao.insert(zonesRow)

    return zonesRow.id!!
  }

  private fun createPlantingSubzone(
      subzone: NewPlantingSubzoneModel,
      plantingSiteId: PlantingSiteId,
      plantingZoneId: PlantingZoneId,
      now: Instant = clock.instant()
  ): PlantingSubzoneId {
    val userId = currentUser().userId

    val plantingSubzonesRow =
        PlantingSubzonesRow(
            areaHa = subzone.areaHa,
            boundary = subzone.boundary,
            createdBy = userId,
            createdTime = now,
            fullName = subzone.fullName,
            modifiedBy = userId,
            modifiedTime = now,
            name = subzone.name,
            plantingCompletedTime = subzone.plantingCompletedTime,
            plantingSiteId = plantingSiteId,
            plantingZoneId = plantingZoneId,
        )

    plantingSubzonesDao.insert(plantingSubzonesRow)

    return plantingSubzonesRow.id!!
  }

  private fun setMonitoringPlotCluster(
      monitoringPlotId: MonitoringPlotId,
      permanentCluster: Int,
  ) {
    with(MONITORING_PLOTS) {
      dslContext
          .update(MONITORING_PLOTS)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(PERMANENT_CLUSTER, permanentCluster)
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

    // Deleting the planting site will trigger cascading deletes of all the dependent data. Since
    // there are some foreign-key constraints in the dependent tables that use ON DELETE SET NULL,
    // it's possible for PostgreSQL to apply updates in the wrong order and end up with a dangling
    // foreign-key reference while it's in the middle of deleting everything. To prevent that
    // from causing the delete operation to fail, we need to tell it to defer checking some of the
    // foreign-key constraints until the deletion is completely done.
    dslContext.transaction { _ ->
      dslContext.execute("SET CONSTRAINTS ALL DEFERRED")
      plantingSitesDao.deleteById(plantingSiteId)
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
      updatePlantingSiteProject(plantingSiteIds.first())
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
   * The requested plot's "is available" flag is set to false, and its permanent cluster number is
   * cleared. If the plot was permanent, a new one with the removed cluster number will be created
   * next time [ensurePermanentClustersExist] is called.
   *
   * @return The plots that were modified. This is always either an empty list (if the plot was
   *   already unavailable) or a removed plots list with just the requested plot (if the plot was
   *   available before and we've just marked it as unavailable).
   */
  fun makePlotUnavailable(monitoringPlotId: MonitoringPlotId): ReplacementResult {
    val plotsRow =
        monitoringPlotsDao.fetchOneById(monitoringPlotId)
            ?: throw PlotNotFoundException(monitoringPlotId)

    requirePermissions { updatePlantingSite(plotsRow.plantingSiteId!!) }

    return if (plotsRow.isAvailable == true) {
      dslContext
          .update(MONITORING_PLOTS)
          .set(MONITORING_PLOTS.IS_AVAILABLE, false)
          .set(MONITORING_PLOTS.MODIFIED_BY, currentUser().userId)
          .set(MONITORING_PLOTS.MODIFIED_TIME, clock.instant())
          .setNull(MONITORING_PLOTS.PERMANENT_CLUSTER)
          .where(MONITORING_PLOTS.ID.eq(monitoringPlotId))
          .execute()

      ReplacementResult(
          addedMonitoringPlotIds = emptySet(), removedMonitoringPlotIds = setOf(monitoringPlotId))
    } else {
      ReplacementResult(emptySet(), emptySet())
    }
  }

  /**
   * Replaces a monitoring plot's permanent cluster with an unused one.
   *
   * If there are existing permanent clusters that haven't been used in any observations yet, uses
   * one of them; the existing cluster's number will be changed to the cluster number of the plot
   * being replaced.
   *
   * If there are no existing unused permanent clusters, tries to create a new cluster at a random
   * location in the zone.
   *
   * If the monitoring plot is not part of a permanent cluster, or there are no available places to
   * put a new permanent cluster, does nothing.
   *
   * @return A result whose "added plots" property has the IDs of the monitoring plots in the
   *   replacement cluster, and whose "removed plots" property has the IDs of all the monitoring
   *   plots in the requested plot's cluster.
   */
  fun replacePermanentCluster(monitoringPlotId: MonitoringPlotId): ReplacementResult {
    val plantingSiteId =
        parentStore.getPlantingSiteId(monitoringPlotId)
            ?: throw PlotNotFoundException(monitoringPlotId)

    requirePermissions {
      readMonitoringPlot(monitoringPlotId)
      updatePlantingSite(plantingSiteId)
    }

    return withLockedPlantingSite(plantingSiteId) {
      val plantingSite = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)
      val plantingZone =
          plantingSite.findZoneWithMonitoringPlot(monitoringPlotId)
              ?: throw PlotNotFoundException(monitoringPlotId)
      val plot =
          plantingZone
              .findSubzoneWithMonitoringPlot(monitoringPlotId)
              ?.findMonitoringPlot(monitoringPlotId)
              ?: throw PlotNotFoundException(monitoringPlotId)

      if (plot.permanentCluster == null) {
        log.warn("Cannot replace permanent cluster for non-permanent plot $monitoringPlotId")
        return@withLockedPlantingSite ReplacementResult(emptySet(), emptySet())
      }

      val unusedClusterNumber = fetchUnusedPermanentClusterNumber(plantingZone.id)
      val replacementPlotId =
          fetchPermanentPlotId(plantingZone.id, unusedClusterNumber)
              ?: run {
                // There's no unused cluster; try creating a new one.
                log.debug("Creating new permanent cluster to use as replacement")
                createPermanentClusters(plantingSite, plantingZone, listOf(unusedClusterNumber))
                    .firstOrNull()
              }

      if (replacementPlotId != null) {
        val now = clock.instant()
        val userId = currentUser().userId

        with(MONITORING_PLOTS) {
          dslContext
              .update(MONITORING_PLOTS)
              .set(MODIFIED_BY, userId)
              .set(MODIFIED_TIME, now)
              .setNull(PERMANENT_CLUSTER)
              .where(ID.eq(monitoringPlotId))
              .execute()

          dslContext
              .update(MONITORING_PLOTS)
              .set(MODIFIED_BY, userId)
              .set(MODIFIED_TIME, now)
              .set(PERMANENT_CLUSTER, plot.permanentCluster)
              .where(ID.eq(replacementPlotId))
              .execute()
        }

        ReplacementResult(setOfNotNull(replacementPlotId), setOf(monitoringPlotId))
      } else {
        ReplacementResult(emptySet(), emptySet())
      }
    }
  }

  /**
   * Ensures that the required number of permanent clusters of correct size exists in each of a
   * planting site's zones. There need to be clusters with numbers from 1 to the zone's permanent
   * cluster count.
   *
   * @return The IDs of any newly-created monitoring plots.
   */
  fun ensurePermanentClustersExist(plantingSiteId: PlantingSiteId): List<MonitoringPlotId> {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    return withLockedPlantingSite(plantingSiteId) {
      val plantingSite = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)

      plantingSite.plantingZones.flatMap { plantingZone ->
        val missingClusterNumbers: List<Int> =
            (1..plantingZone.numPermanentPlots).filterNot {
              plantingZone.permanentClusterExists(it)
            }

        createPermanentClusters(plantingSite, plantingZone, missingClusterNumbers)
      }
    }
  }

  /** Creates an ad-hoc monitoring plot for a planting site with a user-supplied corner. */
  fun createAdHocMonitoringPlot(
      plantingSiteId: PlantingSiteId,
      swCorner: Point,
  ): MonitoringPlotId {
    requirePermissions { scheduleAdHocObservation(plantingSiteId) }

    val userId = currentUser().userId
    val now = clock.instant()

    val crs = CRS.decode("EPSG:${swCorner.srid}", true)

    val plotBoundary = Turtle(swCorner, crs).makePolygon { square(MONITORING_PLOT_SIZE_INT) }

    val organizationId =
        parentStore.getOrganizationId(plantingSiteId)
            ?: throw PlantingSiteNotFoundException(plantingSiteId)
    val plotNumber =
        identifierGenerator.generateNumericIdentifier(
            organizationId, NumericIdentifierType.PlotNumber)

    val monitoringPlotsRow =
        MonitoringPlotsRow(
            boundary = plotBoundary,
            createdBy = userId,
            createdTime = now,
            isAdHoc = true,
            isAvailable = false,
            modifiedBy = userId,
            modifiedTime = now,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            plotNumber = plotNumber,
            sizeMeters = MONITORING_PLOT_SIZE_INT,
        )
    monitoringPlotsDao.insert(monitoringPlotsRow)

    insertMonitoringPlotHistory(monitoringPlotsRow)

    return monitoringPlotsRow.id!!
  }

  fun fetchMonitoringPlotsWithoutElevation(limit: Int = 50): List<MonitoringPlotModel> {
    return dslContext
        .select(
            MONITORING_PLOTS.BOUNDARY,
            MONITORING_PLOTS.ELEVATION_METERS,
            MONITORING_PLOTS.ID,
            MONITORING_PLOTS.IS_AD_HOC,
            MONITORING_PLOTS.IS_AVAILABLE,
            MONITORING_PLOTS.PERMANENT_CLUSTER,
            MONITORING_PLOTS.PLOT_NUMBER,
            MONITORING_PLOTS.SIZE_METERS,
        )
        .from(MONITORING_PLOTS)
        .where(MONITORING_PLOTS.ELEVATION_METERS.isNull)
        .apply {
          // For non-system users, check organization memberships
          if (currentUser().userType != UserType.System) {
            this.and(MONITORING_PLOTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys))
          }
        }
        .orderBy(MONITORING_PLOTS.ID.desc())
        .limit(limit)
        .fetch { record ->
          MonitoringPlotModel(
              boundary = record[MONITORING_PLOTS.BOUNDARY]!! as Polygon,
              elevationMeters = record[MONITORING_PLOTS.ELEVATION_METERS],
              id = record[MONITORING_PLOTS.ID]!!,
              isAdHoc = record[MONITORING_PLOTS.IS_AD_HOC]!!,
              isAvailable = record[MONITORING_PLOTS.IS_AVAILABLE]!!,
              permanentCluster = record[MONITORING_PLOTS.PERMANENT_CLUSTER],
              plotNumber = record[MONITORING_PLOTS.PLOT_NUMBER]!!,
              sizeMeters = record[MONITORING_PLOTS.SIZE_METERS]!!,
          )
        }
        .filter { currentUser().canReadMonitoringPlot(it.id) }
  }

  fun updateMonitoringPlotElevation(elevationByPlotId: Map<MonitoringPlotId, BigDecimal>): Int {

    val elevationTable =
        DSL.values(
            *elevationByPlotId
                .filter { currentUser().canUpdateMonitoringPlot(it.key) }
                .ifEmpty {
                  return 0
                }
                .map { (plotId, elevation) -> DSL.row(plotId, elevation) }
                .toTypedArray())

    val plotIdField =
        elevationTable.field(0, SQLDataType.BIGINT.asConvertedDataType(MonitoringPlotIdConverter()))
    val elevationField = elevationTable.field(1, BigDecimal::class.java)

    return dslContext
        .update(MONITORING_PLOTS)
        .set(MONITORING_PLOTS.ELEVATION_METERS, elevationField)
        .from(elevationTable)
        .where(MONITORING_PLOTS.ID.eq(plotIdField))
        .execute()
  }

  /**
   * Creates permanent clusters with a specific set of cluster numbers. The permanent clusters may
   * include a mix of newly-created monitoring plots and plots that exist already but were only used
   * as temporary plots in the past.
   */
  private fun createPermanentClusters(
      plantingSite: ExistingPlantingSiteModel,
      plantingZone: ExistingPlantingZoneModel,
      clusterNumbers: List<Int>,
      searchBoundary: MultiPolygon = plantingZone.boundary,
  ): List<MonitoringPlotId> {
    val userId = currentUser().userId
    val now = clock.instant()

    if (plantingSite.gridOrigin == null) {
      throw IllegalStateException("Planting site ${plantingSite.id} has no grid origin")
    }

    // List of [boundary, cluster number]
    val clusterBoundaries: List<Pair<Polygon, Int>> =
        plantingZone
            .findUnusedSquares(
                count = clusterNumbers.size,
                excludeAllPermanentPlots = true,
                exclusion = plantingSite.exclusion,
                gridOrigin = plantingSite.gridOrigin,
                searchBoundary = searchBoundary,
                sizeMeters = MONITORING_PLOT_SIZE,
            )
            .zip(clusterNumbers)

    return clusterBoundaries.map { (plotBoundary, clusterNumber) ->
      val existingPlot = plantingZone.findMonitoringPlot(plotBoundary)

      if (existingPlot != null) {
        if (existingPlot.permanentCluster != null) {
          throw IllegalStateException("Cannot place new permanent cluster over existing one")
        }

        setMonitoringPlotCluster(existingPlot.id, clusterNumber)

        existingPlot.id
      } else {
        val subzone =
            plantingZone.findPlantingSubzone(plotBoundary)
                ?: throw IllegalStateException(
                    "Planting zone ${plantingZone.id} not fully covered by subzones")
        val plotNumber =
            identifierGenerator.generateNumericIdentifier(
                plantingSite.organizationId, NumericIdentifierType.PlotNumber)

        val monitoringPlotsRow =
            MonitoringPlotsRow(
                boundary = plotBoundary,
                createdBy = userId,
                createdTime = now,
                isAdHoc = false,
                modifiedBy = userId,
                modifiedTime = now,
                organizationId = plantingSite.organizationId,
                permanentCluster = clusterNumber,
                plantingSiteId = plantingSite.id,
                plantingSubzoneId = subzone.id,
                plotNumber = plotNumber,
                sizeMeters = MONITORING_PLOT_SIZE_INT,
            )

        monitoringPlotsDao.insert(monitoringPlotsRow)

        insertMonitoringPlotHistory(monitoringPlotsRow)

        monitoringPlotsRow.id!!
      }
    }
  }

  fun createTemporaryPlot(
      plantingSiteId: PlantingSiteId,
      plantingZoneId: PlantingZoneId,
      plotBoundary: Polygon,
  ): MonitoringPlotId {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    val userId = currentUser().userId
    val now = clock.instant()

    return withLockedPlantingSite(plantingSiteId) {
      val plantingSite = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)
      val plantingZone =
          plantingSite.plantingZones.singleOrNull { it.id == plantingZoneId }
              ?: throw PlantingZoneNotFoundException(plantingZoneId)

      val existingPlotId = plantingZone.findMonitoringPlot(plotBoundary)?.id
      if (existingPlotId != null) {
        existingPlotId
      } else {
        val plotNumber =
            identifierGenerator.generateNumericIdentifier(
                plantingSite.organizationId, NumericIdentifierType.PlotNumber)
        val subzone =
            plantingZone.findPlantingSubzone(plotBoundary)
                ?: throw IllegalStateException(
                    "Planting zone $plantingZoneId not fully covered by subzones")

        val monitoringPlotsRow =
            MonitoringPlotsRow(
                boundary = plotBoundary,
                createdBy = userId,
                createdTime = now,
                isAdHoc = false,
                modifiedBy = userId,
                modifiedTime = now,
                organizationId = plantingSite.organizationId,
                plantingSiteId = plantingSiteId,
                plantingSubzoneId = subzone.id,
                plotNumber = plotNumber,
                sizeMeters = MONITORING_PLOT_SIZE_INT)
        monitoringPlotsDao.insert(monitoringPlotsRow)

        insertMonitoringPlotHistory(monitoringPlotsRow)

        monitoringPlotsRow.id!!
      }
    }
  }

  fun migrateSimplePlantingSites(
      addSuccessMessage: (String) -> Unit,
      addFailureMessage: (String) -> Unit
  ) {
    // These are the names we use in terraware-web when creating a new planting site without
    // specifying zones or subzones. They are not translated into the user's language.
    val zoneName = "Zone 01"
    val subzoneName = "Subzone A"

    dslContext.transaction { _ ->
      val simplePlantingSiteIds =
          dslContext
              .select(PLANTING_SITES.ID)
              .distinctOn(PLANTING_SITES.ID)
              .from(PLANTING_SITES)
              .where(PLANTING_SITES.BOUNDARY.isNotNull)
              .andNotExists(
                  DSL.selectOne()
                      .from(PLANTING_ZONES)
                      .where(PLANTING_ZONES.PLANTING_SITE_ID.eq(PLANTING_SITES.ID)))
              .orderBy(PLANTING_SITES.ID)
              .fetch(PLANTING_SITES.ID.asNonNullable())

      for (siteId in simplePlantingSiteIds) {
        try {
          val now = clock.instant()

          log.info("Migrating simple planting site $siteId to detailed")

          val existingSite = fetchSiteById(siteId, PlantingSiteDepth.Site)
          val boundary = existingSite.boundary!!.toMultiPolygon()

          // This calculates the grid origin and area.
          val newSite =
              NewPlantingSiteModel.create(
                  boundary,
                  existingSite.description,
                  name = existingSite.name,
                  organizationId = existingSite.organizationId,
              )

          if (newSite.areaHa == null || newSite.areaHa.equalsIgnoreScale(BigDecimal.ZERO)) {
            addFailureMessage(
                "Planting site $siteId (${existingSite.name}) area too small to convert")
            continue
          }

          val zone =
              NewPlantingZoneModel.create(
                  boundary = boundary,
                  name = zoneName,
                  plantingSubzones = emptyList(),
              )
          val subzone =
              NewPlantingSubzoneModel.create(
                  boundary = boundary,
                  fullName = "$zoneName-$subzoneName",
                  name = subzoneName,
              )

          with(PLANTING_SITES) {
            dslContext
                .update(PLANTING_SITES)
                .set(AREA_HA, newSite.areaHa)
                .set(GRID_ORIGIN, newSite.gridOrigin)
                .set(MODIFIED_BY, currentUser().userId)
                .set(MODIFIED_TIME, now)
                .where(ID.eq(siteId))
                .execute()
          }

          val siteHistoryId = insertPlantingSiteHistory(newSite, newSite.gridOrigin!!, now, siteId)
          val zoneId = createPlantingZone(zone, siteId)
          val zoneHistoryId = insertPlantingZoneHistory(zone, siteHistoryId, zoneId)
          val subzoneId = createPlantingSubzone(subzone, siteId, zoneId)
          insertPlantingSubzoneHistory(subzone, zoneHistoryId, subzoneId)

          addSuccessMessage("Migrated planting site $siteId (${existingSite.name})")
        } catch (e: Exception) {
          log.error("Unable to migrate planting site $siteId", e)
          addFailureMessage("Unable to migrate planting site $siteId: ${e.message}")
          break
        }
      }

      migrateSimplePlantingSitePopulations()
      migrateSimplePlantingSitePlantings()
    }
  }

  private fun migrateSimplePlantingSitePopulations() {
    // When plants are withdrawn to a detailed planting site, we update the site, zone, and
    // subzone populations. When they're withdrawn to a simple planting site, we only update the
    // site populations since there are no zones or subzones.
    //
    // After converting a simple planting site to a detailed one, there will be values in
    // planting_site_populations but no values for any of the site's zones or subzones in the
    // corresponding tables. So we want to copy the site values down to those two tables.

    dslContext.transaction { _ ->
      with(PLANTING_ZONE_POPULATIONS) {
        val rowsInserted =
            dslContext
                .insertInto(
                    PLANTING_ZONE_POPULATIONS,
                    PLANTING_ZONE_ID,
                    SPECIES_ID,
                    TOTAL_PLANTS,
                    PLANTS_SINCE_LAST_OBSERVATION)
                .select(
                    DSL.select(
                            PLANTING_ZONES.ID,
                            PLANTING_SITE_POPULATIONS.SPECIES_ID,
                            PLANTING_SITE_POPULATIONS.TOTAL_PLANTS,
                            PLANTING_SITE_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION)
                        .from(PLANTING_SITE_POPULATIONS)
                        .join(PLANTING_ZONES)
                        .on(
                            PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID.eq(
                                PLANTING_ZONES.PLANTING_SITE_ID))
                        .whereNotExists(
                            DSL.selectOne()
                                .from(PLANTING_ZONE_POPULATIONS)
                                .join(PLANTING_ZONES)
                                .on(PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID))
                                .where(
                                    PLANTING_ZONES.PLANTING_SITE_ID.eq(
                                        PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID)))
                        .and(
                            DSL.value(1)
                                .eq(
                                    DSL.selectCount()
                                        .from(PLANTING_ZONES)
                                        .where(
                                            PLANTING_ZONES.PLANTING_SITE_ID.eq(
                                                PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID)))))
                .execute()

        log.info("Inserted $rowsInserted planting zone populations")
      }

      with(PLANTING_SUBZONE_POPULATIONS) {
        val rowsInserted =
            dslContext
                .insertInto(
                    PLANTING_SUBZONE_POPULATIONS,
                    PLANTING_SUBZONE_ID,
                    SPECIES_ID,
                    TOTAL_PLANTS,
                    PLANTS_SINCE_LAST_OBSERVATION)
                .select(
                    DSL.select(
                            PLANTING_SUBZONES.ID,
                            PLANTING_SITE_POPULATIONS.SPECIES_ID,
                            PLANTING_SITE_POPULATIONS.TOTAL_PLANTS,
                            PLANTING_SITE_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION)
                        .from(PLANTING_SITE_POPULATIONS)
                        .join(PLANTING_SUBZONES)
                        .on(
                            PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID.eq(
                                PLANTING_SUBZONES.PLANTING_SITE_ID))
                        .whereNotExists(
                            DSL.selectOne()
                                .from(PLANTING_SUBZONE_POPULATIONS)
                                .join(PLANTING_SUBZONES)
                                .on(PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
                                .where(
                                    PLANTING_SUBZONES.PLANTING_SITE_ID.eq(
                                        PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID)))
                        .and(
                            DSL.value(1)
                                .eq(
                                    DSL.selectCount()
                                        .from(PLANTING_SUBZONES)
                                        .where(
                                            PLANTING_SUBZONES.PLANTING_SITE_ID.eq(
                                                PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID)))))
                .execute()

        log.info("Inserted $rowsInserted planting subzone populations")
      }
    }
  }

  private fun migrateSimplePlantingSitePlantings() {
    // Outplanting withdrawals to simple planting sites result in rows in the plantings table
    // with the planting site ID populated but the planting subzone ID set to null. Withdrawals to
    // detailed planting sites are required to have a subzone ID. So we want to fill in the subzone
    // ID for any plantings that have null subzone IDs but where the planting site has a subzone,
    // since those will be the sites whose zones and subzones we've just created.
    val rowsUpdated =
        dslContext
            .update(PLANTINGS)
            .set(
                PLANTINGS.PLANTING_SUBZONE_ID,
                DSL.select(PLANTING_SUBZONES.ID)
                    .from(PLANTING_SUBZONES)
                    .where(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(PLANTINGS.PLANTING_SITE_ID)))
            .where(PLANTINGS.PLANTING_SUBZONE_ID.isNull)
            .andExists(
                DSL.selectOne()
                    .from(PLANTING_SUBZONES)
                    .where(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(PLANTINGS.PLANTING_SITE_ID)))
            .and(
                DSL.value(1)
                    .eq(
                        DSL.selectCount()
                            .from(PLANTING_SUBZONES)
                            .where(
                                PLANTING_SUBZONES.PLANTING_SITE_ID.eq(PLANTINGS.PLANTING_SITE_ID))))
            .execute()

    log.info("Populated planting subzones for $rowsUpdated plantings")
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

  private fun monitoringPlotsMultiset(condition: Condition) =
      DSL.multiset(
              DSL.select(
                      MONITORING_PLOTS.ELEVATION_METERS,
                      MONITORING_PLOTS.ID,
                      MONITORING_PLOTS.IS_AD_HOC,
                      MONITORING_PLOTS.IS_AVAILABLE,
                      MONITORING_PLOTS.PERMANENT_CLUSTER,
                      MONITORING_PLOTS.PLOT_NUMBER,
                      MONITORING_PLOTS.SIZE_METERS,
                      monitoringPlotBoundaryField)
                  .from(MONITORING_PLOTS)
                  .where(condition)
                  .orderBy(MONITORING_PLOTS.PLOT_NUMBER))
          .convertFrom { result ->
            result.map { record ->
              MonitoringPlotModel(
                  boundary = record[monitoringPlotBoundaryField]!! as Polygon,
                  elevationMeters = record[MONITORING_PLOTS.ELEVATION_METERS],
                  id = record[MONITORING_PLOTS.ID]!!,
                  isAdHoc = record[MONITORING_PLOTS.IS_AD_HOC]!!,
                  isAvailable = record[MONITORING_PLOTS.IS_AVAILABLE]!!,
                  permanentCluster = record[MONITORING_PLOTS.PERMANENT_CLUSTER],
                  plotNumber = record[MONITORING_PLOTS.PLOT_NUMBER]!!,
                  sizeMeters = record[MONITORING_PLOTS.SIZE_METERS]!!,
              )
            }
          }

  private val monitoringPlotHistoriesMultiset =
      DSL.multiset(
              DSL.select(
                      MONITORING_PLOT_HISTORIES.CREATED_BY,
                      MONITORING_PLOT_HISTORIES.CREATED_TIME,
                      MONITORING_PLOT_HISTORIES.ID,
                      MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID,
                      MONITORING_PLOTS.SIZE_METERS,
                      monitoringPlotBoundaryField)
                  .from(MONITORING_PLOT_HISTORIES)
                  .join(MONITORING_PLOTS)
                  .on(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .where(
                      PLANTING_SUBZONE_HISTORIES.ID.eq(
                          MONITORING_PLOT_HISTORIES.PLANTING_SUBZONE_HISTORY_ID))
                  .orderBy(MONITORING_PLOTS.PLOT_NUMBER))
          .convertFrom { result ->
            result.map { record ->
              MonitoringPlotHistoryModel(
                  boundary = record[monitoringPlotBoundaryField]!! as Polygon,
                  createdBy = record[MONITORING_PLOT_HISTORIES.CREATED_BY]!!,
                  createdTime = record[MONITORING_PLOT_HISTORIES.CREATED_TIME]!!,
                  id = record[MONITORING_PLOT_HISTORIES.ID]!!,
                  monitoringPlotId = record[MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID]!!,
                  sizeMeters = record[MONITORING_PLOTS.SIZE_METERS]!!,
              )
            }
          }

  private fun plantingSubzonesMultiset(
      depth: PlantingSiteDepth
  ): Field<List<ExistingPlantingSubzoneModel>> {
    val plotsField =
        if (depth == PlantingSiteDepth.Plot)
            monitoringPlotsMultiset(
                DSL.and(
                    PLANTING_SUBZONES.ID.eq(MONITORING_PLOTS.PLANTING_SUBZONE_ID),
                    MONITORING_PLOTS.IS_AD_HOC.isFalse(),
                ))
        else null

    return DSL.multiset(
            DSL.select(
                    PLANTING_SUBZONES.AREA_HA,
                    PLANTING_SUBZONES.ID,
                    PLANTING_SUBZONES.FULL_NAME,
                    PLANTING_SUBZONES.NAME,
                    PLANTING_SUBZONES.OBSERVED_TIME,
                    PLANTING_SUBZONES.PLANTING_COMPLETED_TIME,
                    plantingSubzoneBoundaryField,
                    plotsField)
                .from(PLANTING_SUBZONES)
                .where(PLANTING_ZONES.ID.eq(PLANTING_SUBZONES.PLANTING_ZONE_ID))
                .orderBy(PLANTING_SUBZONES.FULL_NAME))
        .convertFrom { result ->
          result.map { record: Record ->
            ExistingPlantingSubzoneModel(
                areaHa = record[PLANTING_SUBZONES.AREA_HA]!!,
                boundary = record[plantingSubzoneBoundaryField]!! as MultiPolygon,
                id = record[PLANTING_SUBZONES.ID]!!,
                fullName = record[PLANTING_SUBZONES.FULL_NAME]!!,
                name = record[PLANTING_SUBZONES.NAME]!!,
                observedTime = record[PLANTING_SUBZONES.OBSERVED_TIME],
                plantingCompletedTime = record[PLANTING_SUBZONES.PLANTING_COMPLETED_TIME],
                monitoringPlots = plotsField?.let { record[it] } ?: emptyList(),
            )
          }
        }
  }

  private fun plantingSubzoneHistoriesMultiset(
      depth: PlantingSiteDepth
  ): Field<List<PlantingSubzoneHistoryModel>> {
    val plotsField = if (depth == PlantingSiteDepth.Plot) monitoringPlotHistoriesMultiset else null
    val boundaryField = PLANTING_SUBZONE_HISTORIES.BOUNDARY.forMultiset()

    return DSL.multiset(
            DSL.select(
                    PLANTING_SUBZONE_HISTORIES.FULL_NAME,
                    PLANTING_SUBZONE_HISTORIES.ID,
                    PLANTING_SUBZONE_HISTORIES.NAME,
                    PLANTING_SUBZONE_HISTORIES.PLANTING_SUBZONE_ID,
                    boundaryField,
                    plotsField)
                .from(PLANTING_SUBZONE_HISTORIES)
                .where(
                    PLANTING_ZONE_HISTORIES.ID.eq(
                        PLANTING_SUBZONE_HISTORIES.PLANTING_ZONE_HISTORY_ID))
                .orderBy(PLANTING_SUBZONE_HISTORIES.FULL_NAME))
        .convertFrom { result ->
          result.map { record: Record ->
            PlantingSubzoneHistoryModel(
                record[boundaryField] as MultiPolygon,
                record[PLANTING_SUBZONE_HISTORIES.FULL_NAME]!!,
                record[PLANTING_SUBZONE_HISTORIES.ID]!!,
                plotsField?.let { record[it] } ?: emptyList(),
                record[PLANTING_SUBZONE_HISTORIES.NAME]!!,
                record[PLANTING_SUBZONE_HISTORIES.PLANTING_SUBZONE_ID],
            )
          }
        }
  }

  private fun plantingZonesMultiset(
      depth: PlantingSiteDepth
  ): Field<List<ExistingPlantingZoneModel>> {
    val subzonesField =
        if (depth == PlantingSiteDepth.Subzone || depth == PlantingSiteDepth.Plot) {
          plantingSubzonesMultiset(depth)
        } else {
          null
        }

    return DSL.multiset(
            DSL.select(
                    PLANTING_ZONES.AREA_HA,
                    PLANTING_ZONES.BOUNDARY_MODIFIED_TIME,
                    PLANTING_ZONES.ERROR_MARGIN,
                    PLANTING_ZONES.ID,
                    PLANTING_ZONES.NAME,
                    PLANTING_ZONES.NUM_PERMANENT_PLOTS,
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
            ExistingPlantingZoneModel(
                record[PLANTING_ZONES.AREA_HA]!!,
                record[plantingZonesBoundaryField]!! as MultiPolygon,
                record[PLANTING_ZONES.BOUNDARY_MODIFIED_TIME]!!,
                record[PLANTING_ZONES.ERROR_MARGIN]!!,
                record[PLANTING_ZONES.ID]!!,
                record[PLANTING_ZONES.NAME]!!,
                record[PLANTING_ZONES.NUM_PERMANENT_PLOTS]!!,
                record[PLANTING_ZONES.NUM_TEMPORARY_PLOTS]!!,
                subzonesField?.let { record[it] } ?: emptyList(),
                record[PLANTING_ZONES.STUDENTS_T]!!,
                record[PLANTING_ZONES.TARGET_PLANTING_DENSITY]!!,
                record[PLANTING_ZONES.VARIANCE]!!,
            )
          }
        }
  }

  private fun plantingZoneHistoriesMultiset(
      depth: PlantingSiteDepth
  ): Field<List<PlantingZoneHistoryModel>> {
    val boundaryField = PLANTING_ZONE_HISTORIES.BOUNDARY.forMultiset()
    val subzonesField =
        if (depth == PlantingSiteDepth.Subzone || depth == PlantingSiteDepth.Plot) {
          plantingSubzoneHistoriesMultiset(depth)
        } else {
          null
        }

    return DSL.multiset(
            DSL.select(
                    PLANTING_ZONE_HISTORIES.ID,
                    PLANTING_ZONE_HISTORIES.NAME,
                    PLANTING_ZONE_HISTORIES.PLANTING_ZONE_ID,
                    boundaryField,
                    subzonesField)
                .from(PLANTING_ZONE_HISTORIES)
                .where(
                    PLANTING_SITE_HISTORIES.ID.eq(PLANTING_ZONE_HISTORIES.PLANTING_SITE_HISTORY_ID))
                .orderBy(PLANTING_ZONE_HISTORIES.NAME))
        .convertFrom { result ->
          result.map { record: Record ->
            PlantingZoneHistoryModel(
                record[boundaryField] as MultiPolygon,
                record[PLANTING_ZONE_HISTORIES.ID]!!,
                record[PLANTING_ZONE_HISTORIES.NAME]!!,
                subzonesField?.let { record[it]!! } ?: emptyList(),
                record[PLANTING_ZONE_HISTORIES.PLANTING_ZONE_ID],
            )
          }
        }
  }

  private fun fetchPermanentPlotId(
      plantingZoneId: PlantingZoneId,
      permanentCluster: Int
  ): MonitoringPlotId? {
    return dslContext
        .select(MONITORING_PLOTS.ID)
        .from(MONITORING_PLOTS)
        .join(PLANTING_SUBZONES)
        .on(MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
        .where(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(plantingZoneId))
        .and(MONITORING_PLOTS.PERMANENT_CLUSTER.eq(permanentCluster))
        .fetchOne(MONITORING_PLOTS.ID)
  }

  /**
   * Returns the number of a permanent cluster that hasn't been used in any observations yet. If all
   * existing permanent clusters have already been used, returns a number 1 greater than the current
   * maximum cluster number; the caller will have to create the cluster.
   */
  private fun fetchUnusedPermanentClusterNumber(plantingZoneId: PlantingZoneId): Int {
    val previouslyUsedField =
        DSL.exists(
                DSL.selectOne()
                    .from(OBSERVATION_PLOTS)
                    .where(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                    .and(OBSERVATION_PLOTS.IS_PERMANENT))
            .asNonNullable()

    val (maxCluster, maxClusterWasPreviouslyUsed) =
        dslContext
            .select(MONITORING_PLOTS.PERMANENT_CLUSTER.asNonNullable(), previouslyUsedField)
            .from(MONITORING_PLOTS)
            .join(PLANTING_SUBZONES)
            .on(MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
            .where(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(plantingZoneId))
            .and(MONITORING_PLOTS.PERMANENT_CLUSTER.isNotNull)
            .orderBy(MONITORING_PLOTS.PERMANENT_CLUSTER.desc(), previouslyUsedField.desc())
            .limit(1)
            .fetchOne() ?: throw IllegalStateException("Could not query zone's permanent clusters")

    return if (maxClusterWasPreviouslyUsed) {
      maxCluster + 1
    } else {
      maxCluster
    }
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
  private fun <T> withLockedPlantingSite(plantingSiteId: PlantingSiteId, func: () -> T): T {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    return dslContext.transactionResult { _ ->
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

  private fun insertPlantingSiteHistory(
      newModel: AnyPlantingSiteModel,
      gridOrigin: Point,
      now: Instant,
      plantingSiteId: PlantingSiteId =
          newModel.id ?: throw IllegalArgumentException("Planting site missing ID"),
  ): PlantingSiteHistoryId {
    val historiesRecord =
        PlantingSiteHistoriesRecord(
                areaHa = newModel.areaHa,
                boundary = newModel.boundary,
                createdBy = currentUser().userId,
                createdTime = now,
                exclusion = newModel.exclusion,
                gridOrigin = gridOrigin,
                plantingSiteId = plantingSiteId,
            )
            .attach(dslContext)

    historiesRecord.insert()

    return historiesRecord.id!!
  }

  private fun insertPlantingZoneHistory(
      model: AnyPlantingZoneModel,
      plantingSiteHistoryId: PlantingSiteHistoryId,
      plantingZoneId: PlantingZoneId =
          model.id ?: throw IllegalArgumentException("Planting zone missing ID"),
  ): PlantingZoneHistoryId {
    val historiesRecord =
        PlantingZoneHistoriesRecord(
                areaHa = model.areaHa,
                boundary = model.boundary,
                name = model.name,
                plantingSiteHistoryId = plantingSiteHistoryId,
                plantingZoneId = plantingZoneId,
            )
            .attach(dslContext)

    historiesRecord.insert()

    return historiesRecord.id!!
  }

  private fun insertPlantingSubzoneHistory(
      model: AnyPlantingSubzoneModel,
      plantingZoneHistoryId: PlantingZoneHistoryId,
      plantingSubzoneId: PlantingSubzoneId =
          model.id ?: throw IllegalArgumentException("Planting subzone missing ID"),
  ): PlantingSubzoneHistoryId {
    val historiesRecord =
        PlantingSubzoneHistoriesRecord(
                areaHa = model.areaHa,
                boundary = model.boundary,
                fullName = model.fullName,
                name = model.name,
                plantingSubzoneId = plantingSubzoneId,
                plantingZoneHistoryId = plantingZoneHistoryId,
            )
            .attach(dslContext)

    historiesRecord.insert()

    return historiesRecord.id!!
  }

  private fun insertMonitoringPlotHistory(
      monitoringPlotsRow: MonitoringPlotsRow
  ): MonitoringPlotHistoryId {
    return with(monitoringPlotsRow) {
      insertMonitoringPlotHistory(id!!, plantingSiteId!!, plantingSubzoneId)
    }
  }

  private fun insertMonitoringPlotHistory(
      monitoringPlotId: MonitoringPlotId,
      plantingSiteId: PlantingSiteId,
      plantingSubzoneId: PlantingSubzoneId?,
  ): MonitoringPlotHistoryId {
    return with(MONITORING_PLOT_HISTORIES) {
      dslContext
          .insertInto(MONITORING_PLOT_HISTORIES)
          .set(CREATED_BY, currentUser().userId)
          .set(CREATED_TIME, clock.instant())
          .set(MONITORING_PLOT_ID, monitoringPlotId)
          .set(
              PLANTING_SITE_HISTORY_ID,
              DSL.select(DSL.max(PLANTING_SITE_HISTORIES.ID))
                  .from(PLANTING_SITE_HISTORIES)
                  .where(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID.eq(plantingSiteId)))
          .set(PLANTING_SITE_ID, plantingSiteId)
          .set(
              PLANTING_SUBZONE_HISTORY_ID,
              DSL.select(DSL.max(PLANTING_SUBZONE_HISTORIES.ID))
                  .from(PLANTING_SUBZONE_HISTORIES)
                  .where(PLANTING_SUBZONE_HISTORIES.PLANTING_SUBZONE_ID.eq(plantingSubzoneId)))
          .set(PLANTING_SUBZONE_ID, plantingSubzoneId)
          .returning(ID)
          .fetchOne(ID)!!
    }
  }
}
