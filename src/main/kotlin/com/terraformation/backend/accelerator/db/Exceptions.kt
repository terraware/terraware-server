package com.terraformation.backend.accelerator.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.default_schema.ProjectId

class CohortNotFoundException(id: CohortId) : EntityNotFoundException("Cohort $id not found")

class CohortHasParticipantsException(id: CohortId) :
    MismatchedStateException("Cohort $id has participants")

class DeliverableNotFoundException(id: DeliverableId) :
    EntityNotFoundException("Deliverable $id not found")

class ParticipantHasProjectsException(id: ParticipantId) :
    MismatchedStateException("Participant $id has projects")

class ParticipantNotFoundException(id: ParticipantId) :
    EntityNotFoundException("Participant $id not found")

class ProjectDocumentSettingsNotConfiguredException(id: ProjectId) :
    MismatchedStateException("Project $id document upload settings have not been configured")
