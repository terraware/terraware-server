package com.terraformation.backend.plantingmanagement

import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonScheduledDatesStore
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonSpeciesTargetsStore
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonStore
import com.terraformation.backend.tracking.event.SubstratumDeletionStartedEvent
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

@Named
class PlantingSeasonService(
    private val dslContext: DSLContext,
    private val plantingSeasonStore: PlantingSeasonStore,
    private val plantingSeasonScheduledDatesStore: PlantingSeasonScheduledDatesStore,
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

  @EventListener
  fun on(event: SubstratumDeletionStartedEvent) {
    plantingSeasonScheduledDatesStore.publishSpeciesDeletedEvents(event.substratumId)
  }
}
