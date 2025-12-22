package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.NumericIdentifierType
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.attach
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesIdConverter
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.MonitoringPlotIdConverter
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.daos.MonitoringPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSeasonsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.StrataDao
import com.terraformation.backend.db.tracking.tables.daos.SubstrataDao
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSeasonsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.StrataRow
import com.terraformation.backend.db.tracking.tables.pojos.SubstrataRow
import com.terraformation.backend.db.tracking.tables.records.ObservationsRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingSiteHistoriesRecord
import com.terraformation.backend.db.tracking.tables.records.StratumHistoriesRecord
import com.terraformation.backend.db.tracking.tables.records.SubstratumHistoriesRecord
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_NOTIFICATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_POPULATIONS
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.ratelimit.RateLimitedEventPublisher
import com.terraformation.backend.tracking.edit.MonitoringPlotEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.StratumEdit
import com.terraformation.backend.tracking.edit.SubstratumEdit
import com.terraformation.backend.tracking.event.PlantingSeasonEndedEvent
import com.terraformation.backend.tracking.event.PlantingSeasonRescheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonScheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteDeletionStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteMapEditedEvent
import com.terraformation.backend.tracking.event.RateLimitedT0DataAssignedEvent
import com.terraformation.backend.tracking.model.AnyPlantingSiteModel
import com.terraformation.backend.tracking.model.AnyStratumModel
import com.terraformation.backend.tracking.model.AnySubstratumModel
import com.terraformation.backend.tracking.model.CannotUpdatePastPlantingSeasonException
import com.terraformation.backend.tracking.model.ExistingPlantingSeasonModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingStratumModel
import com.terraformation.backend.tracking.model.ExistingSubstratumModel
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.MonitoringPlotHistoryModel
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.NewPlantingSiteModel
import com.terraformation.backend.tracking.model.NewStratumModel
import com.terraformation.backend.tracking.model.NewSubstratumModel
import com.terraformation.backend.tracking.model.PlantingSeasonsOverlapException
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteHistoryModel
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteReportedPlantTotals
import com.terraformation.backend.tracking.model.ReplacementResult
import com.terraformation.backend.tracking.model.StratumHistoryModel
import com.terraformation.backend.tracking.model.SubstratumHistoryModel
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
import org.jooq.TableField
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
    private val rateLimitedEventPublisher: RateLimitedEventPublisher,
    private val strataDao: StrataDao,
    private val substrataDao: SubstrataDao,
) {
  private val log = perClassLogger()

  private val monitoringPlotBoundaryField = MONITORING_PLOTS.BOUNDARY.forMultiset()
  private val strataBoundaryField = STRATA.BOUNDARY.forMultiset()
  private val substratumBoundaryField = SUBSTRATA.BOUNDARY.forMultiset()

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

  fun fetchSiteHistories(
      plantingSiteId: PlantingSiteId,
      depth: PlantingSiteDepth,
  ): List<PlantingSiteHistoryModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return fetchSiteHistoriesByCondition(
        PLANTING_SITE_HISTORIES.PLANTING_SITE_ID.eq(plantingSiteId),
        depth,
    )
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
            depth,
        )
        .firstOrNull() ?: throw PlantingSiteHistoryNotFoundException(plantingSiteHistoryId)
  }

  private fun fetchSitesByCondition(
      condition: Condition,
      depth: PlantingSiteDepth,
  ): List<ExistingPlantingSiteModel> {
    val strataField =
        if (depth != PlantingSiteDepth.Site) {
          strataMultiset(depth)
        } else {
          null
        }

    val adHocPlotsField =
        if (depth == PlantingSiteDepth.Plot) {
          monitoringPlotsMultiset(
              PLANTING_SITES.ID.eq(MONITORING_PLOTS.PLANTING_SITE_ID)
                  .and(MONITORING_PLOTS.IS_AD_HOC.isTrue)
          )
        } else {
          null
        }

    val exteriorPlotsField =
        if (depth == PlantingSiteDepth.Plot) {
          monitoringPlotsMultiset(
              PLANTING_SITES.ID.eq(MONITORING_PLOTS.PLANTING_SITE_ID)
                  .and(MONITORING_PLOTS.SUBSTRATUM_ID.isNull)
                  .and(MONITORING_PLOTS.IS_AD_HOC.isFalse)
          )
        } else {
          null
        }

    val observationPlotCondition =
        OBSERVATION_PLOTS.MONITORING_PLOT_ID.`in`(
            DSL.select(MONITORING_PLOTS.ID)
                .from(MONITORING_PLOTS)
                .where(MONITORING_PLOTS.PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
        )
    val latestObservationIdField = latestObservationField(OBSERVATIONS.ID, observationPlotCondition)
    val latestObservationTimeField =
        latestObservationField(OBSERVATIONS.COMPLETED_TIME, observationPlotCondition)

    return dslContext
        .select(
            PLANTING_SITES.asterisk(),
            plantingSeasonsMultiset,
            strataField,
            adHocPlotsField,
            exteriorPlotsField,
            latestObservationIdField,
            latestObservationTimeField,
        )
        .from(PLANTING_SITES)
        .where(condition)
        .orderBy(PLANTING_SITES.ID)
        .fetch {
          PlantingSiteModel.of(
              it,
              plantingSeasonsMultiset,
              strataField,
              adHocPlotsField,
              exteriorPlotsField,
              latestObservationIdField,
              latestObservationTimeField,
          )
        }
  }

  private fun fetchSiteHistoriesByCondition(
      condition: Condition,
      depth: PlantingSiteDepth,
  ): List<PlantingSiteHistoryModel> {
    val boundaryField = PLANTING_SITE_HISTORIES.BOUNDARY.forMultiset()
    val exclusionField = PLANTING_SITE_HISTORIES.EXCLUSION.forMultiset()
    val gridOriginField = PLANTING_SITE_HISTORIES.GRID_ORIGIN.forMultiset()

    val strataField =
        if (depth != PlantingSiteDepth.Site) {
          stratumHistoriesMultiset(depth)
        } else {
          null
        }

    return dslContext
        .select(
            PLANTING_SITE_HISTORIES.CREATED_TIME,
            PLANTING_SITE_HISTORIES.ID,
            PLANTING_SITE_HISTORIES.PLANTING_SITE_ID,
            PLANTING_SITE_HISTORIES.AREA_HA,
            boundaryField,
            exclusionField,
            gridOriginField,
            strataField,
        )
        .from(PLANTING_SITE_HISTORIES)
        .where(condition)
        .orderBy(PLANTING_SITE_HISTORIES.ID.desc())
        .fetch { record ->
          PlantingSiteHistoryModel(
              areaHa = record[PLANTING_SITE_HISTORIES.AREA_HA],
              boundary = record[boundaryField] as MultiPolygon,
              createdTime = record[PLANTING_SITE_HISTORIES.CREATED_TIME]!!,
              exclusion = record[exclusionField] as? MultiPolygon,
              gridOrigin = record[gridOriginField] as? Point,
              id = record[PLANTING_SITE_HISTORIES.ID]!!,
              plantingSiteId = record[PLANTING_SITE_HISTORIES.PLANTING_SITE_ID]!!,
              strata = strataField?.let { record[it] } ?: emptyList(),
          )
        }
  }

  fun countMonitoringPlots(plantingSiteId: PlantingSiteId): Map<StratumId, Map<SubstratumId, Int>> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    val countBySubstratumField =
        DSL.multiset(
                DSL.select(SUBSTRATA.ID.asNonNullable(), DSL.count())
                    .from(MONITORING_PLOTS)
                    .join(SUBSTRATA)
                    .on(MONITORING_PLOTS.SUBSTRATUM_ID.eq(SUBSTRATA.ID))
                    .where(SUBSTRATA.STRATUM_ID.eq(STRATA.ID))
                    .groupBy(SUBSTRATA.ID)
            )
            .convertFrom { results -> results.associate { it.value1() to it.value2() } }

    return dslContext
        .select(STRATA.ID, countBySubstratumField)
        .from(STRATA)
        .where(STRATA.PLANTING_SITE_ID.eq(plantingSiteId))
        .fetchMap(STRATA.ID.asNonNullable(), countBySubstratumField)
  }

  /**
   * Returns the number of plants currently known to be planted in the substrata at a planting site.
   * Substrata that do not currently have any plants are not included in the return value. If
   * substratum A had a planting but all the plants were late reassigned to substratum B, substratum
   * A will not be included in the return value.
   */
  fun countReportedPlantsInSubstrata(plantingSiteId: PlantingSiteId): Map<SubstratumId, Long> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    val sumField = DSL.sum(PLANTINGS.NUM_PLANTS)
    return dslContext
        .select(SUBSTRATA.ID.asNonNullable(), sumField)
        .from(SUBSTRATA)
        .join(PLANTINGS)
        .on(SUBSTRATA.ID.eq(PLANTINGS.SUBSTRATUM_ID))
        .where(SUBSTRATA.PLANTING_SITE_ID.eq(plantingSiteId))
        .groupBy(SUBSTRATA.ID)
        .having(sumField.gt(BigDecimal.ZERO))
        .fetch()
        .associate { it.value1() to it.value2().toLong() }
  }

  fun countReportedPlants(plantingSiteId: PlantingSiteId): PlantingSiteReportedPlantTotals {
    requirePermissions { readPlantingSite(plantingSiteId) }
    return fetchReportedPlants(PLANTING_SITES.ID.eq(plantingSiteId)).firstOrNull()
        ?: throw PlantingSiteNotFoundException(plantingSiteId)
  }

  fun countReportedPlantsForOrganization(
      organizationId: OrganizationId
  ): List<PlantingSiteReportedPlantTotals> {
    requirePermissions { readOrganization(organizationId) }
    return fetchReportedPlants(PLANTING_SITES.ORGANIZATION_ID.eq(organizationId))
  }

  fun countReportedPlantsForProject(projectId: ProjectId): List<PlantingSiteReportedPlantTotals> {
    requirePermissions { readProject(projectId) }
    return fetchReportedPlants(PLANTING_SITES.PROJECT_ID.eq(projectId))
  }

  fun createPlantingSite(
      newModel: NewPlantingSiteModel,
      plantingSeasons: Collection<UpdatedPlantingSeasonModel> = emptyList(),
  ): ExistingPlantingSiteModel {
    requirePermissions {
      createPlantingSite(newModel.organizationId)
      newModel.projectId?.let { readProject(it) }
    }

    if (
        newModel.projectId != null &&
            newModel.organizationId != parentStore.getOrganizationId(newModel.projectId)
    ) {
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

        newModel.strata.forEach { stratum ->
          val stratumId = createStratum(stratum, plantingSiteId, now)
          val stratumHistoryId = insertStratumHistory(stratum, siteHistoryId, stratumId)

          stratum.substrata.forEach { substratum ->
            val substratumId = createSubstratum(substratum, plantingSiteId, stratumId, now)
            insertSubstratumHistory(substratum, stratumHistoryId, substratumId)
          }
        }
      }

      val effectiveTimeZone = newModel.timeZone ?: parentStore.getEffectiveTimeZone(plantingSiteId)

      if (!plantingSeasons.isEmpty()) {
        updatePlantingSeasons(plantingSiteId, plantingSeasons, effectiveTimeZone)
      }

      log.info("Created planting site $plantingSiteId for organization ${newModel.organizationId}")

      fetchSiteById(plantingSiteId, PlantingSiteDepth.Substratum).copy(historyId = siteHistoryId)
    }
  }

  fun updatePlantingSite(
      plantingSiteId: PlantingSiteId,
      plantingSeasons: Collection<UpdatedPlantingSeasonModel>,
      editFunc: (ExistingPlantingSiteModel) -> ExistingPlantingSiteModel,
  ) {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    val initial = fetchSiteById(plantingSiteId, PlantingSiteDepth.Stratum)
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
            .set(SURVIVAL_RATE_INCLUDES_TEMP_PLOTS, edited.survivalRateIncludesTempPlots)
            .set(TIME_ZONE, edited.timeZone)
            .apply {
              // Boundaries can only be updated on simple planting sites.
              if (initial.strata.isEmpty()) {
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

      if (
          edited.boundary != null && !edited.boundary.equalsOrBothNull(initial.boundary) ||
              !edited.exclusion.equalsOrBothNull(initial.exclusion)
      ) {
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
            PlantingSiteTimeZoneChangedEvent(edited, initialTimeZone, editedTimeZone)
        )
      }

      if (initial.survivalRateIncludesTempPlots != edited.survivalRateIncludesTempPlots) {
        rateLimitedEventPublisher.publishEvent(
            RateLimitedT0DataAssignedEvent(
                organizationId = edited.organizationId,
                plantingSiteId = edited.id,
                previousSiteTempSetting = initial.survivalRateIncludesTempPlots,
                newSiteTempSetting = edited.survivalRateIncludesTempPlots,
            )
        )
      }
    }
  }

  fun applyPlantingSiteEdit(
      plantingSiteEdit: PlantingSiteEdit,
      substrataToMarkIncomplete: Set<SubstratumId> = emptySet(),
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

      if (
          plantingSiteEdit.stratumEdits.isEmpty() &&
              existing.boundary.equalsOrBothNull(plantingSiteEdit.desiredModel.boundary) &&
              existing.exclusion.equalsOrBothNull(plantingSiteEdit.desiredModel.exclusion)
      ) {
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

      useTemporaryNames(plantingSiteEdit)

      plantingSiteEdit.stratumEdits.forEach {
        replacementResults.add(
            applyStratumEdit(
                it,
                plantingSiteId,
                plantingSiteHistoryId,
                substrataToMarkIncomplete,
                now,
            )
        )
      }

      // If any strata weren't edited, we still want to include them and their substrata in the
      // site's map history.
      existing.strata.forEach { stratum ->
        if (plantingSiteEdit.stratumEdits.none { it.existingModel?.id == stratum.id }) {
          val stratumHistoryId = insertStratumHistory(stratum, plantingSiteHistoryId)
          stratum.substrata.forEach { substratum ->
            insertSubstratumHistory(substratum, stratumHistoryId)
          }
        }
      }

      // If any monitoring plots weren't edited, we still want to include them in the history too.
      dslContext
          .select(MONITORING_PLOTS.ID.asNonNullable(), MONITORING_PLOTS.SUBSTRATUM_ID)
          .from(MONITORING_PLOTS)
          .where(MONITORING_PLOTS.PLANTING_SITE_ID.eq(plantingSiteId))
          .andNotExists(
              DSL.selectOne()
                  .from(MONITORING_PLOT_HISTORIES)
                  .where(
                      MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(plantingSiteHistoryId)
                  )
                  .and(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
          )
          .fetch()
          .forEach { (monitoringPlotId, substratumId) ->
            insertMonitoringPlotHistory(monitoringPlotId, plantingSiteId, substratumId)
          }

      sanityCheckAfterEdit(plantingSiteId)

      val edited = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)

      eventPublisher.publishEvent(
          PlantingSiteMapEditedEvent(
              edited,
              plantingSiteEdit,
              ReplacementResult.merge(replacementResults),
          )
      )

      edited
    }
  }

  /**
   * Gives unique temporary names to all the strata and substrata that are being updated. This is
   * done before applying the actual stratum and substratum edits.
   *
   * Using temporary names avoids potential unique constraint violations.
   *
   * Suppose you have a site with strata A and B. You realize you mislabeled them and you really
   * want the strata to be named B and C. Without the temporary names, the system might first try to
   * rename stratum B to "C". That would bomb out with a database constraint violation because
   * stratum names are required to be unique within planting sites.
   *
   * Instead, we do it in two steps. We rename both A and B to random UUID names. Then we apply the
   * edits, which set the names to "B" and "C", neither of which collides with any of the UUIDs no
   * matter which order the updates are applied.
   */
  private fun useTemporaryNames(plantingSiteEdit: PlantingSiteEdit) {
    val stratumIdsToUpdate =
        plantingSiteEdit.stratumEdits.filterIsInstance<StratumEdit.Update>().map {
          it.existingModel.id
        }
    if (stratumIdsToUpdate.isNotEmpty()) {
      with(STRATA) {
        dslContext
            .update(STRATA)
            .set(NAME, DSL.uuid().cast(SQLDataType.VARCHAR))
            .where(ID.`in`(stratumIdsToUpdate))
            .execute()
      }
    }

    val substratumIdsToUpdate =
        plantingSiteEdit.stratumEdits
            .flatMap { it.substratumEdits }
            .filterIsInstance<SubstratumEdit.Update>()
            .map { it.existingModel.id }
    if (substratumIdsToUpdate.isNotEmpty()) {
      with(SUBSTRATA) {
        dslContext
            .update(SUBSTRATA)
            .set(NAME, DSL.uuid().cast(SQLDataType.VARCHAR))
            .where(ID.`in`(substratumIdsToUpdate))
            .execute()
      }
    }
  }

  private fun applyStratumEdit(
      edit: StratumEdit,
      plantingSiteId: PlantingSiteId,
      plantingSiteHistoryId: PlantingSiteHistoryId,
      substrataToMarkIncomplete: Set<SubstratumId>,
      now: Instant,
  ): ReplacementResult {
    val replacementResults = mutableListOf<ReplacementResult>()

    when (edit) {
      is StratumEdit.Create -> {
        val stratumId = createStratum(edit.desiredModel.toNew(), plantingSiteId, now)
        val stratumHistoryId =
            insertStratumHistory(edit.desiredModel, plantingSiteHistoryId, stratumId)
        edit.substratumEdits.forEach {
          applySubstratumEdit(
              it,
              plantingSiteId,
              stratumId,
              stratumHistoryId,
              emptySet(),
              now,
          )
        }
      }
      is StratumEdit.Delete -> {
        replacementResults.addAll(
            edit.substratumEdits.map {
              applySubstratumEdit(
                  edit = it,
                  plantingSiteId = plantingSiteId,
                  stratumId = edit.existingModel.id,
                  stratumHistoryId = null,
                  substrataToMarkIncomplete = emptySet(),
                  now = now,
              )
            }
        )

        val rowsDeleted =
            dslContext.deleteFrom(STRATA).where(STRATA.ID.eq(edit.existingModel.id)).execute()
        if (rowsDeleted != 1) {
          throw StratumNotFoundException(edit.existingModel.id)
        }
      }
      is StratumEdit.Update -> {
        with(STRATA) {
          val boundaryChanged =
              !edit.existingModel.boundary.equalsOrBothNull(edit.desiredModel.boundary)
          val rowsUpdated =
              dslContext
                  .update(STRATA)
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
            throw StratumNotFoundException(edit.existingModel.id)
          }
        }
        val stratumHistoryId =
            insertStratumHistory(
                edit.desiredModel,
                plantingSiteHistoryId,
                edit.existingModel.id,
            )

        replacementResults.addAll(
            edit.substratumEdits.map { substratumEdit ->
              applySubstratumEdit(
                  edit = substratumEdit,
                  plantingSiteId = plantingSiteId,
                  stratumId = edit.existingModel.id,
                  stratumHistoryId = stratumHistoryId,
                  substrataToMarkIncomplete = substrataToMarkIncomplete,
                  now = now,
              )
            }
        )

        // If any substrata weren't edited, we still want to include them in the site's map history.
        edit.existingModel.substrata.forEach { existingSubstratum ->
          val substratumStillInThisStratum =
              edit.desiredModel.substrata.any { it.name == existingSubstratum.name }
          val noEditForThisSubstratum =
              edit.substratumEdits.none { it.existingModel?.id == existingSubstratum.id }

          if (substratumStillInThisStratum && noEditForThisSubstratum) {
            insertSubstratumHistory(existingSubstratum, stratumHistoryId)
          }
        }

        // Need to create permanent plots using the updated substrata since we need their IDs.
        val updatedSite = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)
        val updatedStratum = updatedSite.strata.single { it.id == edit.existingModel.id }

        edit.monitoringPlotEdits
            .filter { it.permanentIndex != null }
            .groupBy { it.region }
            .forEach { (region, plotEdits) ->
              val newPlotIds =
                  createPermanentPlots(
                      updatedSite,
                      updatedStratum,
                      plotEdits.map { it.permanentIndex!! },
                      region,
                  )
              replacementResults.add(ReplacementResult(newPlotIds.toSet(), emptySet()))
            }
      }
    }

    return ReplacementResult.merge(replacementResults)
  }

  private fun applySubstratumEdit(
      edit: SubstratumEdit,
      plantingSiteId: PlantingSiteId,
      stratumId: StratumId,
      stratumHistoryId: StratumHistoryId?,
      substrataToMarkIncomplete: Set<SubstratumId>,
      now: Instant,
  ): ReplacementResult {
    val replacementResults = mutableListOf<ReplacementResult>()

    when (edit) {
      is SubstratumEdit.Create -> {
        if (stratumHistoryId == null) {
          throw IllegalArgumentException("Substratum creation requires stratum history ID")
        }

        val substratumId =
            createSubstratum(edit.desiredModel.toNew(), plantingSiteId, stratumId, now)
        insertSubstratumHistory(edit.desiredModel, stratumHistoryId, substratumId)

        edit.monitoringPlotEdits.forEach { plotEdit ->
          replacementResults.add(
              applyMonitoringPlotEdit(plotEdit, plantingSiteId, substratumId, now)
          )
        }
      }
      is SubstratumEdit.Delete -> {
        // Plots will be deleted by ON DELETE CASCADE. This may legitimately delete 0 rows if the
        // parent stratum has already been deleted.
        dslContext.deleteFrom(SUBSTRATA).where(SUBSTRATA.ID.eq(edit.existingModel.id)).execute()

        replacementResults.add(
            ReplacementResult(emptySet(), edit.existingModel.monitoringPlots.map { it.id }.toSet())
        )
      }
      is SubstratumEdit.Update -> {
        if (stratumHistoryId == null) {
          throw IllegalArgumentException("Substratum update requires stratum history ID")
        }

        val substratumId = edit.existingModel.id
        val markIncomplete = !edit.addedRegion.isEmpty && substratumId in substrataToMarkIncomplete

        with(SUBSTRATA) {
          val rowsUpdated =
              dslContext
                  .update(SUBSTRATA)
                  .set(AREA_HA, edit.desiredModel.areaHa)
                  .set(BOUNDARY, edit.desiredModel.boundary)
                  .set(FULL_NAME, edit.desiredModel.fullName)
                  .set(MODIFIED_BY, currentUser().userId)
                  .set(MODIFIED_TIME, now)
                  .set(NAME, edit.desiredModel.name)
                  .let { if (markIncomplete) it.setNull(PLANTING_COMPLETED_TIME) else it }
                  .set(STRATUM_ID, stratumId)
                  .where(ID.eq(substratumId))
                  .execute()
          if (rowsUpdated != 1) {
            throw SubstratumNotFoundException(substratumId)
          }
        }

        insertSubstratumHistory(edit.desiredModel, stratumHistoryId, substratumId)

        edit.monitoringPlotEdits.forEach { plotEdit ->
          replacementResults.add(
              applyMonitoringPlotEdit(plotEdit, plantingSiteId, substratumId, now)
          )
        }
      }
    }

    return ReplacementResult.merge(replacementResults)
  }

  private fun applyMonitoringPlotEdit(
      edit: MonitoringPlotEdit,
      plantingSiteId: PlantingSiteId,
      substratumId: SubstratumId,
      now: Instant,
  ): ReplacementResult {
    return when (edit) {
      is MonitoringPlotEdit.Adopt -> {
        with(MONITORING_PLOTS) {
          dslContext
              .update(MONITORING_PLOTS)
              .set(PERMANENT_INDEX, edit.permanentIndex)
              .set(SUBSTRATUM_ID, substratumId)
              .set(MODIFIED_BY, currentUser().userId)
              .set(MODIFIED_TIME, now)
              .where(ID.eq(edit.monitoringPlotId))
              .execute()
        }

        insertMonitoringPlotHistory(edit.monitoringPlotId, plantingSiteId, substratumId)

        ReplacementResult(emptySet(), emptySet())
      }

      is MonitoringPlotEdit.Create ->
          throw IllegalStateException(
              "BUG! Monitoring plot creation should be handled at stratum level"
          )

      is MonitoringPlotEdit.Eject -> {
        with(MONITORING_PLOTS) {
          dslContext
              .update(MONITORING_PLOTS)
              .setNull(PERMANENT_INDEX)
              .setNull(SUBSTRATUM_ID)
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
    // Make sure we haven't assigned the same permanent index to two plots in a stratum. We don't
    // enforce this with a database constraint because duplicate indexes are a valid intermediate
    // state while a complex edit is being applied.
    val strataWithDuplicatePermanentIndexes =
        dslContext
            .select(STRATA.ID, STRATA.NAME, MONITORING_PLOTS.PERMANENT_INDEX)
            .from(STRATA)
            .join(SUBSTRATA)
            .on(STRATA.ID.eq(SUBSTRATA.STRATUM_ID))
            .join(MONITORING_PLOTS)
            .on(SUBSTRATA.ID.eq(MONITORING_PLOTS.SUBSTRATUM_ID))
            .where(STRATA.PLANTING_SITE_ID.eq(plantingSiteId))
            .and(MONITORING_PLOTS.PERMANENT_INDEX.isNotNull)
            .groupBy(STRATA.ID, STRATA.NAME, MONITORING_PLOTS.PERMANENT_INDEX)
            .having(DSL.count().gt(1))
            .fetch { record ->
              "stratum ${record[STRATA.ID]} (${record[STRATA.NAME]}) " +
                  "index ${record[MONITORING_PLOTS.PERMANENT_INDEX]}"
            }
    if (strataWithDuplicatePermanentIndexes.isNotEmpty()) {
      val details = strataWithDuplicatePermanentIndexes.joinToString()
      throw IllegalStateException("BUG! Edit resulted in duplicate permanent indexes: $details")
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
          if (
              existingSeason.startDate != desiredSeason.startDate ||
                  existingSeason.startTime >= now && existingTimeZone != desiredTimeZone
          ) {
            desiredSeason.startDate.toInstant(desiredTimeZone)
          } else {
            existingSeason.startTime
          }
      val endTime =
          if (
              existingSeason.endDate != desiredSeason.endDate ||
                  existingSeason.endTime >= now && existingTimeZone != desiredTimeZone
          ) {
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
              desiredSeason.endDate,
          )
      )
    }

    if (seasonsToInsert.isNotEmpty()) {
      plantingSeasonsDao.insert(seasonsToInsert)

      seasonsToInsert.forEach { season ->
        eventPublisher.publishEvent(
            PlantingSeasonScheduledEvent(
                plantingSiteId,
                season.id!!,
                season.startDate!!,
                season.endDate!!,
            )
        )
      }
    }
  }

  fun updateStratum(
      stratumId: StratumId,
      editFunc: (StrataRow) -> StrataRow,
  ) {
    requirePermissions { updateStratum(stratumId) }

    val initial = strataDao.fetchOneById(stratumId) ?: throw StratumNotFoundException(stratumId)
    val edited = editFunc(initial)

    withLockedPlantingSite(initial.plantingSiteId!!) {
      with(STRATA) {
        dslContext
            .update(STRATA)
            .set(ERROR_MARGIN, edited.errorMargin)
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, clock.instant())
            .set(NAME, edited.name)
            .set(NUM_PERMANENT_PLOTS, edited.numPermanentPlots)
            .set(NUM_TEMPORARY_PLOTS, edited.numTemporaryPlots)
            .set(STUDENTS_T, edited.studentsT)
            .set(TARGET_PLANTING_DENSITY, edited.targetPlantingDensity)
            .set(VARIANCE, edited.variance)
            .where(ID.eq(stratumId))
            .execute()
      }

      if (initial.name != edited.name) {
        with(SUBSTRATA) {
          dslContext
              .update(SUBSTRATA)
              .set(FULL_NAME, DSL.concat("${edited.name}-", NAME))
              .set(MODIFIED_BY, currentUser().userId)
              .set(MODIFIED_TIME, clock.instant())
              .where(STRATUM_ID.eq(stratumId))
              .execute()
        }

        with(STRATUM_HISTORIES) {
          val stratumHistoryId =
              dslContext
                  .select(ID)
                  .from(STRATUM_HISTORIES)
                  .where(STRATUM_ID.eq(stratumId))
                  .orderBy(ID.desc())
                  .limit(1)
                  .fetchSingle(ID)

          dslContext
              .update(STRATUM_HISTORIES)
              .set(NAME, edited.name)
              .where(ID.eq(stratumHistoryId))
              .execute()

          with(SUBSTRATUM_HISTORIES) {
            dslContext
                .update(SUBSTRATUM_HISTORIES)
                .set(FULL_NAME, DSL.concat("${edited.name}-", NAME))
                .where(STRATUM_HISTORY_ID.eq(stratumHistoryId))
                .execute()
          }
        }
      }
    }
  }

  /**
   * Marks a substratum as having completed planting or not. The "planting completed time" value,
   * though it's a timestamp, is treated as a flag:
   * - If the existing planting completed time is null and [completed] is true, the planting
   *   completed time in the database is set to the current time.
   * - If the existing planting completed time is non-null and [completed] is false, the planting
   *   completed time in the database is cleared.
   * - Otherwise, the existing value is left as-is. That is, repeatedly calling this function with
   *   [completed] == true will not cause the planting completed time in the database to change.
   */
  fun updateSubstratumCompleted(substratumId: SubstratumId, completed: Boolean) {
    requirePermissions { updateSubstratumCompleted(substratumId) }

    val initial =
        substrataDao.fetchOneById(substratumId) ?: throw SubstratumNotFoundException(substratumId)

    val plantingCompletedTime =
        if (completed) initial.plantingCompletedTime ?: clock.instant() else null

    if (plantingCompletedTime != initial.plantingCompletedTime) {
      with(SUBSTRATA) {
        dslContext
            .update(SUBSTRATA)
            .set(PLANTING_COMPLETED_TIME, plantingCompletedTime)
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, clock.instant())
            .where(ID.eq(substratumId))
            .execute()
      }
    }
  }

  private fun createStratum(
      stratum: NewStratumModel,
      plantingSiteId: PlantingSiteId,
      now: Instant = clock.instant(),
  ): StratumId {
    val userId = currentUser().userId

    val strataRow =
        StrataRow(
            areaHa = stratum.areaHa,
            boundary = stratum.boundary,
            boundaryModifiedBy = userId,
            boundaryModifiedTime = now,
            createdBy = userId,
            createdTime = now,
            errorMargin = stratum.errorMargin,
            modifiedBy = userId,
            modifiedTime = now,
            name = stratum.name,
            numPermanentPlots = stratum.numPermanentPlots,
            numTemporaryPlots = stratum.numTemporaryPlots,
            plantingSiteId = plantingSiteId,
            stableId = stratum.stableId,
            studentsT = stratum.studentsT,
            targetPlantingDensity = stratum.targetPlantingDensity,
            variance = stratum.variance,
        )

    strataDao.insert(strataRow)

    return strataRow.id!!
  }

  private fun createSubstratum(
      substratum: NewSubstratumModel,
      plantingSiteId: PlantingSiteId,
      stratumId: StratumId,
      now: Instant = clock.instant(),
  ): SubstratumId {
    val userId = currentUser().userId

    val substrataRow =
        SubstrataRow(
            areaHa = substratum.areaHa,
            boundary = substratum.boundary,
            createdBy = userId,
            createdTime = now,
            fullName = substratum.fullName,
            modifiedBy = userId,
            modifiedTime = now,
            name = substratum.name,
            plantingCompletedTime = substratum.plantingCompletedTime,
            plantingSiteId = plantingSiteId,
            stratumId = stratumId,
            stableId = substratum.stableId,
        )

    substrataDao.insert(substrataRow)

    return substrataRow.id!!
  }

  private fun setMonitoringPlotPermanentIndex(
      monitoringPlotId: MonitoringPlotId,
      permanentIndex: Int,
  ) {
    with(MONITORING_PLOTS) {
      dslContext
          .update(MONITORING_PLOTS)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(PERMANENT_INDEX, permanentIndex)
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

  /**
   * Returns true if the ID refers to a detailed planting site, that is, a site with strata and
   * substrata.
   */
  fun isDetailed(plantingSiteId: PlantingSiteId): Boolean {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return dslContext.fetchExists(
        SUBSTRATA,
        SUBSTRATA.PLANTING_SITE_ID.eq(plantingSiteId),
    )
  }

  fun hasSubstratumPlantings(plantingSiteId: PlantingSiteId): Boolean {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return dslContext.fetchExists(
        PLANTINGS,
        PLANTINGS.PLANTING_SITE_ID.eq(plantingSiteId),
        PLANTINGS.SUBSTRATUM_ID.isNotNull,
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

  fun fetchSitesWithSubstratumPlantings(condition: Condition): List<PlantingSiteId> {
    requirePermissions { manageNotifications() }

    return dslContext
        .select(PLANTING_SITES.ID)
        .from(PLANTING_SITES)
        .where(condition)
        .andExists(
            DSL.selectOne()
                .from(PLANTINGS)
                .where(PLANTINGS.PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
                .and(PLANTINGS.SUBSTRATUM_ID.isNotNull)
        )
        .fetch(PLANTING_SITES.ID.asNonNullable())
  }

  fun fetchPartiallyPlantedDetailedSitesWithNoPlantingSeasons(
      weeksSinceCreation: Int,
      additionalCondition: Condition,
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
                .where(PLANTING_SITES.ID.eq(PLANTING_SEASONS.PLANTING_SITE_ID))
        )
        .andExists(
            DSL.selectOne()
                .from(SUBSTRATA)
                .where(PLANTING_SITES.ID.eq(SUBSTRATA.PLANTING_SITE_ID))
                .and(SUBSTRATA.PLANTING_COMPLETED_TIME.isNull)
        )
        .orderBy(PLANTING_SITES.ID)
        .fetch(PLANTING_SITES.ID.asNonNullable())
  }

  fun fetchPartiallyPlantedDetailedSitesWithNoUpcomingPlantingSeasons(
      weeksSinceLastSeason: Int,
      additionalCondition: Condition,
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
                        .where(PLANTING_SITES.ID.eq(PLANTING_SEASONS.PLANTING_SITE_ID))
                )
                .le(maxEndTime)
        )
        .andExists(
            DSL.selectOne()
                .from(SUBSTRATA)
                .where(PLANTING_SITES.ID.eq(SUBSTRATA.PLANTING_SITE_ID))
                .and(SUBSTRATA.PLANTING_COMPLETED_TIME.isNull)
        )
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
   * The requested plot's "is available" flag is set to false, and its permanent index is cleared.
   * If the plot was permanent, a new one with the removed index will be created next time
   * [ensurePermanentPlotsExist] is called.
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
          .setNull(MONITORING_PLOTS.PERMANENT_INDEX)
          .where(MONITORING_PLOTS.ID.eq(monitoringPlotId))
          .execute()

      ReplacementResult(
          addedMonitoringPlotIds = emptySet(),
          removedMonitoringPlotIds = setOf(monitoringPlotId),
      )
    } else {
      ReplacementResult(emptySet(), emptySet())
    }
  }

  /**
   * Replaces a monitoring plot's permanent index with an unused one.
   *
   * If there are existing permanent plots that haven't been used in any observations yet, uses one
   * of them; the existing plot's permanent index will be changed to the index of the plot being
   * replaced.
   *
   * If there are no existing unused permanent plots, tries to create a new one at a random location
   * in the stratum.
   *
   * If the monitoring plot has no permanent index, or there are no available places to put a new
   * permanent plot, does nothing.
   *
   * @return A result whose "added plots" property has the ID of the replacement monitoring plot and
   *   whose "removed plots" property has the ID of the requested plot, if the replacement
   *   succeeded.
   */
  fun replacePermanentIndex(monitoringPlotId: MonitoringPlotId): ReplacementResult {
    val plantingSiteId =
        parentStore.getPlantingSiteId(monitoringPlotId)
            ?: throw PlotNotFoundException(monitoringPlotId)

    requirePermissions {
      readMonitoringPlot(monitoringPlotId)
      updatePlantingSite(plantingSiteId)
    }

    return withLockedPlantingSite(plantingSiteId) {
      val plantingSite = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)
      val stratum =
          plantingSite.findStratumWithMonitoringPlot(monitoringPlotId)
              ?: throw PlotNotFoundException(monitoringPlotId)
      val plot =
          stratum
              .findSubstratumWithMonitoringPlot(monitoringPlotId)
              ?.findMonitoringPlot(monitoringPlotId)
              ?: throw PlotNotFoundException(monitoringPlotId)

      if (plot.permanentIndex == null) {
        log.warn("Cannot replace non-permanent plot $monitoringPlotId")
        return@withLockedPlantingSite ReplacementResult(emptySet(), emptySet())
      }

      val unusedPermanentIndex = fetchUnusedPermanentIndex(stratum.id)
      val replacementPlotId =
          fetchPermanentPlotId(stratum.id, unusedPermanentIndex)
              ?: run {
                log.debug("Creating new permanent plot to use as replacement")
                createPermanentPlots(plantingSite, stratum, listOf(unusedPermanentIndex))
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
              .setNull(PERMANENT_INDEX)
              .where(ID.eq(monitoringPlotId))
              .execute()

          dslContext
              .update(MONITORING_PLOTS)
              .set(MODIFIED_BY, userId)
              .set(MODIFIED_TIME, now)
              .set(PERMANENT_INDEX, plot.permanentIndex)
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
   * Ensures that the required number of permanent plots exists in each of a planting site's strata.
   * There need to be plots with numbers from 1 to the stratum's permanent plot count.
   *
   * @return The IDs of any newly-created monitoring plots.
   */
  fun ensurePermanentPlotsExist(plantingSiteId: PlantingSiteId): List<MonitoringPlotId> {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    return withLockedPlantingSite(plantingSiteId) {
      val plantingSite = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)

      plantingSite.strata.flatMap { stratum ->
        val missingPermanentIndexes: List<Int> =
            (1..stratum.numPermanentPlots).filterNot { stratum.permanentIndexExists(it) }

        createPermanentPlots(plantingSite, stratum, missingPermanentIndexes)
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
            organizationId,
            NumericIdentifierType.PlotNumber,
        )

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
            MONITORING_PLOTS.PERMANENT_INDEX,
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
              permanentIndex = record[MONITORING_PLOTS.PERMANENT_INDEX],
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
                .toTypedArray()
        )

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
   * Creates permanent plots with a specific set of permanent indexes. The permanent plots may
   * include a mix of newly-created monitoring plots and plots that exist already but were only used
   * as temporary plots in the past.
   */
  private fun createPermanentPlots(
      plantingSite: ExistingPlantingSiteModel,
      stratum: ExistingStratumModel,
      permanentIndexes: List<Int>,
      searchBoundary: MultiPolygon = stratum.boundary,
  ): List<MonitoringPlotId> {
    val userId = currentUser().userId
    val now = clock.instant()

    if (plantingSite.gridOrigin == null) {
      throw IllegalStateException("Planting site ${plantingSite.id} has no grid origin")
    }

    // List of [boundary, permanent index]
    val plotBoundaries: List<Pair<Polygon, Int>> =
        stratum
            .findUnusedSquares(
                count = permanentIndexes.size,
                excludeAllPermanentPlots = true,
                exclusion = plantingSite.exclusion,
                gridOrigin = plantingSite.gridOrigin,
                searchBoundary = searchBoundary,
                sizeMeters = MONITORING_PLOT_SIZE,
            )
            .zip(permanentIndexes)

    return plotBoundaries.map { (plotBoundary, permanentIndex) ->
      val existingPlot = stratum.findMonitoringPlot(plotBoundary)

      if (existingPlot != null) {
        if (existingPlot.permanentIndex != null) {
          throw IllegalStateException("Cannot place new permanent plot over existing one")
        }

        setMonitoringPlotPermanentIndex(existingPlot.id, permanentIndex)

        existingPlot.id
      } else {
        val substratum =
            stratum.findSubstratum(plotBoundary)
                ?: throw IllegalStateException(
                    "Stratum ${stratum.id} not fully covered by substrata"
                )
        val plotNumber =
            identifierGenerator.generateNumericIdentifier(
                plantingSite.organizationId,
                NumericIdentifierType.PlotNumber,
            )

        val monitoringPlotsRow =
            MonitoringPlotsRow(
                boundary = plotBoundary,
                createdBy = userId,
                createdTime = now,
                isAdHoc = false,
                modifiedBy = userId,
                modifiedTime = now,
                organizationId = plantingSite.organizationId,
                permanentIndex = permanentIndex,
                plantingSiteId = plantingSite.id,
                substratumId = substratum.id,
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
      stratumId: StratumId,
      plotBoundary: Polygon,
  ): MonitoringPlotId {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    val userId = currentUser().userId
    val now = clock.instant()

    return withLockedPlantingSite(plantingSiteId) {
      val plantingSite = fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)
      val stratum =
          plantingSite.strata.singleOrNull { it.id == stratumId }
              ?: throw StratumNotFoundException(stratumId)

      val existingPlotId = stratum.findMonitoringPlot(plotBoundary)?.id
      if (existingPlotId != null) {
        existingPlotId
      } else {
        val plotNumber =
            identifierGenerator.generateNumericIdentifier(
                plantingSite.organizationId,
                NumericIdentifierType.PlotNumber,
            )
        val substratum =
            stratum.findSubstratum(plotBoundary)
                ?: throw IllegalStateException("Stratum $stratumId not fully covered by substrata")

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
                substratumId = substratum.id,
                plotNumber = plotNumber,
                sizeMeters = MONITORING_PLOT_SIZE_INT,
            )
        monitoringPlotsDao.insert(monitoringPlotsRow)

        insertMonitoringPlotHistory(monitoringPlotsRow)

        monitoringPlotsRow.id!!
      }
    }
  }

  fun migrateSimplePlantingSites(
      addSuccessMessage: (String) -> Unit,
      addFailureMessage: (String) -> Unit,
  ) {
    // These are the names we use in terraware-web when creating a new planting site without
    // specifying strata or substrata. They are not translated into the user's language.
    val stratumName = "Stratum 01"
    val substratumName = "Substratum A"

    dslContext.transaction { _ ->
      val simplePlantingSiteIds =
          dslContext
              .select(PLANTING_SITES.ID)
              .distinctOn(PLANTING_SITES.ID)
              .from(PLANTING_SITES)
              .where(PLANTING_SITES.BOUNDARY.isNotNull)
              .andNotExists(
                  DSL.selectOne().from(STRATA).where(STRATA.PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
              )
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
                "Planting site $siteId (${existingSite.name}) area too small to convert"
            )
            continue
          }

          val stratum =
              NewStratumModel.create(
                  boundary = boundary,
                  name = stratumName,
                  substrata = emptyList(),
                  stableId = StableId(stratumName),
              )
          val fullName = "$stratumName-$substratumName"
          val substratum =
              NewSubstratumModel.create(
                  boundary = boundary,
                  fullName = fullName,
                  name = substratumName,
                  stableId = StableId(fullName),
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
          val stratumId = createStratum(stratum, siteId)
          val stratumHistoryId = insertStratumHistory(stratum, siteHistoryId, stratumId)
          val substratumId = createSubstratum(substratum, siteId, stratumId)
          insertSubstratumHistory(substratum, stratumHistoryId, substratumId)

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
    // When plants are withdrawn to a detailed planting site, we update the site, stratum, and
    // substratum populations. When they're withdrawn to a simple planting site, we only update the
    // site populations since there are no strata or substrata.
    //
    // After converting a simple planting site to a detailed one, there will be values in
    // planting_site_populations but no values for any of the site's strata or substrata in the
    // corresponding tables. So we want to copy the site values down to those two tables.

    dslContext.transaction { _ ->
      with(STRATUM_POPULATIONS) {
        val rowsInserted =
            dslContext
                .insertInto(
                    STRATUM_POPULATIONS,
                    STRATUM_ID,
                    SPECIES_ID,
                    TOTAL_PLANTS,
                    PLANTS_SINCE_LAST_OBSERVATION,
                )
                .select(
                    DSL.select(
                            STRATA.ID,
                            PLANTING_SITE_POPULATIONS.SPECIES_ID,
                            PLANTING_SITE_POPULATIONS.TOTAL_PLANTS,
                            PLANTING_SITE_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION,
                        )
                        .from(PLANTING_SITE_POPULATIONS)
                        .join(STRATA)
                        .on(PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID.eq(STRATA.PLANTING_SITE_ID))
                        .whereNotExists(
                            DSL.selectOne()
                                .from(STRATUM_POPULATIONS)
                                .join(STRATA)
                                .on(STRATUM_ID.eq(STRATA.ID))
                                .where(
                                    STRATA.PLANTING_SITE_ID.eq(
                                        PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID
                                    )
                                )
                        )
                        .and(
                            DSL.value(1)
                                .eq(
                                    DSL.selectCount()
                                        .from(STRATA)
                                        .where(
                                            STRATA.PLANTING_SITE_ID.eq(
                                                PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID
                                            )
                                        )
                                )
                        )
                )
                .execute()

        log.info("Inserted $rowsInserted stratum populations")
      }

      with(SUBSTRATUM_POPULATIONS) {
        val rowsInserted =
            dslContext
                .insertInto(
                    SUBSTRATUM_POPULATIONS,
                    SUBSTRATUM_ID,
                    SPECIES_ID,
                    TOTAL_PLANTS,
                    PLANTS_SINCE_LAST_OBSERVATION,
                )
                .select(
                    DSL.select(
                            SUBSTRATA.ID,
                            PLANTING_SITE_POPULATIONS.SPECIES_ID,
                            PLANTING_SITE_POPULATIONS.TOTAL_PLANTS,
                            PLANTING_SITE_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION,
                        )
                        .from(PLANTING_SITE_POPULATIONS)
                        .join(SUBSTRATA)
                        .on(
                            PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID.eq(
                                SUBSTRATA.PLANTING_SITE_ID
                            )
                        )
                        .whereNotExists(
                            DSL.selectOne()
                                .from(SUBSTRATUM_POPULATIONS)
                                .join(SUBSTRATA)
                                .on(SUBSTRATUM_ID.eq(SUBSTRATA.ID))
                                .where(
                                    SUBSTRATA.PLANTING_SITE_ID.eq(
                                        PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID
                                    )
                                )
                        )
                        .and(
                            DSL.value(1)
                                .eq(
                                    DSL.selectCount()
                                        .from(SUBSTRATA)
                                        .where(
                                            SUBSTRATA.PLANTING_SITE_ID.eq(
                                                PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID
                                            )
                                        )
                                )
                        )
                )
                .execute()

        log.info("Inserted $rowsInserted substratum populations")
      }
    }
  }

  private fun migrateSimplePlantingSitePlantings() {
    // Outplanting withdrawals to simple planting sites result in rows in the plantings table
    // with the planting site ID populated but the substratum ID set to null. Withdrawals to
    // detailed planting sites are required to have a substratum ID. So we want to fill in the
    // substratum ID for any plantings that have null substratum IDs but where the planting site has
    // a substratum, since those will be the sites whose strata and substrata we've just created.
    val rowsUpdated =
        dslContext
            .update(PLANTINGS)
            .set(
                PLANTINGS.SUBSTRATUM_ID,
                DSL.select(SUBSTRATA.ID)
                    .from(SUBSTRATA)
                    .where(SUBSTRATA.PLANTING_SITE_ID.eq(PLANTINGS.PLANTING_SITE_ID)),
            )
            .where(PLANTINGS.SUBSTRATUM_ID.isNull)
            .andExists(
                DSL.selectOne()
                    .from(SUBSTRATA)
                    .where(SUBSTRATA.PLANTING_SITE_ID.eq(PLANTINGS.PLANTING_SITE_ID))
            )
            .and(
                DSL.value(1)
                    .eq(
                        DSL.selectCount()
                            .from(SUBSTRATA)
                            .where(SUBSTRATA.PLANTING_SITE_ID.eq(PLANTINGS.PLANTING_SITE_ID))
                    )
            )
            .execute()

    log.info("Populated substrata for $rowsUpdated plantings")
  }

  private val plantingSeasonsMultiset =
      DSL.multiset(
              DSL.select(
                      PLANTING_SEASONS.END_DATE,
                      PLANTING_SEASONS.END_TIME,
                      PLANTING_SEASONS.ID,
                      PLANTING_SEASONS.IS_ACTIVE,
                      PLANTING_SEASONS.START_DATE,
                      PLANTING_SEASONS.START_TIME,
                  )
                  .from(PLANTING_SEASONS)
                  .where(PLANTING_SITES.ID.eq(PLANTING_SEASONS.PLANTING_SITE_ID))
                  .orderBy(PLANTING_SEASONS.START_DATE)
          )
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

  private fun <T> latestObservationField(
      observationsTableField: TableField<ObservationsRecord, T>,
      observationPlotCondition: Condition,
  ): Field<T?> =
      DSL.field(
          DSL.select(observationsTableField)
              .from(OBSERVATIONS)
              .join(OBSERVATION_PLOTS)
              .on(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
              .where(OBSERVATIONS.IS_AD_HOC.isFalse)
              .and(OBSERVATIONS.OBSERVATION_TYPE_ID.eq(ObservationType.Monitoring))
              .and(OBSERVATIONS.COMPLETED_TIME.isNotNull)
              .and(OBSERVATION_PLOTS.STATUS_ID.eq(ObservationPlotStatus.Completed))
              .and(observationPlotCondition)
              .orderBy(OBSERVATIONS.COMPLETED_TIME.desc())
              .limit(1)
      )

  private fun monitoringPlotsMultiset(condition: Condition): Field<List<MonitoringPlotModel>> {
    return DSL.multiset(
            DSL.select(
                    MONITORING_PLOTS.ELEVATION_METERS,
                    MONITORING_PLOTS.ID,
                    MONITORING_PLOTS.IS_AD_HOC,
                    MONITORING_PLOTS.IS_AVAILABLE,
                    MONITORING_PLOTS.PERMANENT_INDEX,
                    MONITORING_PLOTS.PLOT_NUMBER,
                    MONITORING_PLOTS.SIZE_METERS,
                    monitoringPlotBoundaryField,
                )
                .from(MONITORING_PLOTS)
                .where(condition)
                .orderBy(MONITORING_PLOTS.PLOT_NUMBER)
        )
        .convertFrom { result ->
          result.map { record ->
            MonitoringPlotModel(
                boundary = record[monitoringPlotBoundaryField]!! as Polygon,
                elevationMeters = record[MONITORING_PLOTS.ELEVATION_METERS],
                id = record[MONITORING_PLOTS.ID]!!,
                isAdHoc = record[MONITORING_PLOTS.IS_AD_HOC]!!,
                isAvailable = record[MONITORING_PLOTS.IS_AVAILABLE]!!,
                permanentIndex = record[MONITORING_PLOTS.PERMANENT_INDEX],
                plotNumber = record[MONITORING_PLOTS.PLOT_NUMBER]!!,
                sizeMeters = record[MONITORING_PLOTS.SIZE_METERS]!!,
            )
          }
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
                      monitoringPlotBoundaryField,
                  )
                  .from(MONITORING_PLOT_HISTORIES)
                  .join(MONITORING_PLOTS)
                  .on(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .where(
                      SUBSTRATUM_HISTORIES.ID.eq(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID)
                  )
                  .orderBy(MONITORING_PLOTS.PLOT_NUMBER)
          )
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

  private fun substrataMultiset(depth: PlantingSiteDepth): Field<List<ExistingSubstratumModel>> {
    val plotsField =
        if (depth == PlantingSiteDepth.Plot)
            monitoringPlotsMultiset(
                DSL.and(
                    SUBSTRATA.ID.eq(MONITORING_PLOTS.SUBSTRATUM_ID),
                    MONITORING_PLOTS.IS_AD_HOC.isFalse(),
                )
            )
        else null

    val observationPlotCondition =
        OBSERVATION_PLOTS.MONITORING_PLOT_ID.`in`(
            DSL.select(MONITORING_PLOTS.ID)
                .from(MONITORING_PLOTS)
                .where(MONITORING_PLOTS.SUBSTRATUM_ID.eq(SUBSTRATA.ID))
        )

    val latestObservationIdField = latestObservationField(OBSERVATIONS.ID, observationPlotCondition)
    val latestObservationTimeField =
        latestObservationField(OBSERVATIONS.COMPLETED_TIME, observationPlotCondition)

    return DSL.multiset(
            DSL.select(
                    SUBSTRATA.AREA_HA,
                    SUBSTRATA.ID,
                    SUBSTRATA.FULL_NAME,
                    SUBSTRATA.NAME,
                    SUBSTRATA.OBSERVED_TIME,
                    SUBSTRATA.PLANTING_COMPLETED_TIME,
                    SUBSTRATA.STABLE_ID,
                    substratumBoundaryField,
                    latestObservationIdField,
                    latestObservationTimeField,
                    plotsField,
                )
                .from(SUBSTRATA)
                .where(STRATA.ID.eq(SUBSTRATA.STRATUM_ID))
                .orderBy(SUBSTRATA.FULL_NAME)
        )
        .convertFrom { result ->
          result.map { record: Record ->
            ExistingSubstratumModel(
                areaHa = record[SUBSTRATA.AREA_HA]!!,
                boundary = record[substratumBoundaryField]!! as MultiPolygon,
                id = record[SUBSTRATA.ID]!!,
                fullName = record[SUBSTRATA.FULL_NAME]!!,
                monitoringPlots = plotsField?.let { record[it] } ?: emptyList(),
                latestObservationCompletedTime = record[latestObservationTimeField],
                latestObservationId = record[latestObservationIdField],
                name = record[SUBSTRATA.NAME]!!,
                observedTime = record[SUBSTRATA.OBSERVED_TIME],
                plantingCompletedTime = record[SUBSTRATA.PLANTING_COMPLETED_TIME],
                stableId = record[SUBSTRATA.STABLE_ID]!!,
            )
          }
        }
  }

  private fun substratumHistoriesMultiset(
      depth: PlantingSiteDepth
  ): Field<List<SubstratumHistoryModel>> {
    val plotsField = if (depth == PlantingSiteDepth.Plot) monitoringPlotHistoriesMultiset else null
    val boundaryField = SUBSTRATUM_HISTORIES.BOUNDARY.forMultiset()

    return DSL.multiset(
            DSL.select(
                    SUBSTRATUM_HISTORIES.AREA_HA,
                    SUBSTRATUM_HISTORIES.FULL_NAME,
                    SUBSTRATUM_HISTORIES.ID,
                    SUBSTRATUM_HISTORIES.NAME,
                    SUBSTRATUM_HISTORIES.SUBSTRATUM_ID,
                    SUBSTRATUM_HISTORIES.STABLE_ID,
                    boundaryField,
                    plotsField,
                )
                .from(SUBSTRATUM_HISTORIES)
                .where(STRATUM_HISTORIES.ID.eq(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID))
                .orderBy(SUBSTRATUM_HISTORIES.FULL_NAME)
        )
        .convertFrom { result ->
          result.map { record: Record ->
            SubstratumHistoryModel(
                record[SUBSTRATUM_HISTORIES.AREA_HA]!!,
                record[boundaryField] as MultiPolygon,
                record[SUBSTRATUM_HISTORIES.FULL_NAME]!!,
                record[SUBSTRATUM_HISTORIES.ID]!!,
                plotsField?.let { record[it] } ?: emptyList(),
                record[SUBSTRATUM_HISTORIES.NAME]!!,
                record[SUBSTRATUM_HISTORIES.SUBSTRATUM_ID],
                record[SUBSTRATUM_HISTORIES.STABLE_ID]!!,
            )
          }
        }
  }

  private fun strataMultiset(depth: PlantingSiteDepth): Field<List<ExistingStratumModel>> {
    val substrataField =
        if (depth == PlantingSiteDepth.Substratum || depth == PlantingSiteDepth.Plot) {
          substrataMultiset(depth)
        } else {
          null
        }

    val observationPlotCondition =
        OBSERVATION_PLOTS.MONITORING_PLOT_ID.`in`(
            DSL.select(MONITORING_PLOTS.ID)
                .from(MONITORING_PLOTS)
                .join(SUBSTRATA)
                .on(SUBSTRATA.ID.eq(MONITORING_PLOTS.SUBSTRATUM_ID))
                .where(SUBSTRATA.STRATUM_ID.eq(STRATA.ID))
        )
    val latestObservationIdField = latestObservationField(OBSERVATIONS.ID, observationPlotCondition)
    val latestObservationTimeField =
        latestObservationField(OBSERVATIONS.COMPLETED_TIME, observationPlotCondition)

    return DSL.multiset(
            DSL.select(
                    STRATA.AREA_HA,
                    STRATA.BOUNDARY_MODIFIED_TIME,
                    STRATA.ERROR_MARGIN,
                    STRATA.ID,
                    STRATA.NAME,
                    STRATA.NUM_PERMANENT_PLOTS,
                    STRATA.NUM_TEMPORARY_PLOTS,
                    STRATA.STABLE_ID,
                    STRATA.STUDENTS_T,
                    STRATA.TARGET_PLANTING_DENSITY,
                    STRATA.VARIANCE,
                    strataBoundaryField,
                    latestObservationIdField,
                    latestObservationTimeField,
                    substrataField,
                )
                .from(STRATA)
                .where(PLANTING_SITES.ID.eq(STRATA.PLANTING_SITE_ID))
                .orderBy(STRATA.NAME)
        )
        .convertFrom { result ->
          result.map { record: Record ->
            ExistingStratumModel(
                areaHa = record[STRATA.AREA_HA]!!,
                boundary = record[strataBoundaryField]!! as MultiPolygon,
                boundaryModifiedTime = record[STRATA.BOUNDARY_MODIFIED_TIME]!!,
                errorMargin = record[STRATA.ERROR_MARGIN]!!,
                id = record[STRATA.ID]!!,
                latestObservationCompletedTime = record[latestObservationTimeField],
                latestObservationId = record[latestObservationIdField],
                name = record[STRATA.NAME]!!,
                numPermanentPlots = record[STRATA.NUM_PERMANENT_PLOTS]!!,
                numTemporaryPlots = record[STRATA.NUM_TEMPORARY_PLOTS]!!,
                substrata = substrataField?.let { record[it] } ?: emptyList(),
                stableId = record[STRATA.STABLE_ID]!!,
                studentsT = record[STRATA.STUDENTS_T]!!,
                targetPlantingDensity = record[STRATA.TARGET_PLANTING_DENSITY]!!,
                variance = record[STRATA.VARIANCE]!!,
            )
          }
        }
  }

  private fun stratumHistoriesMultiset(depth: PlantingSiteDepth): Field<List<StratumHistoryModel>> {
    val boundaryField = STRATUM_HISTORIES.BOUNDARY.forMultiset()
    val substrataField =
        if (depth == PlantingSiteDepth.Substratum || depth == PlantingSiteDepth.Plot) {
          substratumHistoriesMultiset(depth)
        } else {
          null
        }

    return DSL.multiset(
            DSL.select(
                    STRATUM_HISTORIES.AREA_HA,
                    STRATUM_HISTORIES.ID,
                    STRATUM_HISTORIES.NAME,
                    STRATUM_HISTORIES.STRATUM_ID,
                    STRATUM_HISTORIES.STABLE_ID,
                    boundaryField,
                    substrataField,
                )
                .from(STRATUM_HISTORIES)
                .where(PLANTING_SITE_HISTORIES.ID.eq(STRATUM_HISTORIES.PLANTING_SITE_HISTORY_ID))
                .orderBy(STRATUM_HISTORIES.NAME)
        )
        .convertFrom { result ->
          result.map { record: Record ->
            StratumHistoryModel(
                record[STRATUM_HISTORIES.AREA_HA]!!,
                record[boundaryField] as MultiPolygon,
                record[STRATUM_HISTORIES.ID]!!,
                record[STRATUM_HISTORIES.NAME]!!,
                substrataField?.let { record[it]!! } ?: emptyList(),
                record[STRATUM_HISTORIES.STRATUM_ID],
                record[STRATUM_HISTORIES.STABLE_ID]!!,
            )
          }
        }
  }

  private fun fetchPermanentPlotId(
      stratumId: StratumId,
      permanentIndex: Int,
  ): MonitoringPlotId? {
    return dslContext
        .select(MONITORING_PLOTS.ID)
        .from(MONITORING_PLOTS)
        .join(SUBSTRATA)
        .on(MONITORING_PLOTS.SUBSTRATUM_ID.eq(SUBSTRATA.ID))
        .where(SUBSTRATA.STRATUM_ID.eq(stratumId))
        .and(MONITORING_PLOTS.PERMANENT_INDEX.eq(permanentIndex))
        .fetchOne(MONITORING_PLOTS.ID)
  }

  /**
   * Returns the index of a permanent plot that hasn't been used in any observations yet. If all
   * existing permanent indexes have already been used, returns a number 1 greater than the current
   * maximum permanent index; the caller will have to create the plot.
   */
  private fun fetchUnusedPermanentIndex(stratumId: StratumId): Int {
    val previouslyUsedField =
        DSL.exists(
                DSL.selectOne()
                    .from(OBSERVATION_PLOTS)
                    .where(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                    .and(OBSERVATION_PLOTS.IS_PERMANENT)
            )
            .asNonNullable()

    val (maxIndex, maxIndexWasPreviouslyUsed) =
        dslContext
            .select(MONITORING_PLOTS.PERMANENT_INDEX.asNonNullable(), previouslyUsedField)
            .from(MONITORING_PLOTS)
            .join(SUBSTRATA)
            .on(MONITORING_PLOTS.SUBSTRATUM_ID.eq(SUBSTRATA.ID))
            .where(SUBSTRATA.STRATUM_ID.eq(stratumId))
            .and(MONITORING_PLOTS.PERMANENT_INDEX.isNotNull)
            .orderBy(MONITORING_PLOTS.PERMANENT_INDEX.desc(), previouslyUsedField.desc())
            .limit(1)
            .fetchOne() ?: throw IllegalStateException("Could not query stratum's permanent plots")

    return if (maxIndexWasPreviouslyUsed) {
      maxIndex + 1
    } else {
      maxIndex
    }
  }

  private fun <ID> speciesCountMultiset(
      scopeIdField: TableField<*, ID?>,
      tableIdField: Field<ID>,
  ): Field<List<PlantingSiteReportedPlantTotals.Species>> {
    val table = scopeIdField.table!!

    val speciesIdField =
        table.field("species_id", SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()))!!
    val plantsSinceLastObservationField =
        table.field("plants_since_last_observation", Int::class.java)!!
    val totalPlantsField = table.field("total_plants", Int::class.java)!!

    return DSL.multiset(
            DSL.select(
                    speciesIdField,
                    plantsSinceLastObservationField,
                    totalPlantsField,
                )
                .from(table)
                .where(tableIdField.eq(scopeIdField))
        )
        .convertFrom { result ->
          result.map { record ->
            PlantingSiteReportedPlantTotals.Species(
                id = record[speciesIdField],
                plantsSinceLastObservation = record[plantsSinceLastObservationField],
                totalPlants = record[totalPlantsField],
            )
          }
        }
  }

  private fun fetchReportedPlants(condition: Condition): List<PlantingSiteReportedPlantTotals> {
    val substratumSpeciesField =
        speciesCountMultiset(
            SUBSTRATUM_POPULATIONS.SUBSTRATUM_ID,
            SUBSTRATA.ID,
        )

    val substrataField =
        DSL.multiset(
                DSL.select(SUBSTRATA.ID, substratumSpeciesField)
                    .from(SUBSTRATA)
                    .where(SUBSTRATA.STRATUM_ID.eq(STRATA.ID))
                    .orderBy(SUBSTRATA.ID)
            )
            .convertFrom { result ->
              result.map { record ->
                val species = record[substratumSpeciesField]
                val plantsSinceLastObservation = species.sumOf { it.plantsSinceLastObservation }
                val totalPlants = species.sumOf { it.totalPlants }
                val totalSpecies = species.size

                PlantingSiteReportedPlantTotals.Substratum(
                    record[SUBSTRATA.ID]!!,
                    plantsSinceLastObservation = plantsSinceLastObservation,
                    species = species,
                    totalPlants = totalPlants,
                    totalSpecies = totalSpecies,
                )
              }
            }

    val stratumSpeciesField = speciesCountMultiset(STRATUM_POPULATIONS.STRATUM_ID, STRATA.ID)
    val strataField =
        DSL.multiset(
                DSL.select(
                        STRATA.ID,
                        STRATA.AREA_HA,
                        STRATA.TARGET_PLANTING_DENSITY,
                        stratumSpeciesField,
                        substrataField,
                    )
                    .from(STRATA)
                    .where(STRATA.PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
                    .orderBy(STRATA.ID)
            )
            .convertFrom { result ->
              result.map { record ->
                val targetPlants =
                    record[STRATA.AREA_HA.asNonNullable()] *
                        record[STRATA.TARGET_PLANTING_DENSITY.asNonNullable()]

                val species = record[stratumSpeciesField]
                val plantsSinceLastObservation = species.sumOf { it.plantsSinceLastObservation }
                val totalPlants = species.sumOf { it.totalPlants }
                val totalSpecies = species.size

                PlantingSiteReportedPlantTotals.Stratum(
                    id = record[STRATA.ID.asNonNullable()],
                    plantsSinceLastObservation = plantsSinceLastObservation,
                    substrata = record[substrataField] ?: emptyList(),
                    species = species,
                    targetPlants = targetPlants.toInt(),
                    totalPlants = totalPlants,
                    totalSpecies = totalSpecies,
                )
              }
            }

    val siteSpeciesField =
        speciesCountMultiset(PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID, PLANTING_SITES.ID)

    return dslContext
        .select(PLANTING_SITES.ID, siteSpeciesField, strataField)
        .from(PLANTING_SITES)
        .where(condition)
        .orderBy(PLANTING_SITES.ID)
        .fetch { record ->
          val species = record[siteSpeciesField]
          PlantingSiteReportedPlantTotals(
              id = record[PLANTING_SITES.ID]!!,
              strata = record[strataField],
              plantsSinceLastObservation = species.sumOf { it.plantsSinceLastObservation },
              species = record[siteSpeciesField],
              totalPlants = species.sumOf { it.totalPlants },
              totalSpecies = species.size,
          )
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
              if (
                  existingSeason.endDate < todayAtSite &&
                      (existingSeason.startDate != desiredSeason.startDate ||
                          existingSeason.endDate != desiredSeason.endDate)
              ) {
                throw CannotUpdatePastPlantingSeasonException(
                    existingSeason.id,
                    existingSeason.endDate,
                )
              }
            }
      }

      desiredPlantingSeasons
          .sortedBy { it.startDate }
          .reduce { previous, next ->
            if (next.startDate <= previous.endDate) {
              throw PlantingSeasonsOverlapException(
                  previous.startDate,
                  previous.endDate,
                  next.startDate,
                  next.endDate,
              )
            }

            next
          }
    }
  }

  private fun startPlantingSeason(
      plantingSiteId: PlantingSiteId,
      plantingSeasonId: PlantingSeasonId,
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
            PLANTING_SEASONS.PLANTING_SITE_ID.asNonNullable(),
            PLANTING_SEASONS.ID.asNonNullable(),
        )
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
      plantingSeasonId: PlantingSeasonId,
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
            PLANTING_SEASONS.PLANTING_SITE_ID.asNonNullable(),
            PLANTING_SEASONS.ID.asNonNullable(),
        )
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
            )
        )
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

  private fun insertStratumHistory(
      model: AnyStratumModel,
      plantingSiteHistoryId: PlantingSiteHistoryId,
      stratumId: StratumId = model.id ?: throw IllegalArgumentException("Stratum missing ID"),
  ): StratumHistoryId {
    val historiesRecord =
        StratumHistoriesRecord(
                areaHa = model.areaHa,
                boundary = model.boundary,
                name = model.name,
                plantingSiteHistoryId = plantingSiteHistoryId,
                stratumId = stratumId,
                stableId = model.stableId,
            )
            .attach(dslContext)

    historiesRecord.insert()

    return historiesRecord.id!!
  }

  private fun insertSubstratumHistory(
      model: AnySubstratumModel,
      stratumHistoryId: StratumHistoryId,
      substratumId: SubstratumId =
          model.id ?: throw IllegalArgumentException("Substratum missing ID"),
  ): SubstratumHistoryId {
    val historiesRecord =
        SubstratumHistoriesRecord(
                areaHa = model.areaHa,
                boundary = model.boundary,
                fullName = model.fullName,
                name = model.name,
                substratumId = substratumId,
                stratumHistoryId = stratumHistoryId,
                stableId = model.stableId,
            )
            .attach(dslContext)

    historiesRecord.insert()

    return historiesRecord.id!!
  }

  private fun insertMonitoringPlotHistory(
      monitoringPlotsRow: MonitoringPlotsRow
  ): MonitoringPlotHistoryId {
    return with(monitoringPlotsRow) {
      insertMonitoringPlotHistory(id!!, plantingSiteId!!, substratumId)
    }
  }

  private fun insertMonitoringPlotHistory(
      monitoringPlotId: MonitoringPlotId,
      plantingSiteId: PlantingSiteId,
      substratumId: SubstratumId?,
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
                  .where(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID.eq(plantingSiteId)),
          )
          .set(PLANTING_SITE_ID, plantingSiteId)
          .set(
              SUBSTRATUM_HISTORY_ID,
              DSL.select(DSL.max(SUBSTRATUM_HISTORIES.ID))
                  .from(SUBSTRATUM_HISTORIES)
                  .where(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID.eq(substratumId)),
          )
          .set(SUBSTRATUM_ID, substratumId)
          .returning(ID)
          .fetchOne(ID)!!
    }
  }
}
