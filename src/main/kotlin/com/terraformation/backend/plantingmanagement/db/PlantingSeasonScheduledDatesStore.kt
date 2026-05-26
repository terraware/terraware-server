package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATES
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATE_SPECIES
import com.terraformation.backend.plantingmanagement.PlantingSeasonScheduledDateModel
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext

@Named
class PlantingSeasonScheduledDatesStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  fun create(model: PlantingSeasonScheduledDateModel): ScheduledPlantingDateId {
    require(model.species.all { it.quantity >= 0 }) { "All quantities must be >= 0" }
    requirePermissions { updatePlantingSeason(model.plantingSeasonId) }

    val userId = currentUser().userId
    val now = clock.instant()

    val scheduledDateId =
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
            scheduledDateId,
            species.speciesId,
            species.substratumId,
            species.quantity,
        )
      }

      insertQuery.onConflictDoNothing().execute()
    }

    return scheduledDateId
  }
}
