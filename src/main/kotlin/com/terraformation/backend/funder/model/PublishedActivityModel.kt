package com.terraformation.backend.funder.model

import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITIES
import java.time.Instant
import java.time.LocalDate
import org.jooq.Field
import org.jooq.Record

data class PublishedActivityModel(
    val activityDate: LocalDate,
    val activityType: ActivityType,
    val description: String,
    val id: ActivityId,
    val isHighlight: Boolean,
    val media: List<PublishedActivityMediaModel> = emptyList(),
    val projectId: ProjectId,
    val publishedBy: UserId? = null,
    val publishedTime: Instant? = null,
) {
  companion object {
    fun of(
        record: Record,
        mediaField: Field<List<PublishedActivityMediaModel>>?,
    ): PublishedActivityModel {
      return with(PUBLISHED_ACTIVITIES) {
        PublishedActivityModel(
            activityDate = record[ACTIVITY_DATE]!!,
            activityType = record[ACTIVITY_TYPE_ID]!!,
            description = record[DESCRIPTION]!!,
            id = record[ACTIVITY_ID]!!,
            isHighlight = record[IS_HIGHLIGHT]!!,
            media = mediaField?.let { record[it] } ?: emptyList(),
            projectId = record[PROJECT_ID]!!,
            publishedBy = record[PUBLISHED_ACTIVITIES.PUBLISHED_BY],
            publishedTime = record[PUBLISHED_ACTIVITIES.PUBLISHED_TIME],
        )
      }
    }
  }
}
