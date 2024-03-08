package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ExistingSubmissionDocumentModel
import com.terraformation.backend.accelerator.model.SubmissionDocumentModel
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSION_DOCUMENTS
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class SubmissionDocumentStore(
    private val dslContext: DSLContext,
) {
  fun fetchOneById(
      submissionDocumentId: SubmissionDocumentId,
  ): ExistingSubmissionDocumentModel {
    return fetch(SUBMISSION_DOCUMENTS.ID.eq(submissionDocumentId)).firstOrNull()
        ?: throw SubmissionDocumentNotFoundException(submissionDocumentId)
  }

  private fun fetch(condition: Condition?): List<ExistingSubmissionDocumentModel> {
    return with(SUBMISSION_DOCUMENTS) {
      dslContext
          .select(SUBMISSION_DOCUMENTS.asterisk())
          .from(SUBMISSION_DOCUMENTS)
          .apply { condition?.let { where(it) } }
          .orderBy(ID)
          .fetch { SubmissionDocumentModel.of(it) }
      // TODO
      // .filter { currentUser().canReadSubmissionDocument(it.id) }
    }
  }
}
