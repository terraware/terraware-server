package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.ActivityId

/** Published when a new activity is created for an accelerator project. */
data class ActivityCreatedEvent(val activityId: ActivityId)
