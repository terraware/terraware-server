package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.default_schema.FileId

/** Published when an activity media file's information is updated by a user. */
data class ActivityMediaUpdatedEvent(
    val activityId: ActivityId,
    val activityType: ActivityType,
    val caption: String?,
    val fileId: FileId,
    /**
     * Application event that triggered this update; null if the update is due to a user directly
     * editing the activity media file. This is used to prevent infinite loops when event listeners
     * need to keep activity media synced with non-activity data sources.
     */
    val triggeredBy: Any?,
)
