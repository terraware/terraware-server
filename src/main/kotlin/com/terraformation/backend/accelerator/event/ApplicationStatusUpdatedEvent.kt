package com.terraformation.backend.accelerator.event

import com.terraformation.backend.accelerator.model.ExternalApplicationStatus
import com.terraformation.backend.db.accelerator.ApplicationId

/** Published when an Application status update is confirmed and ready to be notified. */
data class ApplicationStatusUpdatedEvent(
    val applicationId: ApplicationId,
    val applicationStatus: ExternalApplicationStatus,
)
