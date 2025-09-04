package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITIES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import java.time.LocalDate
import org.jooq.Record

data class ExistingActivityModel(
    val activityDate: LocalDate,
    val activityType: ActivityType,
    val createdBy: UserId,
    val createdTime: Instant,
    val description: String?,
    val id: ActivityId,
    val isHighlight: Boolean,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val projectId: ProjectId,
    val verifiedBy: UserId? = null,
    val verifiedTime: Instant? = null,
    val isVerified: Boolean = verifiedBy != null,
) {
  companion object {
    fun of(record: Record): ExistingActivityModel {
      return with(ACTIVITIES) {
        ExistingActivityModel(
            activityDate = record[ACTIVITY_DATE]!!,
            activityType = record[ACTIVITY_TYPE_ID]!!,
            createdBy = record[CREATED_BY]!!,
            createdTime = record[CREATED_TIME]!!,
            description = record[DESCRIPTION],
            id = record[ID]!!,
            isHighlight = record[IS_HIGHLIGHT]!!,
            modifiedBy = record[MODIFIED_BY]!!,
            modifiedTime = record[MODIFIED_TIME]!!,
            projectId = record[PROJECT_ID]!!,
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
    val description: String? = null,
)
