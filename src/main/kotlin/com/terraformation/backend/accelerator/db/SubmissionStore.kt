package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.DeliverableStatusUpdatedEvent
import com.terraformation.backend.accelerator.model.ExistingSubmissionModel
import com.terraformation.backend.accelerator.model.SubmissionModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.records.SubmissionsRecord
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSION_DOCUMENTS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class SubmissionStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
) {
  fun fetchOneById(
      submissionId: SubmissionId,
  ): ExistingSubmissionModel {
    return fetch(SUBMISSIONS.ID.eq(submissionId)).firstOrNull()
        ?: throw SubmissionNotFoundException(submissionId)
  }

  fun updateSubmissionStatus(
      deliverableId: DeliverableId,
      projectId: ProjectId,
      status: SubmissionStatus,
      feedback: String? = null,
      internalComment: String? = null,
  ): SubmissionId {
    requirePermissions { updateSubmissionStatus(deliverableId, projectId) }

    val now = clock.instant()

    val submission: SubmissionsRecord =
        dslContext
            .selectFrom(SUBMISSIONS)
            .where(SUBMISSIONS.DELIVERABLE_ID.eq(deliverableId))
            .and(SUBMISSIONS.PROJECT_ID.eq(projectId))
            .fetchOne()
            ?: SubmissionsRecord(
                    projectId = projectId,
                    deliverableId = deliverableId,
                    submissionStatusId = SubmissionStatus.NotSubmitted,
                    createdBy = currentUser().userId,
                    createdTime = now,
                )
                .also { it.attach(dslContext.configuration()) }

    val oldStatus = submission.submissionStatusId!!

    submission.feedback = feedback
    submission.internalComment = internalComment
    submission.modifiedBy = currentUser().userId
    submission.modifiedTime = now
    submission.submissionStatusId = status

    submission.store()

    if (oldStatus != status) {
      eventPublisher.publishEvent(
          DeliverableStatusUpdatedEvent(deliverableId, projectId, oldStatus, status))
    }

    return submission.id!!
  }

  private fun fetch(condition: Condition?): List<ExistingSubmissionModel> {
    val submissionDocumentsIdsField =
        DSL.multiset(
                DSL.select(SUBMISSION_DOCUMENTS.ID)
                    .from(SUBMISSION_DOCUMENTS)
                    .where(SUBMISSION_DOCUMENTS.SUBMISSION_ID.eq(SUBMISSIONS.ID))
                    .orderBy(SUBMISSION_DOCUMENTS.ID))
            .convertFrom { result ->
              result.map { it[SUBMISSION_DOCUMENTS.ID.asNonNullable()] }.toSet()
            }

    return with(SUBMISSIONS) {
      dslContext
          .select(SUBMISSIONS.asterisk(), submissionDocumentsIdsField)
          .from(SUBMISSIONS)
          .apply { condition?.let { where(it) } }
          .orderBy(ID)
          .fetch { SubmissionModel.of(it, submissionDocumentsIdsField) }
          .filter { currentUser().canReadSubmission(it.id) }
    }
  }
}
