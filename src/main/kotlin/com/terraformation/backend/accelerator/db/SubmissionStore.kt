package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.DeliverableStatusUpdatedEvent
import com.terraformation.backend.accelerator.model.ExistingSpeciesDeliverableSubmissionModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.records.SubmissionsRecord
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.attach
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.i18n.TimeZones
import jakarta.inject.Named
import java.time.InstantSource
import java.time.LocalDate
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class SubmissionStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
) {
  fun createSubmission(
      deliverableId: DeliverableId,
      projectId: ProjectId,
      status: SubmissionStatus = SubmissionStatus.NotSubmitted,
  ): SubmissionId {
    requirePermissions { createSubmission(projectId) }

    if (
        status != SubmissionStatus.NotSubmitted &&
            status != SubmissionStatus.Completed &&
            status != SubmissionStatus.InReview
    ) {
      throw IllegalArgumentException("Cannot create submissions in $status")
    }

    val now = clock.instant()
    val userId = currentUser().userId

    val result =
        dslContext
            .insertInto(SUBMISSIONS)
            .set(SUBMISSIONS.CREATED_BY, userId)
            .set(SUBMISSIONS.CREATED_TIME, now)
            .set(SUBMISSIONS.DELIVERABLE_ID, deliverableId)
            .set(SUBMISSIONS.MODIFIED_BY, userId)
            .set(SUBMISSIONS.MODIFIED_TIME, now)
            .set(SUBMISSIONS.PROJECT_ID, projectId)
            .set(SUBMISSIONS.SUBMISSION_STATUS_ID, status)
            .onConflict(SUBMISSIONS.PROJECT_ID, SUBMISSIONS.DELIVERABLE_ID)
            .doUpdate()
            .set(SUBMISSIONS.MODIFIED_BY, userId)
            .set(SUBMISSIONS.MODIFIED_TIME, now)
            .set(SUBMISSIONS.SUBMISSION_STATUS_ID, status)
            .returning(SUBMISSIONS.ID)
            .fetchOne { it[SUBMISSIONS.ID] }

    return result ?: throw ProjectDeliverableNotFoundException(deliverableId, projectId)
  }

  fun fetchMostRecentSpeciesDeliverableSubmission(
      projectId: ProjectId,
  ): ExistingSpeciesDeliverableSubmissionModel? {
    requirePermissions { readProjectDeliverables(projectId) }

    val today = LocalDate.ofInstant(clock.instant(), TimeZones.UTC)

    val submission =
        dslContext
            .select(DELIVERABLES.ID, SUBMISSIONS.ID)
            .from(DELIVERABLES)
            .join(MODULES)
            .on(DELIVERABLES.MODULE_ID.eq(MODULES.ID))
            .join(PROJECT_MODULES)
            .on(MODULES.ID.eq(PROJECT_MODULES.MODULE_ID))
            .join(PROJECTS)
            .on(PROJECT_MODULES.PROJECT_ID.eq(PROJECTS.ID))
            .leftJoin(SUBMISSIONS)
            .on(
                DELIVERABLES.ID.eq(SUBMISSIONS.DELIVERABLE_ID),
                PROJECTS.ID.eq(SUBMISSIONS.PROJECT_ID),
            )
            .where(PROJECT_MODULES.START_DATE.lessOrEqual(today))
            .and(PROJECTS.ID.eq(projectId))
            .and(DELIVERABLES.DELIVERABLE_TYPE_ID.eq(DeliverableType.Species))
            .orderBy(PROJECT_MODULES.END_DATE.desc())
            .limit(1)
            .fetchOne { ExistingSpeciesDeliverableSubmissionModel.of(it) }

    if (submission?.submissionId != null) {
      requirePermissions { readSubmission(submission.submissionId) }
    }

    return submission
  }

  /**
   * Returns true if all the required deliverables in the same module as the specified one are
   * marked as completed for a project.
   */
  fun moduleDeliverablesAllCompleted(deliverableId: DeliverableId, projectId: ProjectId): Boolean {
    requirePermissions { readProjectDeliverables(projectId) }

    val hasIncompleteDeliverables =
        dslContext.fetchExists(
            DSL.selectOne()
                .from(DELIVERABLES)
                .where(
                    DELIVERABLES.MODULE_ID.eq(
                        DSL.select(DELIVERABLES.MODULE_ID)
                            .from(DELIVERABLES)
                            .where(DELIVERABLES.ID.eq(deliverableId))
                    )
                )
                .and(DELIVERABLES.IS_REQUIRED)
                .andNotExists(
                    DSL.selectOne()
                        .from(SUBMISSIONS)
                        .where(SUBMISSIONS.PROJECT_ID.eq(projectId))
                        .and(SUBMISSIONS.DELIVERABLE_ID.eq(DELIVERABLES.ID))
                        .and(SUBMISSIONS.SUBMISSION_STATUS_ID.eq(SubmissionStatus.Completed))
                )
        )

    return !hasIncompleteDeliverables
  }

  fun updateSubmissionStatus(
      deliverableId: DeliverableId,
      projectId: ProjectId,
      status: SubmissionStatus,
      feedback: String? = null,
      internalComment: String? = null,
  ): SubmissionId {
    requirePermissions {
      // Non-admin user actions can cause status to be reset to Not Submitted.
      if (status == SubmissionStatus.NotSubmitted) {
        createSubmission(projectId)
      } else {
        updateSubmissionStatus(deliverableId, projectId)
      }
    }

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
                .attach(dslContext)

    val oldStatus = submission.submissionStatusId!!

    submission.feedback = feedback
    submission.internalComment = internalComment
    submission.modifiedBy = currentUser().userId
    submission.modifiedTime = now
    submission.submissionStatusId = status

    submission.store()

    if (oldStatus != status) {
      eventPublisher.publishEvent(
          DeliverableStatusUpdatedEvent(
              deliverableId,
              projectId,
              oldStatus,
              status,
              submission.id!!,
          )
      )
    }

    return submission.id!!
  }
}
