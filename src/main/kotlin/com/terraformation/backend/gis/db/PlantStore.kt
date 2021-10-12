package com.terraformation.backend.gis.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.PlantNotFoundException
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.UsesFuzzySearchOperators
import com.terraformation.backend.db.tables.daos.FeaturesDao
import com.terraformation.backend.db.tables.daos.PlantsDao
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.pojos.PlantsRow
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.PLANTS
import com.terraformation.backend.db.transformSrid
import java.lang.IllegalArgumentException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.annotation.ManagedBean
import net.postgis.jdbc.geometry.Geometry
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.DSL.trueCondition

data class FetchPlantListResult(
    val featureId: FeatureId,
    val label: String? = null,
    val speciesId: SpeciesId? = null,
    val naturalRegen: Boolean? = null,
    val datePlanted: LocalDate? = null,
    val layerId: LayerId,
    val gpsHorizAccuracy: Double? = null,
    val gpsVertAccuracy: Double? = null,
    val attrib: String? = null,
    val notes: String? = null,
    val enteredTime: Instant? = null,
    val geom: Geometry? = null,
)

@ManagedBean
class PlantStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val featuresDao: FeaturesDao,
    override val fuzzySearchOperators: FuzzySearchOperators,
    private val plantsDao: PlantsDao,
    private val speciesDao: SpeciesDao,
) : UsesFuzzySearchOperators {

  fun createPlant(plant: PlantsRow): PlantsRow {
    val featureId = plant.featureId ?: throw IllegalArgumentException("featureId cannot be null")

    requirePermissions { createLayerData(featureId) }

    if (!featuresDao.existsById(featureId)) {
      throw FeatureNotFoundException(featureId)
    }

    val currTime = clock.instant()
    val result = plant.copy(createdTime = currTime, modifiedTime = currTime)
    plantsDao.insert(result)
    return result
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
      conditions = conditions.and(FEATURES.NOTES.likeFuzzy(notes))
    }

    val records =
        dslContext
            .select(
                PLANTS.FEATURE_ID,
                PLANTS.LABEL,
                PLANTS.SPECIES_ID,
                PLANTS.NATURAL_REGEN,
                PLANTS.DATE_PLANTED,
                FEATURES.LAYER_ID,
                FEATURES.GPS_HORIZ_ACCURACY,
                FEATURES.GPS_VERT_ACCURACY,
                FEATURES.ATTRIB,
                FEATURES.NOTES,
                FEATURES.ENTERED_TIME,
                FEATURES.GEOM.transformSrid(SRID.LONG_LAT).`as`(FEATURES.GEOM),
            )
            .from(PLANTS)
            .join(FEATURES)
            .on(PLANTS.FEATURE_ID.eq(FEATURES.ID))
            .where(conditions)
            .fetch()

    return records.map {
      FetchPlantListResult(
          featureId = it[PLANTS.FEATURE_ID]!!,
          label = it[PLANTS.LABEL],
          speciesId = it[PLANTS.SPECIES_ID],
          naturalRegen = it[PLANTS.NATURAL_REGEN],
          datePlanted = it[PLANTS.DATE_PLANTED],
          layerId = it[FEATURES.LAYER_ID]!!,
          gpsHorizAccuracy = it[FEATURES.GPS_HORIZ_ACCURACY],
          gpsVertAccuracy = it[FEATURES.GPS_VERT_ACCURACY],
          attrib = it[FEATURES.ATTRIB],
          notes = it[FEATURES.NOTES],
          enteredTime = it[FEATURES.ENTERED_TIME],
          geom = it[FEATURES.GEOM],
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
    records.forEach { record ->
      val speciesId = record[PLANTS.SPECIES_ID] ?: SpeciesId(-1)
      summaryMap[speciesId] = record.get(1) as Int
    }
    return summaryMap
  }

  fun updatePlant(plant: PlantsRow): PlantsRow {
    val featureId = plant.featureId ?: throw IllegalArgumentException("featureId cannot be null")

    if (!currentUser().canUpdateLayerData(featureId)) {
      throw PlantNotFoundException(featureId)
    }

    val existingPlant =
        plantsDao.fetchOneByFeatureId(featureId) ?: throw PlantNotFoundException(featureId)

    if (existingPlant.copy(modifiedTime = null, createdTime = null) ==
        plant.copy(modifiedTime = null, createdTime = null)) {
      return existingPlant
    }

    val result = plant.copy(createdTime = existingPlant.createdTime, modifiedTime = clock.instant())
    plantsDao.update(result)
    return result
  }
}
