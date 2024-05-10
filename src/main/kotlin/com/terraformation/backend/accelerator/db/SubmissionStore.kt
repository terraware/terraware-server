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
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.attach
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.i18n.TimeZones
import jakarta.inject.Named
import java.time.InstantSource
import java.time.LocalDate
import org.jooq.DSLContext
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
  ) {
    requirePermissions { createSubmission(projectId) }

    val now = clock.instant()
    val userId = currentUser().userId

    dslContext
        .insertInto(SUBMISSIONS)
        .set(SUBMISSIONS.CREATED_BY, userId)
        .set(SUBMISSIONS.CREATED_TIME, now)
        .set(SUBMISSIONS.DELIVERABLE_ID, deliverableId)
        .set(SUBMISSIONS.MODIFIED_BY, userId)
        .set(SUBMISSIONS.MODIFIED_TIME, now)
        .set(SUBMISSIONS.PROJECT_ID, projectId)
        .set(SUBMISSIONS.SUBMISSION_STATUS_ID, SubmissionStatus.NotSubmitted)
        .onConflict()
        .doUpdate()
        .set(SUBMISSIONS.MODIFIED_BY, userId)
        .set(SUBMISSIONS.MODIFIED_TIME, now)
        .set(SUBMISSIONS.SUBMISSION_STATUS_ID, SubmissionStatus.NotSubmitted)
        .execute()
  }

  fun fetchActiveSpeciesDeliverableSubmission(
      projectId: ProjectId,
  ): ExistingSpeciesDeliverableSubmissionModel {
    requirePermissions { readProjectDeliverables(projectId) }

    val today = LocalDate.ofInstant(clock.instant(), TimeZones.UTC)

    return dslContext
        .select(DELIVERABLES.ID, SUBMISSIONS.ID)
        .from(DELIVERABLES)
        .join(MODULES)
        .on(DELIVERABLES.MODULE_ID.eq(MODULES.ID))
        .join(COHORT_MODULES)
        .on(MODULES.ID.eq(COHORT_MODULES.MODULE_ID))
        .join(PARTICIPANTS)
        .on(COHORT_MODULES.COHORT_ID.eq(PARTICIPANTS.COHORT_ID))
        .join(PROJECTS)
        .on(PARTICIPANTS.ID.eq(PROJECTS.PARTICIPANT_ID))
        .fullOuterJoin(SUBMISSIONS)
        .on(DELIVERABLES.ID.eq(SUBMISSIONS.DELIVERABLE_ID), PROJECTS.ID.eq(SUBMISSIONS.PROJECT_ID))
        .join(ORGANIZATIONS)
        .on(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
        .where(COHORT_MODULES.START_DATE.lessOrEqual(today))
        .and(COHORT_MODULES.END_DATE.greaterOrEqual(today))
        .and(PROJECTS.ID.eq(projectId))
        .and(DELIVERABLES.DELIVERABLE_TYPE_ID.eq(DeliverableType.Species))
        .orderBy(DELIVERABLES.ID, PROJECTS.ID)
        .fetchOne { ExistingSpeciesDeliverableSubmissionModel.of(it) }
        ?: throw SpeciesDeliverableNotFoundException(projectId)
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
          DeliverableStatusUpdatedEvent(deliverableId, projectId, oldStatus, status))
    }

    return submission.id!!
  }
}
