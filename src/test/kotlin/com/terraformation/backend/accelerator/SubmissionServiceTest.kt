package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ProjectDocumentSettingsNotConfiguredException
import com.terraformation.backend.accelerator.db.ProjectDocumentStorageFailedException
import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadFailedEvent
import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadFailedEvent.FailureReason
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.file.DropboxWriter
import com.terraformation.backend.file.GoogleDriveWriter
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.MediaType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class SubmissionServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val dropboxWriter: DropboxWriter = mockk()
  private val eventPublisher = TestEventPublisher()
  private val googleDriveWriter: GoogleDriveWriter = mockk()

  private val service: SubmissionService by lazy {
    SubmissionService(clock, dropboxWriter, dslContext, eventPublisher, googleDriveWriter)
  }

  private val contentType = MediaType.APPLICATION_OCTET_STREAM
  private val description = "description"
  private val googleDriveFolder = "https://drive.google.com/drive/folders/abc"
  private val inputStream = byteArrayOf(1, 2, 3).inputStream()
  private val originalName = "file.doc"

  private lateinit var deliverableId: DeliverableId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    projectId = insertProject()
    insertModule()
    deliverableId = insertDeliverable()

    every { user.canCreateSubmission(any()) } returns true
  }

  @Nested
  inner class ReceiveDocument {
    @Test
    fun `publishes event and throws exception if project accelerator details not configured`() {
      assertEventAndException<ProjectDocumentSettingsNotConfiguredException>(
          FailureReason.ProjectNotConfigured)
    }

    @Test
    fun `publishes event and throws exception if file naming not configured`() {
      insertProjectAcceleratorDetails()

      assertEventAndException<ProjectDocumentSettingsNotConfiguredException>(
          FailureReason.FileNamingNotConfigured)
    }

    @Test
    fun `publishes event and throws exception if Google folder ID not configured`() {
      insertProjectAcceleratorDetails(fileNaming = "xyz")

      assertEventAndException<ProjectDocumentSettingsNotConfiguredException>(
          FailureReason.FolderNotConfigured)
    }

    @Test
    fun `publishes event and throws exception if Dropbox URL not configured`() {
      insertProjectAcceleratorDetails(fileNaming = "xyz")
      deliverableId = insertDeliverable(isSensitive = true)

      assertEventAndException<ProjectDocumentSettingsNotConfiguredException>(
          FailureReason.FolderNotConfigured, DocumentStore.Dropbox)
    }

    @Test
    fun `publishes event and throws exception if upload to document store fails`() {
      insertProjectAcceleratorDetails(fileNaming = "xyz", googleFolderUrl = googleDriveFolder)

      val exception = IllegalStateException("Failure")

      every { googleDriveWriter.getFileIdForFolderUrl(any()) } returns "abc"
      every { googleDriveWriter.getDriveIdForFile(any()) } returns "def"
      every {
        googleDriveWriter.uploadFile(any(), any(), any(), any(), any(), any(), any(), any(), any())
      } throws exception

      assertEventAndException(
          FailureReason.CouldNotUpload,
          DocumentStore.Google,
          googleDriveFolder,
          ProjectDocumentStorageFailedException(projectId, cause = exception))
    }

    @Test
    fun `throws exception if no permission to create submission`() {
      every { user.canCreateSubmission(any()) } returns false
      every { user.canReadProject(any()) } returns true

      assertThrows<AccessDeniedException> { receiveDocument() }
    }

    private inline fun <reified E : Exception> assertEventAndException(
        reason: FailureReason,
        documentStore: DocumentStore = DocumentStore.Google,
        folder: String? = null,
        exception: E? = null,
    ) {
      assertThrows<E> { receiveDocument() }

      val cause = (exception as? ProjectDocumentStorageFailedException)?.cause ?: exception

      eventPublisher.assertEventPublished(
          DeliverableDocumentUploadFailedEvent(
              deliverableId, projectId, reason, documentStore, originalName, folder, cause))
    }

    private fun receiveDocument(): SubmissionDocumentId =
        service.receiveDocument(
            inputStream, originalName, projectId, deliverableId, description, contentType)
  }
}
