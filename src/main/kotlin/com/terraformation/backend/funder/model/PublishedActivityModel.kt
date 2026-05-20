package com.terraformation.backend.funder.model

import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITIES
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITY_OBSERVATIONS
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import java.time.Instant
import java.time.LocalDate
import org.jooq.Field
import org.jooq.Record

data class PublishedActivityModel(
    val activityDate: LocalDate,
    val activityType: ActivityType,
    val description: String?,
    val id: ActivityId,
    val isHighlight: Boolean,
    val media: List<PublishedActivityMediaModel> = emptyList(),
    val observation: Observation? = null,
    val projectId: ProjectId,
    val publishedBy: UserId? = null,
    val publishedTime: Instant? = null,
) {
  data class Observation(
      val observationId: ObservationId,
      val completedTime: Instant,
      val livePlants: Int?,
      val plantDensity: Int?,
      val survivalRate: Int?,
  ) {
    companion object {
      fun ofOrNull(record: Record): Observation? {
        return with(PUBLISHED_ACTIVITY_OBSERVATIONS) {
          record[OBSERVATION_ID]?.let { observationId ->
            Observation(
                observationId = observationId,
                completedTime = record[OBSERVATIONS.COMPLETED_TIME]!!,
                livePlants = record[LIVE_PLANTS],
                plantDensity = record[PLANT_DENSITY],
                survivalRate = record[SURVIVAL_RATE],
            )
          }
        }
      }
    }
  }

  companion object {
    fun of(
        record: Record,
        mediaField: Field<List<PublishedActivityMediaModel>>?,
    ): PublishedActivityModel {
      return with(PUBLISHED_ACTIVITIES) {
        PublishedActivityModel(
            activityDate = record[ACTIVITY_DATE]!!,
            activityType = record[ACTIVITY_TYPE_ID]!!,
            description = record[DESCRIPTION],
            id = record[ACTIVITY_ID]!!,
            isHighlight = record[IS_HIGHLIGHT]!!,
            media = mediaField?.let { record[it] } ?: emptyList(),
            observation = Observation.ofOrNull(record),
            projectId = record[PROJECT_ID]!!,
            publishedBy = record[PUBLISHED_ACTIVITIES.PUBLISHED_BY],
            publishedTime = record[PUBLISHED_ACTIVITIES.PUBLISHED_TIME],
        )
      }
    }
  }
}
