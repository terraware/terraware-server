package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATES
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATE_SPECIES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.plantingmanagement.ExistingPlantingSeasonScheduledDateModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonScheduledDateModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonScheduledDateSpecies
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonScheduledDateCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonScheduledDateDeletedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonScheduledDateSpeciesCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonScheduledDateSpeciesDeletedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonScheduledDateSpeciesUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonScheduledDateSpeciesUpdatedEventValues
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonScheduledDateUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonScheduledDateUpdatedEventValues
import com.terraformation.backend.tracking.db.SubstratumNotFoundException
import com.terraformation.backend.util.nullIfEquals
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class PlantingSeasonScheduledDatesStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val parentStore: ParentStore,
) {
  fun fetchList(
      plantingSeasonId: PlantingSeasonId
  ): List<ExistingPlantingSeasonScheduledDateModel> {
    requirePermissions { readPlantingSeason(plantingSeasonId) }

    return fetchByCondition(SCHEDULED_PLANTING_DATES.PLANTING_SEASON_ID.eq(plantingSeasonId))
  }

  fun fetch(
      plantingSeasonId: PlantingSeasonId,
      scheduledPlantingDateId: ScheduledPlantingDateId,
  ): ExistingPlantingSeasonScheduledDateModel {
    requirePermissions { readPlantingSeason(plantingSeasonId) }

    return fetchByCondition(
            SCHEDULED_PLANTING_DATES.ID.eq(scheduledPlantingDateId)
                .and(SCHEDULED_PLANTING_DATES.PLANTING_SEASON_ID.eq(plantingSeasonId))
        )
        .firstOrNull()
        ?: throw PlantingSeasonScheduledDateNotFoundException(scheduledPlantingDateId)
  }

  fun create(model: PlantingSeasonScheduledDateModel): ScheduledPlantingDateId {
    requirePermissions { updatePlantingSeason(model.plantingSeasonId) }

    validateSeasonNotClosed(model.plantingSeasonId)

    val plantingSiteId =
        parentStore.getPlantingSiteId(model.plantingSeasonId)
            ?: throw PlantingSeasonNotFoundException(model.plantingSeasonId)
    val organizationId =
        parentStore.getOrganizationId(plantingSiteId)
            ?: throw PlantingSeasonNotFoundException(model.plantingSeasonId)
    val userId = currentUser().userId
    val now = clock.instant()

    val scheduledDateId = dslContext.transactionResult { _ ->
      val newScheduledDateId =
          with(SCHEDULED_PLANTING_DATES) {
            dslContext
                .insertInto(SCHEDULED_PLANTING_DATES)
                .set(PLANTING_SEASON_ID, model.plantingSeasonId)
                .set(DATE, model.date)
                .set(CREATED_BY, userId)
                .set(CREATED_TIME, now)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .onConflictDoNothing()
                .returning(ID)
                .fetchOne(ID)
                ?: throw PlantingSeasonScheduledDateExistsException(
                    model.plantingSeasonId,
                    model.date,
                )
          }

      with(SCHEDULED_PLANTING_DATE_SPECIES) {
        val insertQuery =
            dslContext.insertInto(
                SCHEDULED_PLANTING_DATE_SPECIES,
                SCHEDULED_PLANTING_DATE_ID,
                SPECIES_ID,
                SUBSTRATUM_ID,
                QUANTITY,
            )

        model.species.forEach { species ->
          insertQuery.values(
              newScheduledDateId,
              species.speciesId,
              species.substratumId,
              species.quantity,
          )
        }

        insertQuery.execute()
      }

      val substratumInfo = fetchSubstratumInfo(model.species.map { it.substratumId }.toSet())

      eventPublisher.publishEvent(
          PlantingSeasonScheduledDateCreatedEvent(
              date = model.date,
              organizationId = organizationId,
              plantingSeasonId = model.plantingSeasonId,
              plantingSiteId = plantingSiteId,
              scheduledPlantingDateId = newScheduledDateId,
          )
      )

      model.species.forEach { species ->
        val info =
            substratumInfo[species.substratumId]
                ?: throw IllegalStateException("Substratum ${species.substratumId} not found")
        eventPublisher.publishEvent(
            PlantingSeasonScheduledDateSpeciesCreatedEvent(
                organizationId = organizationId,
                plantingSeasonId = model.plantingSeasonId,
                plantingSiteId = plantingSiteId,
                quantity = species.quantity,
                scheduledPlantingDateId = newScheduledDateId,
                speciesId = species.speciesId,
                stratumName = info.stratumName,
                substratumHistoryId = info.substratumHistoryId,
                substratumId = species.substratumId,
                substratumName = info.substratumName,
            )
        )
      }

      newScheduledDateId
    }

    return scheduledDateId
  }

  fun update(
      scheduledDateId: ScheduledPlantingDateId,
      model: PlantingSeasonScheduledDateModel,
  ) {
    requirePermissions { updatePlantingSeason(model.plantingSeasonId) }

    validateSeasonNotClosed(model.plantingSeasonId)

    if (model.species.size != model.species.distinctBy { it.substratumId to it.speciesId }.size) {
      throw IllegalArgumentException("Species listed multiple times for substratum")
    }

    withLockedDate(scheduledDateId) {
      val oldModel = fetch(model.plantingSeasonId, scheduledDateId)
      val plantingSiteId =
          parentStore.getPlantingSiteId(model.plantingSeasonId)
              ?: throw PlantingSeasonNotFoundException(model.plantingSeasonId)
      val organizationId =
          parentStore.getOrganizationId(plantingSiteId)
              ?: throw PlantingSeasonNotFoundException(model.plantingSeasonId)

      val updatedCount =
          with(SCHEDULED_PLANTING_DATES) {
            dslContext
                .update(SCHEDULED_PLANTING_DATES)
                .set(DATE, model.date)
                .set(MODIFIED_BY, currentUser().userId)
                .set(MODIFIED_TIME, clock.instant())
                .where(ID.eq(scheduledDateId))
                .and(PLANTING_SEASON_ID.eq(model.plantingSeasonId))
                .execute()
          }

      if (updatedCount == 0) {
        throw PlantingSeasonScheduledDateNotFoundException(scheduledDateId)
      }

      if (oldModel.date != model.date) {
        eventPublisher.publishEvent(
            PlantingSeasonScheduledDateUpdatedEvent(
                changedFrom =
                    PlantingSeasonScheduledDateUpdatedEventValues(
                        date = oldModel.date.nullIfEquals(model.date),
                    ),
                changedTo =
                    PlantingSeasonScheduledDateUpdatedEventValues(
                        date = model.date.nullIfEquals(oldModel.date),
                    ),
                organizationId = organizationId,
                plantingSeasonId = model.plantingSeasonId,
                plantingSiteId = plantingSiteId,
                scheduledPlantingDateId = scheduledDateId,
            )
        )
      }

      with(SCHEDULED_PLANTING_DATE_SPECIES) {
        // We want to diff the old and new species lists using (substratum, species) keys so we can
        // tell which species have been added, removed, or updated in which substrata.
        val oldByIds = oldModel.species.associateBy { it.substratumId to it.speciesId }
        val newByIds = model.species.associateBy { it.substratumId to it.speciesId }

        val removedKeys = oldByIds.keys - newByIds.keys
        val addedKeys = newByIds.keys - oldByIds.keys
        val commonKeys = oldByIds.keys intersect newByIds.keys
        val touchedSubstrata = (removedKeys + addedKeys + commonKeys).map { it.first }.toSet()
        val substrataInfo = fetchSubstratumInfo(touchedSubstrata)

        removedKeys.forEach { (substratumId, speciesId) ->
          val substratumInfo = substrataInfo.getValue(substratumId)

          dslContext
              .deleteFrom(SCHEDULED_PLANTING_DATE_SPECIES)
              .where(SCHEDULED_PLANTING_DATE_ID.eq(scheduledDateId))
              .and(SUBSTRATUM_ID.eq(substratumId))
              .and(SPECIES_ID.eq(speciesId))
              .execute()

          eventPublisher.publishEvent(
              PlantingSeasonScheduledDateSpeciesDeletedEvent(
                  organizationId = organizationId,
                  plantingSeasonId = model.plantingSeasonId,
                  plantingSiteId = plantingSiteId,
                  scheduledPlantingDateId = scheduledDateId,
                  speciesId = speciesId,
                  stratumName = substratumInfo.stratumName,
                  substratumHistoryId = substratumInfo.substratumHistoryId,
                  substratumId = substratumId,
                  substratumName = substratumInfo.substratumName,
              )
          )
        }

        addedKeys.forEach { key ->
          val species = newByIds.getValue(key)
          val substratumInfo = substrataInfo.getValue(species.substratumId)

          dslContext
              .insertInto(SCHEDULED_PLANTING_DATE_SPECIES)
              .set(SCHEDULED_PLANTING_DATE_ID, scheduledDateId)
              .set(SUBSTRATUM_ID, species.substratumId)
              .set(SPECIES_ID, species.speciesId)
              .set(QUANTITY, species.quantity)
              .execute()

          eventPublisher.publishEvent(
              PlantingSeasonScheduledDateSpeciesCreatedEvent(
                  organizationId = organizationId,
                  plantingSeasonId = model.plantingSeasonId,
                  plantingSiteId = plantingSiteId,
                  quantity = species.quantity,
                  scheduledPlantingDateId = scheduledDateId,
                  speciesId = species.speciesId,
                  stratumName = substratumInfo.stratumName,
                  substratumHistoryId = substratumInfo.substratumHistoryId,
                  substratumId = species.substratumId,
                  substratumName = substratumInfo.substratumName,
              )
          )
        }

        commonKeys.forEach { key ->
          val oldSpecies = oldByIds.getValue(key)
          val newSpecies = newByIds.getValue(key)
          val substratumInfo = substrataInfo.getValue(newSpecies.substratumId)

          if (oldSpecies.quantity != newSpecies.quantity) {
            dslContext
                .update(SCHEDULED_PLANTING_DATE_SPECIES)
                .set(QUANTITY, newSpecies.quantity)
                .where(SCHEDULED_PLANTING_DATE_ID.eq(scheduledDateId))
                .and(SUBSTRATUM_ID.eq(newSpecies.substratumId))
                .and(SPECIES_ID.eq(newSpecies.speciesId))
                .execute()

            eventPublisher.publishEvent(
                PlantingSeasonScheduledDateSpeciesUpdatedEvent(
                    changedFrom =
                        PlantingSeasonScheduledDateSpeciesUpdatedEventValues(
                            quantity = oldSpecies.quantity,
                        ),
                    changedTo =
                        PlantingSeasonScheduledDateSpeciesUpdatedEventValues(
                            quantity = newSpecies.quantity,
                        ),
                    organizationId = organizationId,
                    plantingSeasonId = model.plantingSeasonId,
                    plantingSiteId = plantingSiteId,
                    scheduledPlantingDateId = scheduledDateId,
                    speciesId = newSpecies.speciesId,
                    stratumName = substratumInfo.stratumName,
                    substratumHistoryId = substratumInfo.substratumHistoryId,
                    substratumId = newSpecies.substratumId,
                    substratumName = substratumInfo.substratumName,
                )
            )
          }
        }
      }
    }
  }

  fun delete(
      plantingSeasonId: PlantingSeasonId,
      scheduledDateId: ScheduledPlantingDateId,
  ) {
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    validateSeasonNotClosed(plantingSeasonId)

    val plantingSiteId =
        parentStore.getPlantingSiteId(plantingSeasonId)
            ?: throw PlantingSeasonNotFoundException(plantingSeasonId)
    val organizationId =
        parentStore.getOrganizationId(plantingSiteId)
            ?: throw PlantingSeasonNotFoundException(plantingSeasonId)

    with(SCHEDULED_PLANTING_DATES) {
      val rowsDeleted =
          dslContext
              .deleteFrom(SCHEDULED_PLANTING_DATES)
              .where(ID.eq(scheduledDateId))
              .and(PLANTING_SEASON_ID.eq(plantingSeasonId))
              .execute()

      if (rowsDeleted == 0) {
        throw PlantingSeasonScheduledDateNotFoundException(scheduledDateId)
      }
    }

    eventPublisher.publishEvent(
        PlantingSeasonScheduledDateDeletedEvent(
            organizationId = organizationId,
            plantingSeasonId = plantingSeasonId,
            plantingSiteId = plantingSiteId,
            scheduledPlantingDateId = scheduledDateId,
        )
    )
  }

  private fun fetchByCondition(
      condition: Condition
  ): List<ExistingPlantingSeasonScheduledDateModel> {
    val speciesMultiset =
        with(SCHEDULED_PLANTING_DATE_SPECIES) {
          DSL.multiset(
                  DSL.select(SPECIES_ID, SUBSTRATUM_ID, QUANTITY)
                      .from(SCHEDULED_PLANTING_DATE_SPECIES)
                      .where(SCHEDULED_PLANTING_DATE_ID.eq(SCHEDULED_PLANTING_DATES.ID))
                      .orderBy(SPECIES_ID, SUBSTRATUM_ID)
              )
              .convertFrom { result ->
                result.map { record ->
                  PlantingSeasonScheduledDateSpecies(
                      speciesId = record[SPECIES_ID]!!,
                      substratumId = record[SUBSTRATUM_ID]!!,
                      quantity = record[QUANTITY]!!,
                  )
                }
              }
        }

    return with(SCHEDULED_PLANTING_DATES) {
      dslContext
          .select(SCHEDULED_PLANTING_DATES.asterisk(), speciesMultiset)
          .from(SCHEDULED_PLANTING_DATES)
          .where(condition)
          .orderBy(DATE.desc())
          .fetch { record ->
            ExistingPlantingSeasonScheduledDateModel(
                date = record[DATE]!!,
                plantingSeasonId = record[PLANTING_SEASON_ID]!!,
                scheduledPlantingDateId = record[ID]!!,
                species = record[speciesMultiset],
            )
          }
    }
  }

  private fun validateSeasonNotClosed(plantingSeasonId: PlantingSeasonId) {
    with(PLANTING_SEASONS) {
      val status =
          dslContext
              .select(STATUS_ID)
              .from(PLANTING_SEASONS)
              .where(ID.eq(plantingSeasonId))
              .fetchOne(STATUS_ID) ?: throw PlantingSeasonNotFoundException(plantingSeasonId)

      if (status == PlantingSeasonStatus.Closed) {
        throw PlantingSeasonClosedException(plantingSeasonId)
      }
    }
  }

  private data class SubstratumInfo(
      val stratumName: String,
      val substratumName: String,
      val substratumHistoryId: SubstratumHistoryId,
  )

  private fun fetchSubstratumInfo(
      substratumIds: Collection<SubstratumId>
  ): Map<SubstratumId, SubstratumInfo> {
    if (substratumIds.isEmpty()) return emptyMap()

    val latestHistoryIdField =
        DSL.field(
            DSL.select(DSL.max(SUBSTRATUM_HISTORIES.ID))
                .from(SUBSTRATUM_HISTORIES)
                .where(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID.eq(SUBSTRATA.ID))
        )

    val substrata =
        dslContext
            .select(SUBSTRATA.ID, SUBSTRATA.NAME, STRATA.NAME, latestHistoryIdField)
            .from(SUBSTRATA)
            .join(STRATA)
            .on(SUBSTRATA.STRATUM_ID.eq(STRATA.ID))
            .where(SUBSTRATA.ID.`in`(substratumIds))
            .fetchMap(SUBSTRATA.ID.asNonNullable()) { record ->
              SubstratumInfo(
                  stratumName = record.value3()!!,
                  substratumName = record[SUBSTRATA.NAME]!!,
                  substratumHistoryId = record.value4()!!,
              )
            }

    substratumIds.forEach { id ->
      if (id !in substrata) {
        throw SubstratumNotFoundException(id)
      }
    }

    return substrata
  }

  private fun <T> withLockedDate(
      scheduledDateId: ScheduledPlantingDateId,
      func: () -> T,
  ): T {
    return dslContext.transactionResult { _ ->
      dslContext
          .selectOne()
          .from(SCHEDULED_PLANTING_DATES)
          .where(SCHEDULED_PLANTING_DATES.ID.eq(scheduledDateId))
          .forUpdate()
          .fetchOne() ?: throw PlantingSeasonScheduledDateNotFoundException(scheduledDateId)
      func()
    }
  }
}
