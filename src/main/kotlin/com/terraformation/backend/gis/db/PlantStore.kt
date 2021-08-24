package com.terraformation.backend.gis.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.PlantNotFoundException
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.tables.daos.PlantsDao
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.pojos.PlantsRow
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.PLANTS
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.annotation.ManagedBean
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.DSL.trueCondition
import org.springframework.security.access.AccessDeniedException

data class FetchPlantListResult(
    val featureId: FeatureId,
    val label: String? = null,
    val speciesId: SpeciesId? = null,
    val naturalRegen: Boolean? = null,
    val datePlanted: LocalDate? = null,
    val notes: String? = null,
    val enteredTime: Instant? = null,
)

@ManagedBean
class PlantStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val plantsDao: PlantsDao,
    private val speciesDao: SpeciesDao,
) {
  fun createPlant(featureId: FeatureId, plant: PlantsRow): PlantsRow {
    if (featureId != plant.featureId) {
      throw IllegalArgumentException("featureId in PlantsRow must match featureId argument")
    }
    if (!currentUser().canCreateLayerData(featureId)) {
      throw AccessDeniedException(
          "No permission to create a plant associated with featureId $featureId",
      )
    }

    val currTime = clock.instant()
    plant.createdTime = currTime
    plant.modifiedTime = currTime

    plantsDao.insert(plant)

    return plant
  }

  fun fetchPlant(featureId: FeatureId): PlantsRow? {
    if (!currentUser().canReadLayerData(featureId)) {
      return null
    }

    return plantsDao.fetchOneByFeatureId(featureId)
  }

  fun fetchPlantsList(
      layerId: LayerId,
      speciesName: String? = null,
      minEnteredTime: Instant? = null,
      maxEnteredTime: Instant? = null,
      notes: String? = null
  ): List<FetchPlantListResult> {
    if (!currentUser().canReadLayerData(layerId)) {
      return emptyList()
    }

    var conditions: Condition = trueCondition()

    if (speciesName != null) {
      val speciesId = speciesDao.fetchOneByName(speciesName)?.id ?: return emptyList()
      conditions = conditions.and(PLANTS.SPECIES_ID.eq(speciesId))
    }
    if (minEnteredTime != null) {
      conditions = conditions.and(FEATURES.ENTERED_TIME.greaterOrEqual(minEnteredTime))
    }
    if (maxEnteredTime != null) {
      conditions = conditions.and(FEATURES.ENTERED_TIME.lessOrEqual(maxEnteredTime))
    }
    if (notes != null) {
      conditions = conditions.and(FEATURES.NOTES.eq(notes))
    }

    val records =
        dslContext
            .select(
                PLANTS.FEATURE_ID,
                PLANTS.LABEL,
                PLANTS.SPECIES_ID,
                PLANTS.NATURAL_REGEN,
                PLANTS.DATE_PLANTED,
                FEATURES.NOTES,
                FEATURES.ENTERED_TIME)
            .from(PLANTS)
            .join(FEATURES)
            .on(PLANTS.FEATURE_ID.eq(FEATURES.ID))
            .where(conditions)
            .fetch()

    return records.map {
      FetchPlantListResult(
          featureId = it.get(PLANTS.FEATURE_ID)!!,
          label = it.get(PLANTS.LABEL),
          speciesId = it.get(PLANTS.SPECIES_ID),
          naturalRegen = it.get(PLANTS.NATURAL_REGEN),
          datePlanted = it.get(PLANTS.DATE_PLANTED),
          notes = it.get(FEATURES.NOTES),
          enteredTime = it.get(FEATURES.ENTERED_TIME),
      )
    }
  }

  fun fetchPlantSummary(
      layerId: LayerId,
      minEnteredTime: Instant? = null,
      maxEnteredTime: Instant? = null,
  ): Map<SpeciesId, Int> {
    if (!currentUser().canReadLayerData(layerId)) {
      return emptyMap()
    }
    val records =
        dslContext
            .select(PLANTS.SPECIES_ID, DSL.count(PLANTS.FEATURE_ID))
            .from(PLANTS)
            .join(FEATURES)
            .on(PLANTS.FEATURE_ID.eq(FEATURES.ID))
            .where(FEATURES.LAYER_ID.eq(layerId))
            .and(
                if (minEnteredTime != null) FEATURES.ENTERED_TIME.greaterOrEqual(minEnteredTime)
                else DSL.noCondition(),
            )
            .and(
                if (maxEnteredTime != null) FEATURES.ENTERED_TIME.lessOrEqual(maxEnteredTime)
                else DSL.noCondition(),
            )
            .groupBy(PLANTS.SPECIES_ID)
            .fetch()

    val summaryMap = mutableMapOf<SpeciesId, Int>()
    records.forEach { record -> summaryMap[record.get(PLANTS.SPECIES_ID)!!] = record.get(1) as Int }
    return summaryMap
  }

  fun updatePlant(featureId: FeatureId, plant: PlantsRow): PlantsRow {
    if (featureId != plant.featureId) {
      throw IllegalArgumentException("featureId in PlantsRow must match featureId argument")
    }

    if (!currentUser().canUpdateLayerData(featureId)) {
      throw PlantNotFoundException(featureId)
    }

    val existingPlant =
        plantsDao.fetchOneByFeatureId(featureId) ?: throw PlantNotFoundException(featureId)

    if (existingPlant.copy(modifiedTime = null, createdTime = null) ==
        plant.copy(modifiedTime = null, createdTime = null)) {
      return existingPlant
    }

    plant.createdTime = existingPlant.createdTime
    plant.modifiedTime = clock.instant()

    plantsDao.update(plant)
    return plant
  }
}
