package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.PlantingDateRequestStatus
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUESTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUEST_SPECIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATES
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATE_SPECIES
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class PlantingDateRequestsStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  fun fetchId(scheduledPlantingDateId: ScheduledPlantingDateId): PlantingDateRequestId? =
      with(PLANTING_DATE_REQUESTS) {
        dslContext
            .select(ID)
            .from(PLANTING_DATE_REQUESTS)
            .where(SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId))
            .fetchOne(ID)
      }

  fun create(
      scheduledPlantingDateId: ScheduledPlantingDateId,
      plantingSeasonId: PlantingSeasonId,
      notes: String? = null,
  ) {
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    validateSeasonNotClosed(plantingSeasonId)

    val userId = currentUser().userId
    val now = clock.instant()

    dslContext.transaction { _ ->
      val rowsInserted =
          with(PLANTING_DATE_REQUESTS) {
            dslContext
                .insertInto(PLANTING_DATE_REQUESTS)
                .set(SCHEDULED_PLANTING_DATE_ID, scheduledPlantingDateId)
                .set(
                    DATE,
                    DSL.select(SCHEDULED_PLANTING_DATES.DATE)
                        .from(SCHEDULED_PLANTING_DATES)
                        .where(SCHEDULED_PLANTING_DATES.ID.eq(scheduledPlantingDateId)),
                )
                .set(NOTES, notes)
                .set(CREATED_BY, userId)
                .set(CREATED_TIME, now)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .set(STATUS_ID, PlantingDateRequestStatus.Pending)
                .onConflictDoNothing()
                .execute()
          }

      if (rowsInserted == 0) {
        throw PlantingSeasonDateRequestExistsException(scheduledPlantingDateId)
      }

      insertRequestSpecies(scheduledPlantingDateId)
    }
  }

  fun update(
      scheduledPlantingDateId: ScheduledPlantingDateId,
      plantingSeasonId: PlantingSeasonId,
      plantingDateRequestId: PlantingDateRequestId,
      notes: String? = null,
  ) {
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    validateSeasonNotClosed(plantingSeasonId)

    val userId = currentUser().userId
    val now = clock.instant()

    dslContext.transaction { _ ->
      val updatedCount =
          with(PLANTING_DATE_REQUESTS) {
            dslContext
                .update(PLANTING_DATE_REQUESTS)
                .set(
                    DATE,
                    DSL.select(SCHEDULED_PLANTING_DATES.DATE)
                        .from(SCHEDULED_PLANTING_DATES)
                        .where(SCHEDULED_PLANTING_DATES.ID.eq(scheduledPlantingDateId)),
                )
                .set(NOTES, notes)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .where(ID.eq(plantingDateRequestId))
                .execute()
          }

      if (updatedCount == 0) {
        throw PlantingSeasonDateRequestNotFoundException(plantingDateRequestId)
      }

      dslContext
          .deleteFrom(PLANTING_DATE_REQUEST_SPECIES)
          .where(PLANTING_DATE_REQUEST_SPECIES.PLANTING_DATE_REQUEST_ID.eq(plantingDateRequestId))
          .execute()

      insertRequestSpecies(plantingDateRequestId, scheduledPlantingDateId)
    }
  }

  private fun insertRequestSpecies(scheduledPlantingDateId: ScheduledPlantingDateId) {
    with(SCHEDULED_PLANTING_DATE_SPECIES) {
      dslContext
          .insertInto(PLANTING_DATE_REQUEST_SPECIES)
          .select(
              DSL.select(
                      DSL.`val`(scheduledPlantingDateId),
                      SUBSTRATUM_ID,
                      SPECIES_ID,
                      QUANTITY,
                  )
                  .from(SCHEDULED_PLANTING_DATE_SPECIES)
                  .where(SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId))
          )
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
