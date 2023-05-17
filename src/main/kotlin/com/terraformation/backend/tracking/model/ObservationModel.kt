package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import java.time.Instant
import java.time.LocalDate
import org.jooq.Record

data class ObservationModel<ID : ObservationId?>(
    val completedTime: Instant? = null,
    val endDate: LocalDate,
    val id: ID,
    val plantingSiteId: PlantingSiteId,
    val startDate: LocalDate,
    val state: ObservationState,
) {
  companion object {
    fun of(record: Record): ExistingObservationModel {
      return ObservationModel(
          completedTime = record[OBSERVATIONS.COMPLETED_TIME],
          endDate = record[OBSERVATIONS.END_DATE]!!,
          id = record[OBSERVATIONS.ID]!!,
          plantingSiteId = record[OBSERVATIONS.PLANTING_SITE_ID]!!,
          startDate = record[OBSERVATIONS.START_DATE]!!,
          state = record[OBSERVATIONS.STATE_ID]!!,
      )
    }
  }
}

typealias ExistingObservationModel = ObservationModel<ObservationId>

typealias NewObservationModel = ObservationModel<Nothing?>
