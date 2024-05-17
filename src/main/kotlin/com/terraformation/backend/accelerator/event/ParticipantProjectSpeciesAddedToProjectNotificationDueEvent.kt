package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId

/**
 * Published when a participant project species is added to a project This event is a deferred,
 * throttled event and will only be sent out if a subsequent new species has not been added within
 * the defer period
 *
 * @see com.terraformation.backend.accelerator.SpeciesNotifier
 */
data class ParticipantProjectSpeciesAddedToProjectNotificationDueEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
    val speciesId: SpeciesId,
)
