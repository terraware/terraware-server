package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.OrganizationId

/** Published when a deliverable status is updated */
data class DeliverableStatusUpdatedEvent(
    val deliverableId: DeliverableId,
    val organizationId: OrganizationId,
)
