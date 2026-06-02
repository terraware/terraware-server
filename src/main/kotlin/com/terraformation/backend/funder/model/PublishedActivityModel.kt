package com.terraformation.backend.funder.model

import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITIES
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITY_OBSERVATIONS
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationType
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
      val completedTime: Instant,
      val isAdHoc: Boolean,
      val livePlants: Int?,
      val monitoringPlotNumber: Long?,
      val observationId: ObservationId,
      val observationType: ObservationType,
      val plantDensity: Int?,
      val survivalRate: Int?,
  ) {
    companion object {
      fun ofOrNull(
          record: Record,
          monitoringPlotNumberField: Field<Long?>,
      ): Observation? {
        return with(PUBLISHED_ACTIVITY_OBSERVATIONS) {
          record[OBSERVATION_ID]?.let { observationId ->
            Observation(
                completedTime = record[OBSERVATIONS.COMPLETED_TIME]!!,
                isAdHoc = record[OBSERVATIONS.IS_AD_HOC]!!,
                livePlants = record[LIVE_PLANTS],
                monitoringPlotNumber = record[monitoringPlotNumberField],
                observationId = observationId,
                observationType = record[OBSERVATIONS.OBSERVATION_TYPE_ID]!!,
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
        monitoringPlotNumberField: Field<Long?>,
    ): PublishedActivityModel {
      return with(PUBLISHED_ACTIVITIES) {
        PublishedActivityModel(
            activityDate = record[ACTIVITY_DATE]!!,
            activityType = record[ACTIVITY_TYPE_ID]!!,
            description = record[DESCRIPTION],
            id = record[ACTIVITY_ID]!!,
            isHighlight = record[IS_HIGHLIGHT]!!,
            media = mediaField?.let { record[it] } ?: emptyList(),
            observation = Observation.ofOrNull(record, monitoringPlotNumberField),
            projectId = record[PROJECT_ID]!!,
            publishedBy = record[PUBLISHED_ACTIVITIES.PUBLISHED_BY],
            publishedTime = record[PUBLISHED_ACTIVITIES.PUBLISHED_TIME],
        )
      }
    }
  }
}
