package com.terraformation.backend.accelerator.event

import com.terraformation.backend.accelerator.model.ExternalApplicationStatus
import com.terraformation.backend.db.accelerator.ApplicationId

/** Published when an Application is reviewed with an applicant-visible status change. */
data class ApplicationReviewedEvent(
    val applicationId: ApplicationId,
    val applicationStatus: ExternalApplicationStatus,
)
