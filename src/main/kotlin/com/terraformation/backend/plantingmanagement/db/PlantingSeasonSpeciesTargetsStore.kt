package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_SPECIES_TARGETS
import com.terraformation.backend.plantingmanagement.PlantingSeasonSpeciesTargetModel
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetDeletedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetUpdatedEventValues
import com.terraformation.backend.plantingmanagement.event.RateLimitedPlantingSeasonSpeciesChangedEvent
import com.terraformation.backend.plantingmanagement.event.RateLimitedPlantingSeasonTargetQuantitiesChangedEvent
import com.terraformation.backend.ratelimit.RateLimitedEventPublisher
import com.terraformation.backend.tracking.db.SubstratumNotFoundException
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class PlantingSeasonSpeciesTargetsStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val rateLimitedEventPublisher: RateLimitedEventPublisher,
    private val seasonHelper: SeasonHelper,
) {
  fun fetchList(plantingSeasonId: PlantingSeasonId): List<PlantingSeasonSpeciesTargetModel> {
    requirePermissions { readPlantingSeason(plantingSeasonId) }

    return fetchByCondition(PLANTING_SEASON_SPECIES_TARGETS.PLANTING_SEASON_ID.eq(plantingSeasonId))
  }

  fun upsert(
      plantingSeasonId: PlantingSeasonId,
      substratumId: SubstratumId,
      speciesId: SpeciesId,
      quantity: Int,
  ) {
    require(quantity >= 0) { "Quantity must be >= 0" }
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    seasonHelper.validateSeasonNotClosed(plantingSeasonId)

    val userId = currentUser().userId
    val now = clock.instant()

    val (plantingSiteId, organizationId) =
        seasonHelper.fetchPlantingSiteAndOrganization(plantingSeasonId)
    val substratumInfo = seasonHelper.fetchSubstratumInfo(substratumId)

    seasonHelper.withLockedPlantingSeason(plantingSeasonId) {
      with(PLANTING_SEASON_SPECIES_TARGETS) {
        val oldQuantity =
            dslContext
                .select(QUANTITY)
                .from(PLANTING_SEASON_SPECIES_TARGETS)
                .where(PLANTING_SEASON_ID.eq(plantingSeasonId))
                .and(SUBSTRATUM_ID.eq(substratumId))
                .and(SPECIES_ID.eq(speciesId))
                .fetchOne(QUANTITY)
        val isNewSpecies = oldQuantity == null && !speciesHasTargets(plantingSeasonId, speciesId)

        dslContext
            .insertInto(PLANTING_SEASON_SPECIES_TARGETS)
            .set(PLANTING_SEASON_ID, plantingSeasonId)
            .set(SUBSTRATUM_ID, substratumId)
            .set(SPECIES_ID, speciesId)
            .set(QUANTITY, quantity)
            .set(CREATED_BY, userId)
            .set(CREATED_TIME, now)
            .set(MODIFIED_BY, userId)
            .set(MODIFIED_TIME, now)
            .onConflict(PLANTING_SEASON_ID, SUBSTRATUM_ID, SPECIES_ID)
            .doUpdate()
            .set(QUANTITY, quantity)
            .set(MODIFIED_BY, userId)
            .set(MODIFIED_TIME, now)
            .execute()

        if (!isNewSpecies && (oldQuantity ?: 0) != quantity) {
          rateLimitedEventPublisher.publishEvent(
              RateLimitedPlantingSeasonTargetQuantitiesChangedEvent(
                  changes =
                      listOf(
                          RateLimitedPlantingSeasonTargetQuantitiesChangedEvent.Change(
                              newQuantity = quantity,
                              oldQuantity = oldQuantity ?: 0,
                              speciesId = speciesId,
                              stratumName = substratumInfo.stratumName,
                              substratumId = substratumId,
                              substratumName = substratumInfo.substratumName,
                          )
                      ),
                  organizationId = organizationId,
                  plantingSeasonId = plantingSeasonId,
                  plantingSiteId = plantingSiteId,
              )
          )
        }

        if (isNewSpecies) {
          rateLimitedEventPublisher.publishEvent(
              RateLimitedPlantingSeasonSpeciesChangedEvent(
                  addedSpeciesIds = setOf(speciesId),
                  organizationId = organizationId,
                  plantingSeasonId = plantingSeasonId,
              )
          )
        }

        if (oldQuantity == null) {
          eventPublisher.publishEvent(
              PlantingSeasonSpeciesTargetCreatedEvent(
                  organizationId = organizationId,
                  plantingSeasonId = plantingSeasonId,
                  plantingSiteId = plantingSiteId,
                  quantity = quantity,
                  speciesId = speciesId,
                  stratumName = substratumInfo.stratumName,
                  substratumHistoryId = substratumInfo.substratumHistoryId,
                  substratumId = substratumId,
                  substratumName = substratumInfo.substratumName,
              )
          )
        } else if (oldQuantity != quantity) {
          eventPublisher.publishEvent(
              PlantingSeasonSpeciesTargetUpdatedEvent(
                  changedFrom =
                      PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = oldQuantity),
                  changedTo = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = quantity),
                  organizationId = organizationId,
                  plantingSeasonId = plantingSeasonId,
                  plantingSiteId = plantingSiteId,
                  speciesId = speciesId,
                  stratumName = substratumInfo.stratumName,
                  substratumHistoryId = substratumInfo.substratumHistoryId,
                  substratumId = substratumId,
                  substratumName = substratumInfo.substratumName,
              )
          )
        }
      }
    }
  }

  /**
   * Inserts the initial species targets for a newly-created planting season. Unlike [upsert], this
   * isn't treated as the user editing the season's targets.
   */
  fun createInitialTargets(
      plantingSeasonId: PlantingSeasonId,
      targets: Collection<PlantingSeasonSpeciesTargetModel>,
  ) {
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    val userId = currentUser().userId
    val now = clock.instant()

    val (plantingSiteId, organizationId) =
        seasonHelper.fetchPlantingSiteAndOrganization(plantingSeasonId)
    val substrataInfo = seasonHelper.fetchSubstrataInfo(targets.map { it.substratumId })

    targets.forEach { target ->
      if (target.substratumId !in substrataInfo) {
        throw SubstratumNotFoundException(target.substratumId)
      }
    }

    seasonHelper.withLockedPlantingSeason(plantingSeasonId) {
      with(PLANTING_SEASON_SPECIES_TARGETS) {
        targets.forEach { target ->
          dslContext
              .insertInto(PLANTING_SEASON_SPECIES_TARGETS)
              .set(CREATED_BY, userId)
              .set(CREATED_TIME, now)
              .set(MODIFIED_BY, userId)
              .set(MODIFIED_TIME, now)
              .set(PLANTING_SEASON_ID, plantingSeasonId)
              .set(QUANTITY, target.quantity)
              .set(SPECIES_ID, target.speciesId)
              .set(SUBSTRATUM_ID, target.substratumId)
              .execute()

          val substratumInfo = substrataInfo[target.substratumId]!!

          eventPublisher.publishEvent(
              PlantingSeasonSpeciesTargetCreatedEvent(
                  organizationId = organizationId,
                  plantingSeasonId = plantingSeasonId,
                  plantingSiteId = plantingSiteId,
                  quantity = target.quantity,
                  speciesId = target.speciesId,
                  stratumName = substratumInfo.stratumName,
                  substratumHistoryId = substratumInfo.substratumHistoryId,
                  substratumId = target.substratumId,
                  substratumName = substratumInfo.substratumName,
              )
          )
        }
      }
    }
  }

  fun copySpeciesTargets(
      fromPlantingSeasonId: PlantingSeasonId,
      toPlantingSeasonId: PlantingSeasonId,
  ) {
    requirePermissions {
      updatePlantingSeason(toPlantingSeasonId)
      readPlantingSeason(fromPlantingSeasonId)
    }

    val userId = currentUser().userId
    val now = clock.instant()

    val (plantingSiteId, organizationId) =
        seasonHelper.fetchPlantingSiteAndOrganization(toPlantingSeasonId)

    seasonHelper.withLockedPlantingSeason(toPlantingSeasonId) {
      with(PLANTING_SEASON_SPECIES_TARGETS) {
        val substrataAndSpecies =
            dslContext
                .insertInto(
                    PLANTING_SEASON_SPECIES_TARGETS,
                    PLANTING_SEASON_ID,
                    SUBSTRATUM_ID,
                    SPECIES_ID,
                    QUANTITY,
                    CREATED_BY,
                    CREATED_TIME,
                    MODIFIED_BY,
                    MODIFIED_TIME,
                )
                .select(
                    DSL.select(
                            DSL.`val`(toPlantingSeasonId),
                            SUBSTRATUM_ID,
                            SPECIES_ID,
                            DSL.`val`(0),
                            DSL.`val`(userId),
                            DSL.`val`(now),
                            DSL.`val`(userId),
                            DSL.`val`(now),
                        )
                        .from(PLANTING_SEASON_SPECIES_TARGETS)
                        .where(PLANTING_SEASON_ID.eq(fromPlantingSeasonId))
                )
                .returningResult(SUBSTRATUM_ID.asNonNullable(), SPECIES_ID.asNonNullable())
                .fetch()

        val substrataInfo = seasonHelper.fetchSubstrataInfo(substrataAndSpecies.map { it.value1() })

        substrataAndSpecies.forEach { (substratumId, speciesId) ->
          val substratumInfo = substrataInfo[substratumId]!!

          eventPublisher.publishEvent(
              PlantingSeasonSpeciesTargetCreatedEvent(
                  organizationId = organizationId,
                  plantingSeasonId = toPlantingSeasonId,
                  plantingSiteId = plantingSiteId,
                  quantity = 0,
                  speciesId = speciesId,
                  stratumName = substratumInfo.stratumName,
                  substratumHistoryId = substratumInfo.substratumHistoryId,
                  substratumId = substratumId,
                  substratumName = substratumInfo.substratumName,
              )
          )
        }
      }
    }
  }

  fun delete(
      plantingSeasonId: PlantingSeasonId,
      substratumId: SubstratumId,
      speciesId: SpeciesId,
  ) {
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    seasonHelper.validateSeasonNotClosed(plantingSeasonId)

    seasonHelper.withLockedPlantingSeason(plantingSeasonId) {
      with(PLANTING_SEASON_SPECIES_TARGETS) {
        val deletedQuantity =
            dslContext
                .deleteFrom(PLANTING_SEASON_SPECIES_TARGETS)
                .where(PLANTING_SEASON_ID.eq(plantingSeasonId))
                .and(SUBSTRATUM_ID.eq(substratumId))
                .and(SPECIES_ID.eq(speciesId))
                .returning(QUANTITY)
                .fetchOne(QUANTITY)

        if (deletedQuantity != null) {
          val (plantingSiteId, organizationId) =
              seasonHelper.fetchPlantingSiteAndOrganization(plantingSeasonId)
          val substratumInfo = seasonHelper.fetchSubstratumInfo(substratumId)

          eventPublisher.publishEvent(
              PlantingSeasonSpeciesTargetDeletedEvent(
                  organizationId = organizationId,
                  plantingSeasonId = plantingSeasonId,
                  plantingSiteId = plantingSiteId,
                  speciesId = speciesId,
                  stratumName = substratumInfo.stratumName,
                  substratumHistoryId = substratumInfo.substratumHistoryId,
                  substratumId = substratumId,
                  substratumName = substratumInfo.substratumName,
              )
          )

          if (speciesHasTargets(plantingSeasonId, speciesId)) {
            if (deletedQuantity != 0) {
              rateLimitedEventPublisher.publishEvent(
                  RateLimitedPlantingSeasonTargetQuantitiesChangedEvent(
                      changes =
                          listOf(
                              RateLimitedPlantingSeasonTargetQuantitiesChangedEvent.Change(
                                  newQuantity = 0,
                                  oldQuantity = deletedQuantity,
                                  speciesId = speciesId,
                                  stratumName = substratumInfo.stratumName,
                                  substratumId = substratumId,
                                  substratumName = substratumInfo.substratumName,
                              )
                          ),
                      organizationId = organizationId,
                      plantingSeasonId = plantingSeasonId,
                      plantingSiteId = plantingSiteId,
                  )
              )
            }
          } else {
            rateLimitedEventPublisher.publishEvent(
                RateLimitedPlantingSeasonSpeciesChangedEvent(
                    organizationId = organizationId,
                    plantingSeasonId = plantingSeasonId,
                    removedSpeciesIds = setOf(speciesId),
                )
            )
          }
        }
      }
    }
  }

  private fun speciesHasTargets(plantingSeasonId: PlantingSeasonId, speciesId: SpeciesId) =
      dslContext.fetchExists(
          PLANTING_SEASON_SPECIES_TARGETS,
          PLANTING_SEASON_SPECIES_TARGETS.PLANTING_SEASON_ID.eq(plantingSeasonId)
              .and(PLANTING_SEASON_SPECIES_TARGETS.SPECIES_ID.eq(speciesId)),
      )

  private fun fetchByCondition(condition: Condition): List<PlantingSeasonSpeciesTargetModel> {
    return with(PLANTING_SEASON_SPECIES_TARGETS) {
      dslContext.selectFrom(PLANTING_SEASON_SPECIES_TARGETS).where(condition).fetch { record ->
        PlantingSeasonSpeciesTargetModel(
            substratumId = record[SUBSTRATUM_ID]!!,
            speciesId = record[SPECIES_ID]!!,
            quantity = record[QUANTITY]!!,
        )
      }
    }
  }
}
