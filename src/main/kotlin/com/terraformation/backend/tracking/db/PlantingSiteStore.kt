package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.attach
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.MonitoringPlotId
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
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_NOTIFICATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_POPULATIONS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.PlantingSubzoneEdit
import com.terraformation.backend.tracking.edit.PlantingZoneEdit
import com.terraformation.backend.tracking.event.PlantingSeasonEndedEvent
import com.terraformation.backend.tracking.event.PlantingSeasonRescheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonScheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteDeletionStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteMapEditedEvent
import com.terraformation.backend.tracking.model.CannotUpdatePastPlantingSeasonException
import com.terraformation.backend.tracking.model.ExistingPlantingSeasonModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingPlantingSubzoneModel
import com.terraformation.backend.tracking.model.ExistingPlantingZoneModel
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.NewPlantingSiteModel
import com.terraformation.backend.tracking.model.NewPlantingSubzoneModel
import com.terraformation.backend.tracking.model.NewPlantingZoneModel
import com.terraformation.backend.tracking.model.PlantingSeasonsOverlapException
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteReportedPlantTotals
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.tracking.model.ReplacementResult
import com.terraformation.backend.tracking.model.UpdatedPlantingSeasonModel
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.createRectangle
import com.terraformation.backend.util.equalsOrBothNull
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
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
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

  private fun fetchSitesByCondition(
      condition: Condition,
      depth: PlantingSiteDepth,
  ): List<ExistingPlantingSiteModel> {
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
        .fetch { PlantingSiteModel.of(it, plantingSeasonsMultiset, zonesField) }
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

    // The point that will be used as the origin for the grid of monitoring plots. We use the
    // southwest corner of the envelope (bounding box) of the site boundary.
    val gridOrigin =
        if (newModel.boundary != null) {
          newModel.boundary.factory.createPoint(newModel.boundary.envelope.coordinates[0])
        } else {
          null
        }

    val problems = newModel.copy(gridOrigin = gridOrigin).validate()
    if (problems != null) {
      throw PlantingSiteMapInvalidException(problems)
    }

    val plantingSitesRow =
        PlantingSitesRow(
            areaHa = newModel.areaHa,
            boundary = newModel.boundary,
            createdBy = currentUser().userId,
            createdTime = now,
            description = newModel.description,
            exclusion = newModel.exclusion,
            gridOrigin = gridOrigin,
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

      if (newModel.boundary != null && gridOrigin != null) {
        siteHistoryId = insertPlantingSiteHistory(newModel, gridOrigin, now, plantingSiteId)

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
                set(AREA_HA, edited.boundary?.calculateAreaHectares())
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

    if (plantingSiteEdit.problems.isNotEmpty()) {
      throw PlantingSiteEditInvalidException(plantingSiteEdit.problems)
    }

    requirePermissions { updatePlantingSite(plantingSiteId) }

    return withLockedPlantingSite(plantingSiteId) {
      val existing = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)
      val now = clock.instant()
      val userId = currentUser().userId

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
        applyPlantingZoneEdit(
            it, plantingSiteId, plantingSiteHistoryId, subzonesToMarkIncomplete, now)
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

      eventPublisher.publishEvent(PlantingSiteMapEditedEvent(plantingSiteEdit))

      fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)
    }
  }

  private fun applyPlantingZoneEdit(
      edit: PlantingZoneEdit,
      plantingSiteId: PlantingSiteId,
      plantingSiteHistoryId: PlantingSiteHistoryId,
      subzonesToMarkIncomplete: Set<PlantingSubzoneId>,
      now: Instant
  ) {
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
        // Subzones and plots will be deleted by ON DELETE CASCADE
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
          val rowsUpdated =
              dslContext
                  .update(PLANTING_ZONES)
                  .set(AREA_HA, edit.desiredModel.areaHa)
                  .set(BOUNDARY, edit.desiredModel.boundary)
                  .set(
                      EXTRA_PERMANENT_CLUSTERS,
                      edit.existingModel.extraPermanentClusters + edit.numPermanentClustersToAdd)
                  .set(MODIFIED_BY, currentUser().userId)
                  .set(MODIFIED_TIME, now)
                  .set(NAME, edit.desiredModel.name)
                  .set(
                      NUM_PERMANENT_CLUSTERS,
                      edit.existingModel.numPermanentClusters + edit.numPermanentClustersToAdd)
                  .set(TARGET_PLANTING_DENSITY, edit.desiredModel.targetPlantingDensity)
                  .where(ID.eq(edit.existingModel.id))
                  .execute()
          if (rowsUpdated != 1) {
            throw PlantingZoneNotFoundException(edit.existingModel.id)
          }
        }
        val plantingZoneHistoryId =
            insertPlantingZoneHistory(
                edit.desiredModel, plantingSiteHistoryId, edit.existingModel.id)

        edit.plantingSubzoneEdits.forEach { subzoneEdit ->
          applyPlantingSubzoneEdit(
              edit = subzoneEdit,
              plantingSiteId = plantingSiteId,
              plantingZoneId = edit.existingModel.id,
              plantingZoneHistoryId = plantingZoneHistoryId,
              subzonesToMarkIncomplete = subzonesToMarkIncomplete,
              now = now,
          )
        }

        // If any subzones weren't edited, we still want to include them in the site's map history.
        edit.existingModel.plantingSubzones.forEach { subzone ->
          if (edit.plantingSubzoneEdits.none { it.existingModel?.id == subzone.id }) {
            insertPlantingSubzoneHistory(subzone, plantingZoneHistoryId)
          }
        }

        if (edit.numPermanentClustersToAdd > 0) {
          // Create the new permanent clusters at random places in the cluster list so that they
          // aren't less likely to be selected for observations if the number of permanent clusters
          // in the zone changes over time.
          val newClusterNumbers =
              (1..edit.existingModel.numPermanentClusters + edit.numPermanentClustersToAdd)
                  .shuffled()
                  .take(edit.numPermanentClustersToAdd)
                  .sorted()
          newClusterNumbers.forEach { makeRoomForClusterNumber(edit.existingModel.id, it) }

          // Need to create permanent clusters using the updated subzones since we need their IDs.
          val updatedSite = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)
          val updatedZone = updatedSite.plantingZones.single { it.id == edit.existingModel.id }
          createPermanentClusters(updatedSite, updatedZone, newClusterNumbers, edit.addedRegion)
        }
      }
    }
  }

  private fun applyPlantingSubzoneEdit(
      edit: PlantingSubzoneEdit,
      plantingSiteId: PlantingSiteId,
      plantingZoneId: PlantingZoneId,
      plantingZoneHistoryId: PlantingZoneHistoryId,
      subzonesToMarkIncomplete: Set<PlantingSubzoneId>,
      now: Instant
  ) {
    when (edit) {
      is PlantingSubzoneEdit.Create -> {
        val plantingSubzoneId =
            createPlantingSubzone(edit.desiredModel.toNew(), plantingSiteId, plantingZoneId, now)
        insertPlantingSubzoneHistory(edit.desiredModel, plantingZoneHistoryId, plantingSubzoneId)
      }
      is PlantingSubzoneEdit.Delete -> {
        // Plots will be deleted by ON DELETE CASCADE. This may legitimately delete 0 rows if the
        // parent zone has already been deleted.
        dslContext
            .deleteFrom(PLANTING_SUBZONES)
            .where(PLANTING_SUBZONES.ID.eq(edit.existingModel.id))
            .execute()
      }
      is PlantingSubzoneEdit.Update -> {
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
                  .where(ID.eq(plantingSubzoneId))
                  .execute()
          if (rowsUpdated != 1) {
            throw PlantingSubzoneNotFoundException(plantingSubzoneId)
          }
        }

        edit.existingModel.monitoringPlots.forEach { plot ->
          if (plot.boundary.intersects(edit.removedRegion)) {
            makePlotUnavailable(plot.id)
          }
        }

        insertPlantingSubzoneHistory(edit.desiredModel, plantingZoneHistoryId, plantingSubzoneId)
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
            createdBy = userId,
            createdTime = now,
            errorMargin = zone.errorMargin,
            extraPermanentClusters = zone.extraPermanentClusters,
            modifiedBy = userId,
            modifiedTime = now,
            name = zone.name,
            numPermanentClusters = zone.numPermanentClusters,
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
   * are still available for selection as temporary plots). A new cluster with the removed cluster
   * number will be created next time [ensurePermanentClustersExist] is called.
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
        // standalone ones.
        val clusterPlotIds = fetchPlotIdsForPermanentCluster(plantingZoneId, permanentCluster)

        dslContext
            .update(MONITORING_PLOTS)
            .set(MONITORING_PLOTS.MODIFIED_BY, currentUser().userId)
            .set(MONITORING_PLOTS.MODIFIED_TIME, clock.instant())
            .setNull(MONITORING_PLOTS.PERMANENT_CLUSTER)
            .setNull(MONITORING_PLOTS.PERMANENT_CLUSTER_SUBPLOT)
            .where(MONITORING_PLOTS.ID.`in`(clusterPlotIds))
            .execute()

        ReplacementResult(
            addedMonitoringPlotIds = emptySet(), removedMonitoringPlotIds = clusterPlotIds.toSet())
      }
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

      val clusterPlotIds = fetchPlotIdsForPermanentCluster(plantingZone.id, plot.permanentCluster)

      val unusedClusterNumber = fetchUnusedPermanentClusterNumber(plantingZone.id)
      val replacementPlotIds =
          fetchPlotIdsForPermanentCluster(plantingZone.id, unusedClusterNumber).ifEmpty {
            // There's no unused cluster; try creating a new one.
            log.debug("Creating new permanent cluster to use as replacement")
            createPermanentClusters(plantingSite, plantingZone, listOf(unusedClusterNumber))
          }

      if (replacementPlotIds.isNotEmpty()) {
        val now = clock.instant()
        val userId = currentUser().userId

        with(MONITORING_PLOTS) {
          dslContext
              .update(MONITORING_PLOTS)
              .set(MODIFIED_BY, userId)
              .set(MODIFIED_TIME, now)
              .setNull(PERMANENT_CLUSTER)
              .setNull(PERMANENT_CLUSTER_SUBPLOT)
              .where(ID.`in`(clusterPlotIds))
              .execute()

          dslContext
              .update(MONITORING_PLOTS)
              .set(MODIFIED_BY, userId)
              .set(MODIFIED_TIME, now)
              .set(PERMANENT_CLUSTER, plot.permanentCluster)
              .where(ID.`in`(replacementPlotIds))
              .execute()
        }

        ReplacementResult(replacementPlotIds.toSet(), clusterPlotIds.toSet())
      } else {
        ReplacementResult(emptySet(), emptySet())
      }
    }
  }

  /**
   * Ensures that the required number of permanent clusters exists in each of a planting site's
   * zones. There need to be clusters with numbers from 1 to the zone's permanent cluster count.
   */
  fun ensurePermanentClustersExist(plantingSiteId: PlantingSiteId) {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    withLockedPlantingSite(plantingSiteId) {
      val plantingSite = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)

      plantingSite.plantingZones.forEach { plantingZone ->
        val missingClusterNumbers: List<Int> =
            (1..plantingZone.numPermanentClusters).filterNot {
              plantingZone.permanentClusterExists(it)
            }

        createPermanentClusters(plantingSite, plantingZone, missingClusterNumbers)
      }
    }
  }

  /**
   * Makes room for a new permanent cluster with a particular number. This is effectively an
   * insertion into the ordered list of clusters for the zone: the numbers of any existing clusters
   * with the desired number or higher are incremented by 1.
   */
  private fun makeRoomForClusterNumber(plantingZoneId: PlantingZoneId, clusterNumber: Int) {
    with(MONITORING_PLOTS) {
      dslContext
          .update(MONITORING_PLOTS)
          .set(PERMANENT_CLUSTER, PERMANENT_CLUSTER.plus(1))
          .where(
              PLANTING_SUBZONE_ID.`in`(
                  DSL.select(PLANTING_SUBZONES.ID)
                      .from(PLANTING_SUBZONES)
                      .where(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(plantingZoneId))))
          .and(PERMANENT_CLUSTER.ge(clusterNumber))
          .execute()
    }
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

    var nextPlotNumber = plantingZone.getMaxPlotName() + 1

    if (plantingSite.gridOrigin == null) {
      throw IllegalStateException("Planting site ${plantingSite.id} has no grid origin")
    }

    val geometryFactory = plantingSite.gridOrigin.factory

    // List of [boundary, cluster number]
    val clusterBoundaries: List<Pair<Polygon, Int>> =
        plantingZone
            .findUnusedSquares(
                count = clusterNumbers.size,
                excludeAllPermanentPlots = true,
                exclusion = plantingSite.exclusion,
                gridOrigin = plantingSite.gridOrigin,
                searchBoundary = searchBoundary,
                sizeMeters = MONITORING_PLOT_SIZE * 2,
            )
            .zip(clusterNumbers)

    return clusterBoundaries.flatMap { (clusterBoundary, clusterNumber) ->
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

      clusterPlots.mapIndexed { plotIndex, plotBoundary ->
        val existingPlot = plantingZone.findMonitoringPlot(plotBoundary.centroid)

        if (existingPlot != null) {
          if (existingPlot.permanentCluster != null) {
            throw IllegalStateException("Cannot place new permanent cluster over existing one")
          }

          setMonitoringPlotCluster(existingPlot.id, clusterNumber, plotIndex + 1)

          existingPlot.id
        } else {
          val subzone =
              plantingZone.findPlantingSubzone(plotBoundary)
                  ?: throw IllegalStateException(
                      "Planting zone ${plantingZone.id} not fully covered by subzones",
                  )
          val plotNumber = nextPlotNumber++

          val monitoringPlotsRow =
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
                  plantingSubzoneId = subzone.id,
              )

          monitoringPlotsDao.insert(monitoringPlotsRow)

          monitoringPlotsRow.id!!
        }
      }
    }
  }

  fun createTemporaryPlot(
      plantingSiteId: PlantingSiteId,
      plantingZoneId: PlantingZoneId,
      plotBoundary: Polygon
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
        val plotNumber = plantingZone.getMaxPlotName() + 1
        val subzone =
            plantingZone.findPlantingSubzone(plotBoundary)
                ?: throw IllegalStateException(
                    "Planting zone $plantingZoneId not fully covered by subzones")

        val monitoringPlotsRow =
            MonitoringPlotsRow(
                boundary = plotBoundary,
                createdBy = userId,
                createdTime = now,
                fullName = "${subzone.fullName}-$plotNumber",
                modifiedBy = userId,
                modifiedTime = now,
                name = "$plotNumber",
                plantingSubzoneId = subzone.id)
        monitoringPlotsDao.insert(monitoringPlotsRow)

        monitoringPlotsRow.id!!
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
  ): Field<List<ExistingPlantingSubzoneModel>> {
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
      newModel: PlantingSiteModel<*, *, *>,
      gridOrigin: Point,
      now: Instant,
      plantingSiteId: PlantingSiteId =
          newModel.id ?: throw IllegalArgumentException("Planting site missing ID"),
  ): PlantingSiteHistoryId {
    val historiesRecord =
        PlantingSiteHistoriesRecord(
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
      model: PlantingZoneModel<*, *>,
      plantingSiteHistoryId: PlantingSiteHistoryId,
      plantingZoneId: PlantingZoneId =
          model.id ?: throw IllegalArgumentException("Planting zone missing ID"),
  ): PlantingZoneHistoryId {
    val historiesRecord =
        PlantingZoneHistoriesRecord(
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
      model: PlantingSubzoneModel<*>,
      plantingZoneHistoryId: PlantingZoneHistoryId,
      plantingSubzoneId: PlantingSubzoneId =
          model.id ?: throw IllegalArgumentException("Planting subzone missing ID"),
  ): PlantingSubzoneHistoryId {
    val historiesRecord =
        PlantingSubzoneHistoriesRecord(
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
}
