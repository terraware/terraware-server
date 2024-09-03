package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.ApplicationId
import java.time.Instant

data class ApplicationSubmittedEvent(
    val applicationId: ApplicationId,
    val time: Instant,
)
