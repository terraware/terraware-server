package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityStatus
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITIES
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_OBSERVATIONS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITIES
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import java.time.Instant
import java.time.LocalDate
import org.jooq.Field
import org.jooq.Record

data class ExistingActivityModel(
    val activityDate: LocalDate,
    val activityStatus: ActivityStatus,
    val activityType: ActivityType,
    val createdBy: UserId,
    val createdTime: Instant,
    val description: String?,
    val id: ActivityId,
    val isHighlight: Boolean,
    val media: List<ActivityMediaModel> = emptyList(),
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val observation: Observation? = null,
    val projectId: ProjectId,
    val publishedBy: UserId? = null,
    val publishedTime: Instant? = null,
    val verifiedBy: UserId? = null,
    val verifiedTime: Instant? = null,
) {
  data class Observation(
      val isAdHoc: Boolean,
      val monitoringPlotNumber: Long?,
      val observationId: ObservationId,
      val observationType: ObservationType,
  )

  companion object {
    fun of(
        record: Record,
        mediaField: Field<List<ActivityMediaModel>>?,
        monitoringPlotNumberField: Field<Long?>,
    ): ExistingActivityModel {
      return with(ACTIVITIES) {
        val observation =
            record[ACTIVITY_OBSERVATIONS.OBSERVATION_ID]?.let { observationId ->
              Observation(
                  isAdHoc = record[OBSERVATIONS.IS_AD_HOC]!!,
                  monitoringPlotNumber = record[monitoringPlotNumberField],
                  observationId = observationId,
                  observationType = record[OBSERVATIONS.OBSERVATION_TYPE_ID]!!,
              )
            }

        ExistingActivityModel(
            activityDate = record[ACTIVITY_DATE]!!,
            activityStatus = record[ACTIVITY_STATUS_ID]!!,
            activityType = record[ACTIVITY_TYPE_ID]!!,
            createdBy = record[CREATED_BY]!!,
            createdTime = record[CREATED_TIME]!!,
            description = record[DESCRIPTION],
            id = record[ID]!!,
            isHighlight = record[IS_HIGHLIGHT]!!,
            media = mediaField?.let { record[it] } ?: emptyList(),
            modifiedBy = record[MODIFIED_BY]!!,
            modifiedTime = record[MODIFIED_TIME]!!,
            observation = observation,
            projectId = record[PROJECT_ID]!!,
            publishedBy = record[PUBLISHED_ACTIVITIES.PUBLISHED_BY],
            publishedTime = record[PUBLISHED_ACTIVITIES.PUBLISHED_TIME],
            verifiedBy = record[VERIFIED_BY],
            verifiedTime = record[VERIFIED_TIME],
        )
      }
    }
  }
}

data class NewActivityModel(
    val projectId: ProjectId,
    val activityStatus: ActivityStatus = ActivityStatus.NotVerified,
    val activityType: ActivityType,
    val activityDate: LocalDate,
    val description: String?,
    val isHighlight: Boolean = false,
)
