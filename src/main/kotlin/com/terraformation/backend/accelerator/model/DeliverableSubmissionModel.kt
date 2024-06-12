package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import java.net.URI
import java.time.LocalDate

/**
 * The combination of a deliverable for a particular project and possibly one of its submissions.
 */
data class DeliverableSubmissionModel(
    val deliverableId: DeliverableId,
    val submissionId: SubmissionId?,
    val category: DeliverableCategory,
    val descriptionHtml: String?,
    val documents: List<SubmissionDocumentModel>,
    val dueDate: LocalDate,
    val feedback: String?,
    val internalComment: String?,
    val moduleId: ModuleId,
    val moduleName: String,
    val moduleTitle: String,
    val name: String,
    val organizationId: OrganizationId,
    val organizationName: String,
    val participantId: ParticipantId,
    val participantName: String,
    val projectId: ProjectId,
    val projectName: String,
    val status: SubmissionStatus,
    val templateUrl: URI?,
    val type: DeliverableType,
)
