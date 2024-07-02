package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.default_schema.ProjectId

/** Published when a new document is uploaded for a deliverable. */
data class DeliverableDocumentUploadedEvent(
    val deliverableId: DeliverableId,
    val documentId: SubmissionDocumentId,
    val projectId: ProjectId,
)
