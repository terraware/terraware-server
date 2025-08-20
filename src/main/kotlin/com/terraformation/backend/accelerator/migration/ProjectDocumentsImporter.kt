package com.terraformation.backend.accelerator.migration

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSION_DOCUMENTS
import com.terraformation.backend.importer.processCsvFile
import jakarta.inject.Named
import java.io.InputStream
import java.net.URI
import java.time.InstantSource
import org.jooq.DSLContext

@Named
class ProjectDocumentsImporter(
    private val applicationStore: ApplicationStore,
    private val clock: InstantSource,
    private val deliverableStore: DeliverableStore,
    private val dslContext: DSLContext,
    private val systemUser: SystemUser,
) {
  companion object {
    const val COLUMN_DEAL_NAME = 0
    const val COLUMN_DELIVERABLE_ID = 1
    const val COLUMN_URL = 2
    const val COLUMN_DESCRIPTION = 3
    const val COLUMN_DOCUMENT_NAME = 4
  }

  fun importCsv(inputStream: InputStream) {
    requirePermissions { createEntityWithOwner(systemUser.userId) }

    systemUser.run {
      dslContext.transaction { _ ->
        processCsvFile(inputStream) { values, _, addError ->
          try {
            val dealName = values[COLUMN_DEAL_NAME] ?: throw ImportError("Missing deal name")
            val deliverableId =
                values[COLUMN_DELIVERABLE_ID]?.let { DeliverableId(it) }
                    ?: throw ImportError("Missing deliverable ID")
            val url = values[COLUMN_URL] ?: throw ImportError("Missing URL")
            val description = values[COLUMN_DESCRIPTION]
            val name = values[COLUMN_DOCUMENT_NAME] ?: throw ImportError("Missing document name")

            val application =
                applicationStore.fetchOneByInternalName(dealName)
                    ?: throw ImportError(
                        "Deal $dealName has not been imported yet; import the project setup sheet first."
                    )

            if (!deliverableStore.deliverableIdExists(deliverableId)) {
              throw ImportError("Deliverable $deliverableId does not exist")
            }

            // This will throw an exception if the URL isn't syntactically valid
            URI.create(url)

            val now = clock.instant()

            val submissionId =
                with(SUBMISSIONS) {
                  dslContext
                      .insertInto(SUBMISSIONS)
                      .set(CREATED_BY, systemUser.userId)
                      .set(CREATED_TIME, now)
                      .set(DELIVERABLE_ID, deliverableId)
                      .set(MODIFIED_BY, systemUser.userId)
                      .set(MODIFIED_TIME, now)
                      .set(PROJECT_ID, application.projectId)
                      .set(SUBMISSION_STATUS_ID, SubmissionStatus.Approved)
                      .onConflict(PROJECT_ID, DELIVERABLE_ID)
                      .doUpdate()
                      .set(MODIFIED_BY, systemUser.userId)
                      .set(MODIFIED_TIME, now)
                      .set(SUBMISSION_STATUS_ID, SubmissionStatus.Approved)
                      .returning(SUBMISSIONS.ID)
                      .fetchOne { it[SUBMISSIONS.ID] }!!
                }

            with(SUBMISSION_DOCUMENTS) {
              dslContext
                  .insertInto(SUBMISSION_DOCUMENTS)
                  .set(CREATED_BY, systemUser.userId)
                  .set(CREATED_TIME, now)
                  .set(DESCRIPTION, description)
                  .set(DOCUMENT_STORE_ID, DocumentStore.External)
                  .set(LOCATION, url)
                  .set(NAME, name)
                  .set(ORIGINAL_NAME, name)
                  .set(PROJECT_ID, application.projectId)
                  .set(SUBMISSION_ID, submissionId)
                  .onConflict(PROJECT_ID, NAME)
                  .doUpdate()
                  .set(DESCRIPTION, description)
                  .set(LOCATION, url)
                  .execute()
            }
          } catch (e: Exception) {
            addError(e.message ?: e.toString())
          }
        }
      }
    }
  }

  private class ImportError(message: String) : Exception(message)
}
