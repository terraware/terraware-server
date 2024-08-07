package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import java.time.Instant
import java.time.LocalDate
import org.jooq.Field
import org.jooq.Record

data class ObservationModel<ID : ObservationId?>(
    val completedTime: Instant? = null,
    val endDate: LocalDate,
    val id: ID,
    val plantingSiteId: PlantingSiteId,
    val requestedSubzoneIds: Set<PlantingSubzoneId> = emptySet(),
    val startDate: LocalDate,
    val state: ObservationState,
    val upcomingNotificationSentTime: Instant? = null,
) {
  fun validateStateTransition(newState: ObservationState) {
    if (state != newState && (state to newState) !in validStateTransitions) {
      throw IllegalArgumentException("Cannot transition observation from $state to $newState")
    }
  }

  companion object {
    private val validStateTransitions =
        setOf(
            ObservationState.Upcoming to ObservationState.InProgress,
            ObservationState.InProgress to ObservationState.Completed,
            ObservationState.InProgress to ObservationState.Overdue,
            ObservationState.Overdue to ObservationState.Completed,
        )

    fun of(
        record: Record,
        requestedSubzoneIdsField: Field<Set<PlantingSubzoneId>>
    ): ExistingObservationModel {
      return ObservationModel(
          completedTime = record[OBSERVATIONS.COMPLETED_TIME],
          endDate = record[OBSERVATIONS.END_DATE]!!,
          id = record[OBSERVATIONS.ID]!!,
          plantingSiteId = record[OBSERVATIONS.PLANTING_SITE_ID]!!,
          requestedSubzoneIds = record[requestedSubzoneIdsField],
          startDate = record[OBSERVATIONS.START_DATE]!!,
          state = record[OBSERVATIONS.STATE_ID]!!,
          upcomingNotificationSentTime = record[OBSERVATIONS.UPCOMING_NOTIFICATION_SENT_TIME],
      )
    }
  }
}

typealias ExistingObservationModel = ObservationModel<ObservationId>

typealias NewObservationModel = ObservationModel<Nothing?>
