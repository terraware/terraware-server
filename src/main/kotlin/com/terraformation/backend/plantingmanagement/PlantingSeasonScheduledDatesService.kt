package com.terraformation.backend.plantingmanagement

import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUESTS
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

  fun create(
      model: PlantingSeasonScheduledDateModel,
      createNurseryRequest: Boolean,
      nurseryRequestNotes: String? = null,
  ): ScheduledPlantingDateId {
    return dslContext.transactionResult { _ ->
      val scheduledPlantingDateId = plantingSeasonScheduledDatesStore.create(model)

      if (createNurseryRequest) {
        plantingDateRequestsStore.create(
            scheduledPlantingDateId,
            model.plantingSeasonId,
            nurseryRequestNotes,
        )
      }

      scheduledPlantingDateId
    }
  }

  fun update(
      scheduledPlantingDateId: ScheduledPlantingDateId,
      model: PlantingSeasonScheduledDateModel,
  ) {
    dslContext.transaction { _ ->
      plantingSeasonScheduledDatesStore.update(scheduledPlantingDateId, model)

      val requestExists =
          dslContext.fetchExists(
              PLANTING_DATE_REQUESTS,
              PLANTING_DATE_REQUESTS.SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId),
          )

      if (model.createNurseryRequest == true) {
        if (requestExists) {
          plantingDateRequestsStore.update(
              scheduledPlantingDateId,
              model.plantingSeasonId,
              model.nurseryRequestNotes,
          )
        } else {
          plantingDateRequestsStore.create(
              scheduledPlantingDateId,
              model.plantingSeasonId,
              model.nurseryRequestNotes,
          )
        }
      }
    }
  }
}
