package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.ActivityId

/** Published in an open database transaction when an activity is about to be deleted. */
data class ActivityDeletionStartedEvent(val activityId: ActivityId)
