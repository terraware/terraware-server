package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedEvent
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

@Named
class SpeciesSubmissionUpdater(
    private val dslContext: DSLContext,
    private val submissionStore: SubmissionStore,
) {
  /**
   * When a species is added to a deliverable with a submission that is "Approved", it must be reset
   * back to "Not Submitted".
   */
  @EventListener
  fun on(event: ParticipantProjectSpeciesAddedEvent) {
    val deliverableId = event.deliverableId
    val projectId = event.participantProjectSpecies.projectId

    val (feedback, internalComment, submissionStatus) =
        with(SUBMISSIONS) {
          dslContext
              .select(FEEDBACK, INTERNAL_COMMENT, SUBMISSION_STATUS_ID)
              .from(SUBMISSIONS)
              .where(PROJECT_ID.eq(projectId))
              .and(DELIVERABLE_ID.eq(deliverableId))
              .fetchOne()
              /** If there is no submission, there is nothing to do */
              ?: return
        }

    if (submissionStatus == SubmissionStatus.Approved) {
      submissionStore.updateSubmissionStatus(
          deliverableId,
          projectId,
          SubmissionStatus.NotSubmitted,
          feedback,
          internalComment,
      )
    }
  }
}
