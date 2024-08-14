package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.default_schema.ProjectId

/**
 * Published when the user attempts to upload a document for a document deliverable but the upload
 * fails due to an error on our side.
 */
data class DeliverableDocumentUploadFailedEvent(
    val deliverableId: DeliverableId,
    val projectId: ProjectId,
    val reason: FailureReason,
    /** Which document store the file would have been saved to, or null if none is configured. */
    val documentStore: DocumentStore,
    /** The user-supplied name of the uploaded file, if any. */
    val originalName: String?,
    /** Which folder on the document store would have been used, or null if none is configured. */
    val documentStoreFolder: String? = null,
    /** Exception that caused the failure, if any. */
    val exception: Exception? = null,
) {
  /**
   * Why the upload failed. The descriptions here are included in the support ticket that gets filed
   * about the failure.
   */
  enum class FailureReason(val description: String) {
    ProjectNotConfigured("Project document settings are not configured"),
    FileNamingNotConfigured("File naming is not configured for project"),
    FolderNotConfigured("Upload folder is not configured for project"),
    CouldNotUpload("Could not upload to the configured folder"),
    CouldNotRename("Could not rename uploaded document"),
  }
}
