package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ExistingSubmissionModel
import com.terraformation.backend.accelerator.model.SubmissionModel
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.tables.daos.SubmissionsDao
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSION_DOCUMENTS
import com.terraformation.backend.db.asNonNullable
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class SubmissionStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val submissionsDao: SubmissionsDao,
) {
  /** Inserts a new document for a submission. This calculates the filename */
  fun fetchOneById(
      submissionId: SubmissionId,
  ): ExistingSubmissionModel {
    return fetch(SUBMISSIONS.ID.eq(submissionId)).firstOrNull()
        ?: throw SubmissionNotFoundException(submissionId)
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
    }
  }
}
