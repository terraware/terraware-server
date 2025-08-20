package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import org.jooq.Record

data class ExistingSpeciesDeliverableSubmissionModel(
    val deliverableId: DeliverableId,
    val submissionId: SubmissionId? = null,
) {
  companion object {
    fun of(record: Record): ExistingSpeciesDeliverableSubmissionModel {
      return ExistingSpeciesDeliverableSubmissionModel(
          deliverableId = record[DELIVERABLES.ID]!!,
          submissionId = record[SUBMISSIONS.ID],
      )
    }
  }
}
