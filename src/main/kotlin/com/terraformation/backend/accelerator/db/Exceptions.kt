package com.terraformation.backend.accelerator.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId

class CohortNotFoundException(id: CohortId) : EntityNotFoundException("Cohort $id not found")

class CohortHasParticipantsException(id: CohortId) :
    MismatchedStateException("Cohort $id has participants")

class DeliverableNotFoundException(id: DeliverableId) :
    EntityNotFoundException("Deliverable $id not found")

class ModuleNotFoundException(moduleId: ModuleId) :
    EntityNotFoundException("Module $moduleId not found")

class ParticipantHasProjectsException(id: ParticipantId) :
    MismatchedStateException("Participant $id has projects")

class ParticipantNotFoundException(id: ParticipantId) :
    EntityNotFoundException("Participant $id not found")

class ParticipantProjectSpeciesNotFoundException(id: ParticipantProjectSpeciesId) :
    EntityNotFoundException("Participant Project Species $id not found")

class ProjectDocumentSettingsNotConfiguredException(id: ProjectId) :
    MismatchedStateException("Project $id document upload settings have not been configured")

class ProjectNotInCohortException(id: ProjectId) :
    MismatchedStateException("Project $id is not assigned to any cohorts")

class ProjectNotInCohortPhaseException(id: ProjectId, phase: CohortPhase) :
    MismatchedStateException("Project $id is not currently in $phase")

class ProjectNotInParticipantException(id: ProjectId) :
    MismatchedStateException("Project $id is not currently associated to a participant")

class ProjectVoteNotFoundException(
    projectId: ProjectId,
    phase: CohortPhase? = null,
    userId: UserId? = null
) :
    EntityNotFoundException(
        "Vote not found for project $projectId, phase ${phase?.id}, user $userId")

class SubmissionDocumentNotFoundException(id: SubmissionDocumentId) :
    EntityNotFoundException("Submission document $id not found")

class SubmissionNotFoundException(id: SubmissionId) :
    EntityNotFoundException("Submission $id not found")
