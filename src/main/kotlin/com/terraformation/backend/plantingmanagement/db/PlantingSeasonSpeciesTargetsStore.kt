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
        } else {
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
                .returningResult(
                    // DSL.field works around a jOOQ behavior/bug where it will add an "excluded."
                    // prefix to the fields in the "returning" clause.
                    DSL.field(SUBSTRATUM_ID.name, SUBSTRATUM_ID.dataType).asNonNullable(),
                    DSL.field(SPECIES_ID.name, SPECIES_ID.dataType).asNonNullable(),
                )
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
        val rowsDeleted =
            dslContext
                .deleteFrom(PLANTING_SEASON_SPECIES_TARGETS)
                .where(PLANTING_SEASON_ID.eq(plantingSeasonId))
                .and(SUBSTRATUM_ID.eq(substratumId))
                .and(SPECIES_ID.eq(speciesId))
                .execute()

        if (rowsDeleted > 0) {
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
        }
      }
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
}
