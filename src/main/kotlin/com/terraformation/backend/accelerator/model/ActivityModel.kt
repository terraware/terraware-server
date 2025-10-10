package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityStatus
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITIES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITIES
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
    val description: String,
    val id: ActivityId,
    val isHighlight: Boolean,
    val media: List<ActivityMediaModel> = emptyList(),
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val projectId: ProjectId,
    val publishedBy: UserId? = null,
    val publishedTime: Instant? = null,
    val verifiedBy: UserId? = null,
    val verifiedTime: Instant? = null,
) {
  companion object {
    fun of(
        record: Record,
        mediaField: Field<List<ActivityMediaModel>>?,
    ): ExistingActivityModel {
      return with(ACTIVITIES) {
        ExistingActivityModel(
            activityDate = record[ACTIVITY_DATE]!!,
            activityStatus = record[ACTIVITY_STATUS_ID]!!,
            activityType = record[ACTIVITY_TYPE_ID]!!,
            createdBy = record[CREATED_BY]!!,
            createdTime = record[CREATED_TIME]!!,
            description = record[DESCRIPTION]!!,
            id = record[ID]!!,
            isHighlight = record[IS_HIGHLIGHT]!!,
            media = mediaField?.let { record[it] } ?: emptyList(),
            modifiedBy = record[MODIFIED_BY]!!,
            modifiedTime = record[MODIFIED_TIME]!!,
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
    val activityType: ActivityType,
    val activityDate: LocalDate,
    val description: String,
    val isHighlight: Boolean = false,
    val isVerified: Boolean = false,
)
