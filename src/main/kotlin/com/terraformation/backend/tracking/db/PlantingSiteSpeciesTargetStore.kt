package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.EntityLocker
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SPECIES_TARGETS
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_SPECIES_TARGETS
import com.terraformation.backend.tracking.model.PlantingSiteSpeciesTargetModel
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

@Named
class PlantingSiteSpeciesTargetStore(
    private val dslContext: DSLContext,
    private val entityLocker: EntityLocker,
    private val parentStore: ParentStore,
) {
  fun fetchByPlantingSiteId(plantingSiteId: PlantingSiteId): List<PlantingSiteSpeciesTargetModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return with(PLANTING_SITE_SPECIES_TARGETS) {
      dslContext
          .select(SPECIES_ID, TARGET_PLANTS, stratumIdsMultiset)
          .from(PLANTING_SITE_SPECIES_TARGETS)
          .where(PLANTING_SITE_ID.eq(plantingSiteId))
          .orderBy(SPECIES_ID)
          .fetch { record ->
            PlantingSiteSpeciesTargetModel(
                speciesId = record[SPECIES_ID]!!,
                stratumIds = record[stratumIdsMultiset],
                targetPlants = record[TARGET_PLANTS],
            )
          }
    }
  }

  /**
   * Creates or replaces the target for a species at a planting site. The species' existing stratum
   * associations, if any, are replaced with the ones in [model].
   */
  fun upsert(plantingSiteId: PlantingSiteId, model: PlantingSiteSpeciesTargetModel) {
    requirePermissions {
      updatePlantingSite(plantingSiteId)
      readSpecies(model.speciesId)
    }

    require(model.targetPlants == null || model.targetPlants >= 0) {
      "Target number of plants must not be negative"
    }

    if (
        parentStore.getOrganizationId(model.speciesId) !=
            parentStore.getOrganizationId(plantingSiteId)
    ) {
      throw SpeciesInWrongOrganizationException(model.speciesId)
    }

    entityLocker.withLockedPlantingSite(plantingSiteId) {
      val stratumIdsInSite = fetchStratumIds(plantingSiteId)
      model.stratumIds
          .firstOrNull { it !in stratumIdsInSite }
          ?.let { throw StratumNotFoundException(it) }

      with(PLANTING_SITE_SPECIES_TARGETS) {
        dslContext
            .insertInto(PLANTING_SITE_SPECIES_TARGETS)
            .set(PLANTING_SITE_ID, plantingSiteId)
            .set(SPECIES_ID, model.speciesId)
            .set(TARGET_PLANTS, model.targetPlants)
            .onConflict(PLANTING_SITE_ID, SPECIES_ID)
            .doUpdate()
            .set(TARGET_PLANTS, model.targetPlants)
            .execute()
      }

      with(STRATUM_SPECIES_TARGETS) {
        dslContext
            .deleteFrom(STRATUM_SPECIES_TARGETS)
            .where(PLANTING_SITE_ID.eq(plantingSiteId))
            .and(SPECIES_ID.eq(model.speciesId))
            .and(STRATUM_ID.notIn(model.stratumIds))
            .execute()

        if (model.stratumIds.isNotEmpty()) {
          var insertQuery =
              dslContext.insertInto(
                  STRATUM_SPECIES_TARGETS,
                  PLANTING_SITE_ID,
                  STRATUM_ID,
                  SPECIES_ID,
              )

          model.stratumIds.forEach { stratumId ->
            insertQuery = insertQuery.values(plantingSiteId, stratumId, model.speciesId)
          }

          insertQuery.onConflictDoNothing().execute()
        }
      }
    }
  }

  /** Deletes a species' target from a planting site, including its stratum associations. */
  fun delete(plantingSiteId: PlantingSiteId, speciesId: SpeciesId) {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    with(PLANTING_SITE_SPECIES_TARGETS) {
      dslContext
          .deleteFrom(PLANTING_SITE_SPECIES_TARGETS)
          .where(PLANTING_SITE_ID.eq(plantingSiteId))
          .and(SPECIES_ID.eq(speciesId))
          .execute()
    }
  }

  private val stratumIdsMultiset: Field<Set<StratumId>> =
      with(STRATUM_SPECIES_TARGETS) {
        DSL.multiset(
                DSL.select(STRATUM_ID)
                    .from(STRATUM_SPECIES_TARGETS)
                    .where(PLANTING_SITE_ID.eq(PLANTING_SITE_SPECIES_TARGETS.PLANTING_SITE_ID))
                    .and(SPECIES_ID.eq(PLANTING_SITE_SPECIES_TARGETS.SPECIES_ID))
                    .orderBy(STRATUM_ID)
            )
            .convertFrom { result -> result.map { it[STRATUM_ID]!! }.toSet() }
      }

  private fun fetchStratumIds(plantingSiteId: PlantingSiteId): Set<StratumId> =
      dslContext
          .select(STRATA.ID)
          .from(STRATA)
          .where(STRATA.PLANTING_SITE_ID.eq(plantingSiteId))
          .fetchSet(STRATA.ID.asNonNullable())
}
