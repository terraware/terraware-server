package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.ProjectId
import java.time.Instant

/** Published when a species associated to a project's species list deliverable is edited */
data class DeliverableSpeciesEditedEvent(
    val deliverableId: DeliverableId,
    val modifiedTime: Instant,
    val newSubmissionStatus: SubmissionStatus,
    val oldSubmissionStatus: SubmissionStatus,
    val projectId: ProjectId,
) {
  /**
   * Returns true if the transition is visible to end users, or false if it is a transition between
   * internally-visible statuses that are presented as the same status to users.
   */
  fun isUserVisible(): Boolean {
    return when (oldSubmissionStatus) {
      SubmissionStatus.InReview ->
          when (newSubmissionStatus) {
            SubmissionStatus.NeedsTranslation -> false
            else -> true
          }
      SubmissionStatus.NeedsTranslation ->
          when (newSubmissionStatus) {
            SubmissionStatus.InReview -> false
            else -> true
          }
      else -> true
    }
  }
}
