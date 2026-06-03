package com.terraformation.backend.plantingmanagement

import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.plantingmanagement.db.PlantingDateRequestsStore
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonScheduledDatesStore
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class PlantingSeasonScheduledDatesService(
    private val dslContext: DSLContext,
    private val plantingDateRequestsStore: PlantingDateRequestsStore,
    private val plantingSeasonScheduledDatesStore: PlantingSeasonScheduledDatesStore,
) {

  fun create(model: PlantingSeasonScheduledDateModel): ScheduledPlantingDateId {
    return dslContext.transactionResult { _ ->
      val scheduledPlantingDateId = plantingSeasonScheduledDatesStore.create(model)

      if (model.createNurseryRequest == true) {
        plantingDateRequestsStore.create(
            scheduledPlantingDateId,
            model.plantingSeasonId,
            model.nurseryRequestNotes,
        )
      }

      scheduledPlantingDateId
    }
  }
}
