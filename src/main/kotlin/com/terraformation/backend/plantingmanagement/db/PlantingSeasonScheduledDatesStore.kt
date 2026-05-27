package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATES
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATE_SPECIES
import com.terraformation.backend.plantingmanagement.ExistingPlantingSeasonScheduledDateModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonScheduledDateModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonScheduledDateSpecies
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class PlantingSeasonScheduledDatesStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
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

      newScheduledDateId
    }

    return scheduledDateId
  }

  fun update(
      scheduledDateId: ScheduledPlantingDateId,
      model: PlantingSeasonScheduledDateModel,
  ) {
    requirePermissions { updatePlantingSeason(model.plantingSeasonId) }

    dslContext.transaction { _ ->
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

      with(SCHEDULED_PLANTING_DATE_SPECIES) {
        dslContext
            .deleteFrom(SCHEDULED_PLANTING_DATE_SPECIES)
            .where(SCHEDULED_PLANTING_DATE_ID.eq(scheduledDateId))
            .execute()

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

        insertQuery.execute()
      }
    }
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
}
