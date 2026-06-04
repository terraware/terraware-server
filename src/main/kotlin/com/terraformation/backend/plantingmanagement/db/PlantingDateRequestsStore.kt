package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWALS
import com.terraformation.backend.db.tracking.PlantingDateRequestStatus
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUESTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUEST_SPECIES
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATES
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATE_SPECIES
import com.terraformation.backend.nursery.event.WithdrawalAssociatedWithPlantingDateRequestEvent
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.InstantSource
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.event.EventListener

@Named
class PlantingDateRequestsStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val seasonHelper: SeasonHelper,
) {

  fun create(
      scheduledPlantingDateId: ScheduledPlantingDateId,
      plantingSeasonId: PlantingSeasonId,
      notes: String? = null,
  ) {
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    seasonHelper.validateSeasonNotClosed(plantingSeasonId)

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
      notes: String? = null,
  ) {
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    seasonHelper.validateSeasonNotClosed(plantingSeasonId)

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
                .where(SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId))
                .execute()
          }

      if (updatedCount == 0) {
        throw PlantingSeasonDateRequestNotFoundException(scheduledPlantingDateId)
      }

      dslContext
          .deleteFrom(PLANTING_DATE_REQUEST_SPECIES)
          .where(
              PLANTING_DATE_REQUEST_SPECIES.SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId)
          )
          .execute()

      insertRequestSpecies(scheduledPlantingDateId)
    }
  }

  @EventListener
  fun on(event: WithdrawalAssociatedWithPlantingDateRequestEvent) {
    updateRequestStatus(event.scheduledPlantingDateRequestId)
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

  private fun updateRequestStatus(scheduledPlantingDateId: ScheduledPlantingDateId) {
    val requestedQuantities: Map<SpeciesId, BigDecimal> =
        with(PLANTING_DATE_REQUEST_SPECIES) {
          dslContext
              .select(SPECIES_ID, DSL.sum(QUANTITY))
              .from(PLANTING_DATE_REQUEST_SPECIES)
              .where(SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId))
              .groupBy(SPECIES_ID)
              .associate { it[SPECIES_ID]!! to it[DSL.sum(QUANTITY)]!! }
        }

    val withdrawnQuantities: Map<SpeciesId, BigDecimal> =
        dslContext
            .select(BATCHES.SPECIES_ID, DSL.sum(BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN))
            .from(BATCH_WITHDRAWALS)
            .join(BATCHES)
            .on(BATCHES.ID.eq(BATCH_WITHDRAWALS.BATCH_ID))
            .join(WITHDRAWALS)
            .on(WITHDRAWALS.ID.eq(BATCH_WITHDRAWALS.WITHDRAWAL_ID))
            .where(WITHDRAWALS.SCHEDULED_PLANTING_DATE_REQUEST_ID.eq(scheduledPlantingDateId))
            .groupBy(BATCHES.SPECIES_ID)
            .associate {
              it[BATCHES.SPECIES_ID]!! to it[DSL.sum(BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN)]!!
            }

    val newStatus =
        when {
          requestedQuantities.isEmpty() -> PlantingDateRequestStatus.Pending
          requestedQuantities.all { (speciesId, quantity) ->
            (withdrawnQuantities[speciesId] ?: BigDecimal.ZERO) >= quantity
          } -> PlantingDateRequestStatus.Fulfilled
          requestedQuantities.keys.any {
            (withdrawnQuantities[it] ?: BigDecimal.ZERO) > BigDecimal.ZERO
          } -> PlantingDateRequestStatus.Partial
          else -> PlantingDateRequestStatus.Pending
        }

    // This runs as an automated recompute triggered by a withdrawal event rather than a user
    // edit, so modified_by and modified_time are intentionally left unchanged.
    dslContext
        .update(PLANTING_DATE_REQUESTS)
        .set(PLANTING_DATE_REQUESTS.STATUS_ID, newStatus)
        .where(PLANTING_DATE_REQUESTS.SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId))
        .execute()
  }
}
