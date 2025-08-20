package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.default_schema.ProjectId
import org.jooq.Field
import org.jooq.Record

data class SubmissionModel<ID : SubmissionId?>(
    val id: ID,
    val deliverableId: DeliverableId,
    val feedback: String? = null,
    val internalComment: String? = null,
    val projectId: ProjectId,
    val submissionDocumentIds: Set<SubmissionDocumentId>,
    val submissionStatus: SubmissionStatus,
) {
  companion object {
    fun of(
        record: Record,
        submissionDocumentIds: Field<Set<SubmissionDocumentId>>? = null,
    ): ExistingSubmissionModel {
      return ExistingSubmissionModel(
          id = record[SUBMISSIONS.ID]!!,
          deliverableId = record[SUBMISSIONS.DELIVERABLE_ID]!!,
          feedback = record[SUBMISSIONS.FEEDBACK],
          internalComment = record[SUBMISSIONS.INTERNAL_COMMENT],
          projectId = record[SUBMISSIONS.PROJECT_ID]!!,
          submissionDocumentIds = submissionDocumentIds?.let { record[it] } ?: emptySet(),
          submissionStatus = record[SUBMISSIONS.SUBMISSION_STATUS_ID]!!,
      )
    }
  }
}

typealias ExistingSubmissionModel = SubmissionModel<SubmissionId>
