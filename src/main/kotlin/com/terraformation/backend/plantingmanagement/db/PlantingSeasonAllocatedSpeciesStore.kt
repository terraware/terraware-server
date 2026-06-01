package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_ALLOCATED_SPECIES
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext

@Named
class PlantingSeasonAllocatedSpeciesStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  fun upsert(plantingSeasonId: PlantingSeasonId, speciesId: SpeciesId, quantity: Int) {
    require(quantity >= 0) { "Quantity must be >= 0" }
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    validateSeasonNotClosed(plantingSeasonId)

    val userId = currentUser().userId
    val now = clock.instant()

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
