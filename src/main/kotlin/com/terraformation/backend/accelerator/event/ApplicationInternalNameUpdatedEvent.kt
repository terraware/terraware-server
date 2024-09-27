package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.ApplicationId

data class ApplicationInternalNameUpdatedEvent(val applicationId: ApplicationId)
