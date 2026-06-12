package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_ALLOCATED_SPECIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_SPECIES_TARGETS
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesUpdatedEventValues
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetDeletedEvent
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@Named
class PlantingSeasonAllocatedSpeciesStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val seasonHelper: SeasonHelper,
) {
  fun upsert(plantingSeasonId: PlantingSeasonId, speciesId: SpeciesId, quantity: Int) {
    require(quantity >= 0) { "Quantity must be >= 0" }
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    seasonHelper.validateSeasonNotClosed(plantingSeasonId)

    val userId = currentUser().userId
    val now = clock.instant()
    val (plantingSiteId, organizationId) =
        seasonHelper.fetchPlantingSiteAndOrganization(plantingSeasonId)

    seasonHelper.withLockedPlantingSeason(plantingSeasonId) {
      val existingQuantity =
          with(PLANTING_SEASON_ALLOCATED_SPECIES) {
            dslContext
                .select(QUANTITY)
                .from(PLANTING_SEASON_ALLOCATED_SPECIES)
                .where(PLANTING_SEASON_ID.eq(plantingSeasonId))
                .and(SPECIES_ID.eq(speciesId))
                .fetchOne(QUANTITY)
          }

      with(PLANTING_SEASON_ALLOCATED_SPECIES) {
        dslContext
            .insertInto(PLANTING_SEASON_ALLOCATED_SPECIES)
            .set(PLANTING_SEASON_ID, plantingSeasonId)
            .set(SPECIES_ID, speciesId)
            .set(QUANTITY, quantity)
            .set(CREATED_BY, userId)
            .set(CREATED_TIME, now)
            .set(MODIFIED_BY, userId)
            .set(MODIFIED_TIME, now)
            .onConflict(PLANTING_SEASON_ID, SPECIES_ID)
            .doUpdate()
            .set(QUANTITY, quantity)
            .set(MODIFIED_BY, userId)
            .set(MODIFIED_TIME, now)
            .execute()
      }

      when {
        existingQuantity == null ->
            eventPublisher.publishEvent(
                PlantingSeasonAllocatedSpeciesCreatedEvent(
                    organizationId = organizationId,
                    plantingSeasonId = plantingSeasonId,
                    plantingSiteId = plantingSiteId,
                    quantity = quantity,
                    speciesId = speciesId,
                )
            )

        existingQuantity != quantity ->
            eventPublisher.publishEvent(
                PlantingSeasonAllocatedSpeciesUpdatedEvent(
                    changedFrom =
                        PlantingSeasonAllocatedSpeciesUpdatedEventValues(
                            quantity = existingQuantity
                        ),
                    changedTo =
                        PlantingSeasonAllocatedSpeciesUpdatedEventValues(quantity = quantity),
                    organizationId = organizationId,
                    plantingSeasonId = plantingSeasonId,
                    plantingSiteId = plantingSiteId,
                    speciesId = speciesId,
                )
            )
      }
    }
  }

  @EventListener
  fun on(event: PlantingSeasonSpeciesTargetDeletedEvent) {
    val remainingSpeciesTargets =
        dslContext
            .selectCount()
            .from(PLANTING_SEASON_SPECIES_TARGETS)
            .where(PLANTING_SEASON_SPECIES_TARGETS.PLANTING_SEASON_ID.eq(event.plantingSeasonId))
            .and(PLANTING_SEASON_SPECIES_TARGETS.SPECIES_ID.eq(event.speciesId))
            .fetchOne(0, Int::class.java) ?: 0

    if (remainingSpeciesTargets == 0) {
      delete(event.plantingSeasonId, event.speciesId)
    }
  }

  private fun delete(plantingSeasonId: PlantingSeasonId, speciesId: SpeciesId) {
    with(PLANTING_SEASON_ALLOCATED_SPECIES) {
      dslContext
          .deleteFrom(PLANTING_SEASON_ALLOCATED_SPECIES)
          .where(PLANTING_SEASON_ID.eq(plantingSeasonId))
          .and(SPECIES_ID.eq(speciesId))
          .execute()
    }
  }
}
