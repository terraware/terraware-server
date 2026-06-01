package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_SPECIES_TARGETS
import com.terraformation.backend.plantingmanagement.PlantingSeasonSpeciesTargetModel
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetDeletedEventV1
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
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
    private val parentStore: ParentStore,
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

    validateSeasonNotClosed(plantingSeasonId)

    val userId = currentUser().userId
    val now = clock.instant()

    with(PLANTING_SEASON_SPECIES_TARGETS) {
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

    with(PLANTING_SEASON_SPECIES_TARGETS) {
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
          .execute()
    }
  }

  fun delete(
      plantingSeasonId: PlantingSeasonId,
      substratumId: SubstratumId,
      speciesId: SpeciesId,
  ) {
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    validateSeasonNotClosed(plantingSeasonId)

    val plantingSiteId =
        parentStore.getPlantingSiteId(plantingSeasonId)
            ?: throw PlantingSeasonNotFoundException(plantingSeasonId)

    val organizationId =
        parentStore.getOrganizationId(plantingSiteId)
            ?: throw PlantingSiteNotFoundException(plantingSiteId)

    with(PLANTING_SEASON_SPECIES_TARGETS) {
      dslContext
          .deleteFrom(PLANTING_SEASON_SPECIES_TARGETS)
          .where(PLANTING_SEASON_ID.eq(plantingSeasonId))
          .and(SUBSTRATUM_ID.eq(substratumId))
          .and(SPECIES_ID.eq(speciesId))
          .execute()

      eventPublisher.publishEvent(
          PlantingSeasonSpeciesTargetDeletedEventV1(
              organizationId = organizationId,
              plantingSeasonId = plantingSeasonId,
              plantingSiteId = plantingSiteId,
              speciesId = speciesId,
              substratumId = substratumId,
          )
      )
    }
  }

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
}
