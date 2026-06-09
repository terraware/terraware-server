package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_SPECIES_TARGETS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_NOTIFICATIONS
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.plantingmanagement.ExistingPlantingSeasonModel
import com.terraformation.backend.plantingmanagement.NewPlantingSeasonModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonSpeciesTargetModel
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonDeletedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonUpdatedEventValues
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.event.PlantingSeasonPastEndDateEvent
import com.terraformation.backend.tracking.event.PlantingSeasonRescheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonScheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonStartedEvent
import com.terraformation.backend.util.nullIfEquals
import jakarta.inject.Named
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class PlantingSeasonStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val parentStore: ParentStore,
    private val seasonHelper: SeasonHelper,
) {
  private val log = perClassLogger()

  fun create(newModel: NewPlantingSeasonModel): PlantingSeasonId {
    requirePermissions { createPlantingSeason(newModel.plantingSiteId) }

    val organizationId =
        parentStore.getOrganizationId(newModel.plantingSiteId)
            ?: throw PlantingSiteNotFoundException(newModel.plantingSiteId)
    val userId = currentUser().userId
    val now = clock.instant()
    val status = calculateStatus(newModel.startDate, newModel.endDate, newModel.plantingSiteId)

    return with(PLANTING_SEASONS) {
      val newSeasonId =
          dslContext
              .insertInto(PLANTING_SEASONS)
              .set(NAME, newModel.name)
              .set(PLANTING_SITE_ID, newModel.plantingSiteId)
              .set(START_DATE, newModel.startDate)
              .set(END_DATE, newModel.endDate)
              .set(STATUS_ID, status)
              .set(CREATED_BY, userId)
              .set(CREATED_TIME, now)
              .set(MODIFIED_BY, userId)
              .set(MODIFIED_TIME, now)
              .onConflictDoNothing()
              .returning(ID)
              .fetchOne(ID)
              ?: throw PlantingSeasonExistsException(newModel.plantingSiteId, newModel.name)

      eventPublisher.publishEvent(
          PlantingSeasonScheduledEvent(
              plantingSeasonId = newSeasonId,
              plantingSiteId = newModel.plantingSiteId,
              startDate = newModel.startDate,
              endDate = newModel.endDate,
          )
      )

      eventPublisher.publishEvent(
          PlantingSeasonCreatedEvent(
              endDate = newModel.endDate,
              name = newModel.name,
              organizationId = organizationId,
              plantingSeasonId = newSeasonId,
              plantingSiteId = newModel.plantingSiteId,
              startDate = newModel.startDate,
              status = status,
          )
      )

      newSeasonId
    }
  }

  fun fetchById(id: PlantingSeasonId): ExistingPlantingSeasonModel {
    requirePermissions { readPlantingSeason(id) }

    return fetchByCondition(PLANTING_SEASONS.ID.eq(id)).firstOrNull()
        ?: throw PlantingSeasonNotFoundException(id)
  }

  fun fetchList(plantingSiteId: PlantingSiteId): List<ExistingPlantingSeasonModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return fetchByCondition(PLANTING_SEASONS.PLANTING_SITE_ID.eq(plantingSiteId))
  }

  fun fetchList(organizationId: OrganizationId): List<ExistingPlantingSeasonModel> {
    requirePermissions { readOrganization(organizationId) }

    return fetchByCondition(
        PLANTING_SEASONS.PLANTING_SITE_ID.`in`(
            DSL.select(PLANTING_SITES.ID)
                .from(PLANTING_SITES)
                .where(PLANTING_SITES.ORGANIZATION_ID.eq(organizationId))
        )
    )
  }

  fun update(
      plantingSeasonId: PlantingSeasonId,
      name: String,
      startDate: LocalDate,
      endDate: LocalDate,
  ) {
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    seasonHelper.withLockedPlantingSeason(plantingSeasonId) {
      val existingSeason =
          fetchByCondition(PLANTING_SEASONS.ID.eq(plantingSeasonId)).firstOrNull()
              ?: throw PlantingSeasonNotFoundException(plantingSeasonId)
      val organizationId =
          parentStore.getOrganizationId(plantingSeasonId)
              ?: throw PlantingSeasonNotFoundException(plantingSeasonId)

      if (existingSeason.status == PlantingSeasonStatus.Closed) {
        throw PlantingSeasonClosedException(plantingSeasonId)
      }

      val now = clock.instant()
      val status =
          if (existingSeason.startDate == startDate && existingSeason.endDate == endDate)
              existingSeason.status
          else calculateStatus(startDate, endDate, existingSeason.plantingSiteId)

      val rowsUpdated =
          with(PLANTING_SEASONS) {
            dslContext
                .update(PLANTING_SEASONS)
                .set(NAME, name)
                .set(START_DATE, startDate)
                .set(END_DATE, endDate)
                .set(STATUS_ID, status)
                .set(MODIFIED_BY, currentUser().userId)
                .set(MODIFIED_TIME, now)
                .where(ID.eq(plantingSeasonId))
                .execute()
          }

      if (rowsUpdated == 0) {
        throw PlantingSeasonNotFoundException(plantingSeasonId)
      }

      if (existingSeason.startDate != startDate || existingSeason.endDate != endDate) {
        eventPublisher.publishEvent(
            PlantingSeasonRescheduledEvent(
                plantingSeasonId = existingSeason.id,
                plantingSiteId = existingSeason.plantingSiteId,
                oldStartDate = existingSeason.startDate,
                oldEndDate = existingSeason.endDate,
                newStartDate = startDate,
                newEndDate = endDate,
            )
        )
      }

      if (
          existingSeason.endDate != endDate ||
              existingSeason.name != name ||
              existingSeason.startDate != startDate ||
              existingSeason.status != status
      ) {
        eventPublisher.publishEvent(
            PlantingSeasonUpdatedEvent(
                changedFrom =
                    PlantingSeasonUpdatedEventValues(
                        endDate = existingSeason.endDate.nullIfEquals(endDate),
                        name = existingSeason.name.nullIfEquals(name),
                        startDate = existingSeason.startDate.nullIfEquals(startDate),
                        status = existingSeason.status.nullIfEquals(status),
                    ),
                changedTo =
                    PlantingSeasonUpdatedEventValues(
                        endDate = endDate.nullIfEquals(existingSeason.endDate),
                        name = name.nullIfEquals(existingSeason.name),
                        startDate = startDate.nullIfEquals(existingSeason.startDate),
                        status = status.nullIfEquals(existingSeason.status),
                    ),
                organizationId = organizationId,
                plantingSeasonId = plantingSeasonId,
                plantingSiteId = existingSeason.plantingSiteId,
            )
        )
      }
    }
  }

  fun close(plantingSeasonId: PlantingSeasonId) {
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    seasonHelper.withLockedPlantingSeason(plantingSeasonId) {
      val existingSeason =
          fetchByCondition(PLANTING_SEASONS.ID.eq(plantingSeasonId)).firstOrNull()
              ?: throw PlantingSeasonNotFoundException(plantingSeasonId)
      val organizationId =
          parentStore.getOrganizationId(plantingSeasonId)
              ?: throw PlantingSeasonNotFoundException(plantingSeasonId)

      with(PLANTING_SEASONS) {
        dslContext
            .update(PLANTING_SEASONS)
            .set(STATUS_ID, PlantingSeasonStatus.Closed)
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, clock.instant())
            .where(ID.eq(plantingSeasonId))
            .execute()
      }

      if (existingSeason.status != PlantingSeasonStatus.Closed) {
        eventPublisher.publishEvent(
            PlantingSeasonUpdatedEvent(
                changedFrom = PlantingSeasonUpdatedEventValues(status = existingSeason.status),
                changedTo = PlantingSeasonUpdatedEventValues(status = PlantingSeasonStatus.Closed),
                organizationId = organizationId,
                plantingSeasonId = plantingSeasonId,
                plantingSiteId = existingSeason.plantingSiteId,
            )
        )
      }
    }
  }

  fun delete(id: PlantingSeasonId) {
    requirePermissions { deletePlantingSeason(id) }

    val plantingSiteId =
        parentStore.getPlantingSiteId(id) ?: throw PlantingSeasonNotFoundException(id)
    val organizationId =
        parentStore.getOrganizationId(id) ?: throw PlantingSeasonNotFoundException(id)

    val rowsDeleted =
        dslContext.deleteFrom(PLANTING_SEASONS).where(PLANTING_SEASONS.ID.eq(id)).execute()

    if (rowsDeleted == 0) {
      throw PlantingSeasonNotFoundException(id)
    }

    eventPublisher.publishEvent(
        PlantingSeasonDeletedEvent(
            organizationId = organizationId,
            plantingSeasonId = id,
            plantingSiteId = plantingSiteId,
        )
    )
  }

  private fun calculateStatus(
      startDate: LocalDate,
      endDate: LocalDate,
      plantingSiteId: PlantingSiteId,
  ): PlantingSeasonStatus {
    val today =
        clock.instant().atZone(parentStore.getEffectiveTimeZone(plantingSiteId)).toLocalDate()
    return when {
      today < startDate -> PlantingSeasonStatus.Upcoming
      today <= endDate -> PlantingSeasonStatus.Active
      else -> PlantingSeasonStatus.PastEndDate
    }
  }

  private fun fetchByCondition(condition: Condition): List<ExistingPlantingSeasonModel> {
    val targetsMultiset =
        DSL.multiset(
                DSL.select(
                        PLANTING_SEASON_SPECIES_TARGETS.SUBSTRATUM_ID,
                        PLANTING_SEASON_SPECIES_TARGETS.SPECIES_ID,
                        PLANTING_SEASON_SPECIES_TARGETS.QUANTITY,
                    )
                    .from(PLANTING_SEASON_SPECIES_TARGETS)
                    .where(
                        PLANTING_SEASON_SPECIES_TARGETS.PLANTING_SEASON_ID.eq(PLANTING_SEASONS.ID)
                    )
            )
            .convertFrom { result ->
              result.map { record ->
                PlantingSeasonSpeciesTargetModel(
                    substratumId = record[PLANTING_SEASON_SPECIES_TARGETS.SUBSTRATUM_ID]!!,
                    speciesId = record[PLANTING_SEASON_SPECIES_TARGETS.SPECIES_ID]!!,
                    quantity = record[PLANTING_SEASON_SPECIES_TARGETS.QUANTITY]!!,
                )
              }
            }

    return with(PLANTING_SEASONS) {
      dslContext
          .select(PLANTING_SEASONS.asterisk(), targetsMultiset)
          .from(PLANTING_SEASONS)
          .where(condition)
          .fetch { record ->
            ExistingPlantingSeasonModel(
                endDate = record[END_DATE]!!,
                id = record[ID]!!,
                name = record[NAME]!!,
                plantingSiteId = record[PLANTING_SITE_ID]!!,
                speciesTargets = record[targetsMultiset],
                startDate = record[START_DATE]!!,
                status = record[STATUS_ID]!!,
            )
          }
    }
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
                    DSL.select(DSL.max(PLANTING_SEASONS.END_DATE))
                        .from(PLANTING_SEASONS)
                        .where(PLANTING_SITES.ID.eq(PLANTING_SEASONS.PLANTING_SITE_ID))
                )
                .lt(localDateInSiteTimezone(maxEndTime))
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
    markPlantingSeasonsPastEndDate()
    markPlantingSeasonsActive()
  }

  private fun localDateInSiteTimezone(instant: Instant = clock.instant()) =
      DSL.field(
          "({0} AT TIME ZONE COALESCE({1}, {2}, 'UTC'))::date",
          LocalDate::class.java,
          DSL.`val`(instant),
          PLANTING_SITES.TIME_ZONE,
          PLANTING_SITES.organizations.TIME_ZONE,
      )

  private fun markPlantingSeasonActive(
      plantingSiteId: PlantingSiteId,
      plantingSeasonId: PlantingSeasonId,
  ) {
    dslContext.transaction { _ ->
      val rowsUpdated =
          dslContext
              .update(PLANTING_SEASONS)
              .set(PLANTING_SEASONS.STATUS_ID, PlantingSeasonStatus.Active)
              .where(PLANTING_SEASONS.ID.eq(plantingSeasonId))
              .and(PLANTING_SEASONS.PLANTING_SITE_ID.eq(plantingSiteId))
              .and(PLANTING_SEASONS.STATUS_ID.eq(PlantingSeasonStatus.Upcoming))
              .execute()

      if (rowsUpdated > 0) {
        log.info("Planting season $plantingSeasonId at site $plantingSiteId has started")

        eventPublisher.publishEvent(PlantingSeasonStartedEvent(plantingSiteId, plantingSeasonId))
      }
    }
  }

  private fun markPlantingSeasonsActive() {
    dslContext
        .select(
            PLANTING_SEASONS.PLANTING_SITE_ID.asNonNullable(),
            PLANTING_SEASONS.ID.asNonNullable(),
        )
        .from(PLANTING_SEASONS)
        .join(PLANTING_SITES)
        .on(PLANTING_SEASONS.PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
        .where(PLANTING_SEASONS.START_DATE.le(localDateInSiteTimezone()))
        .and(PLANTING_SEASONS.END_DATE.gt(localDateInSiteTimezone()))
        .and(PLANTING_SEASONS.STATUS_ID.eq(PlantingSeasonStatus.Upcoming))
        .fetch()
        .forEach { (plantingSiteId, plantingSeasonId) ->
          markPlantingSeasonActive(plantingSiteId, plantingSeasonId)
        }
  }

  private fun markPlantingSeasonPastEndDate(
      plantingSiteId: PlantingSiteId,
      plantingSeasonId: PlantingSeasonId,
  ) {
    dslContext.transaction { _ ->
      val rowsUpdated =
          dslContext
              .update(PLANTING_SEASONS)
              .set(PLANTING_SEASONS.STATUS_ID, PlantingSeasonStatus.PastEndDate)
              .where(PLANTING_SEASONS.ID.eq(plantingSeasonId))
              .and(PLANTING_SEASONS.PLANTING_SITE_ID.eq(plantingSiteId))
              .and(PLANTING_SEASONS.STATUS_ID.eq(PlantingSeasonStatus.Active))
              .execute()

      if (rowsUpdated > 0) {
        log.info(
            "Planting season $plantingSeasonId at site $plantingSiteId is now past the end date"
        )

        deleteRecurringPlantingSeasonNotifications(plantingSiteId)
        eventPublisher.publishEvent(
            PlantingSeasonPastEndDateEvent(plantingSiteId, plantingSeasonId)
        )
      }
    }
  }

  private fun markPlantingSeasonsPastEndDate() {
    dslContext
        .select(
            PLANTING_SEASONS.PLANTING_SITE_ID.asNonNullable(),
            PLANTING_SEASONS.ID.asNonNullable(),
        )
        .from(PLANTING_SEASONS)
        .join(PLANTING_SITES)
        .on(PLANTING_SEASONS.PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
        .where(PLANTING_SEASONS.END_DATE.lt(localDateInSiteTimezone()))
        .and(PLANTING_SEASONS.STATUS_ID.eq(PlantingSeasonStatus.Active))
        .fetch()
        .forEach { (plantingSiteId, plantingSeasonId) ->
          markPlantingSeasonPastEndDate(plantingSiteId, plantingSeasonId)
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
}
