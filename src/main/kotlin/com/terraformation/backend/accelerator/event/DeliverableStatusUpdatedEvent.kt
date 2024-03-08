package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.ProjectId

/** Published when a deliverable status is updated */
data class DeliverableStatusUpdatedEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
    val oldStatus: SubmissionStatus,
    val newStatus: SubmissionStatus,
) {
  /**
   * Returns true if the transition is visible to end users, or false if it is a transition between
   * internally-visible statuses that are presented as the same status to users.
   */
  fun isUserVisible(): Boolean {
    return when (oldStatus) {
      SubmissionStatus.InReview ->
          when (newStatus) {
            SubmissionStatus.NeedsTranslation -> false
            else -> true
          }
      SubmissionStatus.NeedsTranslation ->
          when (newStatus) {
            SubmissionStatus.InReview -> false
            else -> true
          }
      else -> true
    }
  }
}
