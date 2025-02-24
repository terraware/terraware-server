package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSION_DOCUMENTS
import java.time.Instant
import org.jooq.Record

data class SubmissionDocumentModel(
    val createdTime: Instant,
    val description: String?,
    val documentStore: DocumentStore,
    val id: SubmissionDocumentId,
    val location: String,
    val name: String,
    val originalName: String?,
) {
  companion object {
    fun of(record: Record): SubmissionDocumentModel {
      return SubmissionDocumentModel(
          record[SUBMISSION_DOCUMENTS.CREATED_TIME]!!,
          record[SUBMISSION_DOCUMENTS.DESCRIPTION],
          record[SUBMISSION_DOCUMENTS.DOCUMENT_STORE_ID]!!,
          record[SUBMISSION_DOCUMENTS.ID]!!,
          record[SUBMISSION_DOCUMENTS.LOCATION]!!,
          record[SUBMISSION_DOCUMENTS.NAME]!!,
          record[SUBMISSION_DOCUMENTS.ORIGINAL_NAME],
      )
    }
  }
}
