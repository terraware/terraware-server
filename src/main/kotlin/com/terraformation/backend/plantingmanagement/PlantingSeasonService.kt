package com.terraformation.backend.plantingmanagement

import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonSpeciesTargetsStore
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonStore
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class PlantingSeasonService(
    private val dslContext: DSLContext,
    private val plantingSeasonStore: PlantingSeasonStore,
    private val plantingSeasonSpeciesTargetsStore: PlantingSeasonSpeciesTargetsStore,
) {
  fun create(newModel: NewPlantingSeasonModel): PlantingSeasonId {
    return dslContext.transactionResult { _ ->
      val newSeasonId = plantingSeasonStore.create(newModel)

      if (newModel.fromPlantingSeasonId != null) {
        plantingSeasonSpeciesTargetsStore.copySpeciesTargets(
            newModel.fromPlantingSeasonId,
            newSeasonId,
        )
      }
      newSeasonId
    }
  }
}
