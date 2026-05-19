package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_SPECIES_TARGETS
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext

@Named
class PlantingSeasonSpeciesTargetsStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  fun upsert(
      plantingSeasonId: PlantingSeasonId,
      substratumId: SubstratumId,
      speciesId: SpeciesId,
      quantity: Int,
  ) {
    require(quantity >= 0) { "Quantity must be >= 0" }
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    val userId = currentUser().userId
    val now = clock.instant()

    with(PLANTING_SEASON_SPECIES_TARGETS) {
      dslContext
          .insertInto(PLANTING_SEASON_SPECIES_TARGETS)
          .set(PLANTING_SEASON_ID, plantingSeasonId.value)
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
}
