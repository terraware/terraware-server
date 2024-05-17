package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId

/**
 * Published when an approved participant project species is edited This event is a deferred,
 * throttled event and will only be sent out if a subsequent qualifying species edit has not been
 * made within the defer period
 *
 * @see com.terraformation.backend.accelerator.SpeciesNotifier
 */
data class ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
    val speciesId: SpeciesId,
)
