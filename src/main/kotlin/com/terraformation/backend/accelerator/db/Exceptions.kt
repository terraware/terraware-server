package com.terraformation.backend.accelerator.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.accelerator.AcceleratorPhase
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId

class ActivityNotFoundException(id: ActivityId) : EntityNotFoundException("Activity $id not found")

class ApplicationNotFoundException(id: ApplicationId) :
    EntityNotFoundException("Application $id not found")

class CannotDeletePublishedActivityException(activityId: ActivityId) :
    MismatchedStateException("Activity $activityId has been published and cannot be deleted")

class CannotUpdatePublishedActivityException(activityId: ActivityId) :
    MismatchedStateException("Activity $activityId has been published and cannot be updated")

class DeliverableNotFoundException(id: DeliverableId) :
    EntityNotFoundException("Deliverable $id not found")

class ModuleNotFoundException(moduleId: ModuleId) :
    EntityNotFoundException("Module $moduleId not found")

class ParticipantProjectSpeciesNotFoundException(id: ParticipantProjectSpeciesId) :
    EntityNotFoundException("Participant Project Species $id not found")

class ParticipantProjectSpeciesProjectNotFoundException(id: ProjectId) :
    EntityNotFoundException("Participant Project Species for project $id not found")

class ProjectApplicationExistsException(projectId: ProjectId) :
    MismatchedStateException("Project $projectId already has an application")

class ProjectApplicationNotFoundException(projectId: ProjectId) :
    MismatchedStateException("Project $projectId has no application")

class ProjectDeliverableNotFoundException(deliverableId: DeliverableId, projectId: ProjectId) :
    EntityNotFoundException("Deliverable $deliverableId not found for project $projectId")

open class ProjectDocumentStorageFailedException(
    projectId: ProjectId,
    message: String = "Project $projectId document storage failed",
    cause: Throwable? = null,
) : Exception(message, cause)

class ProjectDocumentSettingsNotConfiguredException(id: ProjectId) :
    ProjectDocumentStorageFailedException(
        id,
        "Project $id document upload settings have not been configured",
    )

class ProjectModuleNotFoundException(projectId: ProjectId, moduleId: ModuleId) :
    MismatchedStateException("Project $projectId is not associated with module $moduleId")

class ProjectNotInAcceleratorPhaseException(
    id: ProjectId,
    expectedPhase: AcceleratorPhase? = null,
) :
    MismatchedStateException(
        "Project $id is not currently in ${expectedPhase ?: "an accelerator phase"}"
    )

class ProjectVoteNotFoundException(
    projectId: ProjectId,
    phase: AcceleratorPhase? = null,
    userId: UserId? = null,
) :
    EntityNotFoundException(
        "Vote not found for project $projectId, phase ${phase?.id}, user $userId"
    )

class SubmissionDocumentNotFoundException(id: SubmissionDocumentId) :
    EntityNotFoundException("Submission document $id not found")

class SubmissionNotFoundException(id: SubmissionId) :
    EntityNotFoundException("Submission $id not found")

class SubmissionForProjectDeliverableNotFoundException(
    deliverableId: DeliverableId,
    projectId: ProjectId,
) :
    EntityNotFoundException(
        "Submission not found for deliverable $deliverableId and project $projectId"
    )

class SubmissionSnapshotNotFoundException(id: SubmissionId) :
    EntityNotFoundException(
        "Participant Project Species snapshot data for submission $id not found"
    )
