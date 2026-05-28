package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_SPECIES_TARGETS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.plantingmanagement.ExistingPlantingSeasonModel
import com.terraformation.backend.plantingmanagement.NewPlantingSeasonModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonSpeciesTargetModel
import jakarta.inject.Named
import java.time.InstantSource
import java.time.LocalDate
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class PlantingSeasonStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val parentStore: ParentStore,
) {
  fun create(newModel: NewPlantingSeasonModel): PlantingSeasonId {
    requirePermissions { createPlantingSeason(newModel.plantingSiteId) }

    val userId = currentUser().userId
    val now = clock.instant()
    val status = calculateStatus(newModel.startDate, newModel.endDate, newModel.plantingSiteId)

    return with(PLANTING_SEASONS) {
      dslContext
          .insertInto(PLANTING_SEASONS)
          .set(NAME, newModel.name)
          .set(PLANTING_SITE_ID, newModel.plantingSiteId)
          .set(START_DATE, newModel.startDate)
          .set(END_DATE, newModel.endDate)
          .set(STATUS_ID, status)
          .set(CREATED_BY, userId)
          .set(CREATED_TIME, now)
          .set(MODIFIED_BY, userId)
          .set(MODIFIED_TIME, now)
          .onConflictDoNothing()
          .returning(ID)
          .fetchOne(ID)
          ?: throw PlantingSeasonExistsException(newModel.plantingSiteId, newModel.name)
    }
  }

  fun fetchById(id: PlantingSeasonId): ExistingPlantingSeasonModel {
    requirePermissions { readPlantingSeason(id) }

    return fetchByCondition(PLANTING_SEASONS.ID.eq(id)).firstOrNull()
        ?: throw PlantingSeasonNotFoundException(id)
  }

  fun fetchList(plantingSiteId: PlantingSiteId): List<ExistingPlantingSeasonModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return fetchByCondition(PLANTING_SEASONS.PLANTING_SITE_ID.eq(plantingSiteId))
  }

  fun fetchList(organizationId: OrganizationId): List<ExistingPlantingSeasonModel> {
    requirePermissions { readOrganization(organizationId) }

    return fetchByCondition(
        PLANTING_SEASONS.PLANTING_SITE_ID.`in`(
            DSL.select(PLANTING_SITES.ID)
                .from(PLANTING_SITES)
                .where(PLANTING_SITES.ORGANIZATION_ID.eq(organizationId))
        )
    )
  }

  fun update(
      plantingSeasonId: PlantingSeasonId,
      name: String,
      startDate: LocalDate,
      endDate: LocalDate,
  ) {
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    val now = clock.instant()
    val plantingSiteId =
        parentStore.getPlantingSiteId(plantingSeasonId)
            ?: throw PlantingSeasonNotFoundException(plantingSeasonId)
    val status = calculateStatus(startDate, endDate, plantingSiteId)

    val rowsUpdated =
        with(PLANTING_SEASONS) {
          dslContext
              .update(PLANTING_SEASONS)
              .set(NAME, name)
              .set(START_DATE, startDate)
              .set(END_DATE, endDate)
              .set(STATUS_ID, status)
              .set(MODIFIED_BY, currentUser().userId)
              .set(MODIFIED_TIME, now)
              .where(ID.eq(plantingSeasonId))
              .execute()
        }

    if (rowsUpdated == 0) {
      throw PlantingSeasonNotFoundException(plantingSeasonId)
    }
  }

  fun delete(id: PlantingSeasonId) {
    requirePermissions { deletePlantingSeason(id) }

    val rowsDeleted =
        dslContext.deleteFrom(PLANTING_SEASONS).where(PLANTING_SEASONS.ID.eq(id)).execute()

    if (rowsDeleted == 0) {
      throw PlantingSeasonNotFoundException(id)
    }
  }

  private fun calculateStatus(
      startDate: LocalDate,
      endDate: LocalDate,
      plantingSiteId: PlantingSiteId,
  ): PlantingSeasonStatus {
    val today =
        clock.instant().atZone(parentStore.getEffectiveTimeZone(plantingSiteId)).toLocalDate()
    return when {
      today < startDate -> PlantingSeasonStatus.Upcoming
      today <= endDate -> PlantingSeasonStatus.Active
      else -> PlantingSeasonStatus.PastEndDate
    }
  }

  private fun fetchByCondition(condition: Condition): List<ExistingPlantingSeasonModel> {
    val targetsMultiset =
        DSL.multiset(
                DSL.select(
                        PLANTING_SEASON_SPECIES_TARGETS.SUBSTRATUM_ID,
                        PLANTING_SEASON_SPECIES_TARGETS.SPECIES_ID,
                        PLANTING_SEASON_SPECIES_TARGETS.QUANTITY,
                    )
                    .from(PLANTING_SEASON_SPECIES_TARGETS)
                    .where(
                        PLANTING_SEASON_SPECIES_TARGETS.PLANTING_SEASON_ID.eq(PLANTING_SEASONS.ID)
                    )
            )
            .convertFrom { result ->
              result.map { record ->
                PlantingSeasonSpeciesTargetModel(
                    substratumId = record[PLANTING_SEASON_SPECIES_TARGETS.SUBSTRATUM_ID]!!,
                    speciesId = record[PLANTING_SEASON_SPECIES_TARGETS.SPECIES_ID]!!,
                    quantity = record[PLANTING_SEASON_SPECIES_TARGETS.QUANTITY]!!,
                )
              }
            }

    return with(PLANTING_SEASONS) {
      dslContext
          .select(PLANTING_SEASONS.asterisk(), targetsMultiset)
          .from(PLANTING_SEASONS)
          .where(condition)
          .fetch { record ->
            ExistingPlantingSeasonModel(
                endDate = record[END_DATE]!!,
                id = record[ID]!!,
                name = record[NAME]!!,
                plantingSiteId = record[PLANTING_SITE_ID]!!,
                speciesTargets = record[targetsMultiset],
                startDate = record[START_DATE]!!,
                status = record[STATUS_ID]!!,
            )
          }
    }
  }
}
