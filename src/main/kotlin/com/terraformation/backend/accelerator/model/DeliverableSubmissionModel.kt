package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import java.net.URI
import java.time.Instant
import java.time.LocalDate

/**
 * The combination of a deliverable for a particular project and possibly one of its submissions.
 */
data class DeliverableSubmissionModel(
    val category: DeliverableCategory,
    val deliverableId: DeliverableId,
    val descriptionHtml: String?,
    val documents: List<SubmissionDocumentModel>,
    val dueDate: LocalDate?,
    val feedback: String?,
    val internalComment: String?,
    val modifiedTime: Instant?,
    val moduleId: ModuleId,
    val moduleName: String,
    val moduleTitle: String?,
    val name: String,
    val organizationId: OrganizationId,
    val organizationName: String,
    val position: Int,
    val projectDealName: String?,
    val projectId: ProjectId,
    val projectName: String,
    val required: Boolean,
    val sensitive: Boolean,
    val status: SubmissionStatus,
    val submissionId: SubmissionId?,
    val templateUrl: URI?,
    val type: DeliverableType,
)
