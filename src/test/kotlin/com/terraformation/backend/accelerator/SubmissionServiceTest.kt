package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ProjectDocumentSettingsNotConfiguredException
import com.terraformation.backend.accelerator.db.ProjectDocumentStorageFailedException
import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadFailedEvent
import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadFailedEvent.FailureReason
import com.terraformation.backend.config.TerrawareServerConfig
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
import io.mockk.verify
import jakarta.ws.rs.core.MediaType
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class SubmissionServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val config: TerrawareServerConfig = mockk()
  private val dropboxWriter: DropboxWriter = mockk()
  private val eventPublisher = TestEventPublisher()
  private val googleDriveWriter: GoogleDriveWriter = mockk()

  private val service: SubmissionService by lazy {
    SubmissionService(clock, config, dropboxWriter, dslContext, eventPublisher, googleDriveWriter)
  }

  private val contentType = MediaType.APPLICATION_OCTET_STREAM
  private val inputStream = byteArrayOf(1, 2, 3).inputStream()
  private val originalName = "file.doc"

  private val fileNaming = "xyz"

  private val googleDriveFolder = "https://drive.google.com/drive/folders/abc"
  private val googleDriveId = "drive"
  private val googleDriveNewFolderId = "xyzzy"
  private val googleDriveNewFolderUrl =
      URI("https://drive.google.com/drive/$googleDriveNewFolderId")
  private val googleDriveParentFolderId = "parent"

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
          FailureReason.ProjectNotConfigured
      )
    }

    @Test
    fun `publishes event and throws exception if file naming not configured`() {
      insertProjectAcceleratorDetails()

      assertEventAndException<ProjectDocumentSettingsNotConfiguredException>(
          FailureReason.FileNamingNotConfigured
      )
    }

    @Test
    fun `publishes event and throws exception if Google folder ID not configured`() {
      every { config.accelerator } returns
          TerrawareServerConfig.AcceleratorConfig(applicationGoogleFolderId = null)

      insertProjectAcceleratorDetails(fileNaming = "xyz")

      assertEventAndException<ProjectDocumentSettingsNotConfiguredException>(
          FailureReason.FolderNotConfigured
      )
    }

    @Test
    fun `publishes event and throws exception if Dropbox URL not configured`() {
      insertProjectAcceleratorDetails(fileNaming = "xyz")
      deliverableId = insertDeliverable(isSensitive = true)

      assertEventAndException<ProjectDocumentSettingsNotConfiguredException>(
          FailureReason.FolderNotConfigured,
          DocumentStore.Dropbox,
      )
    }

    @Test
    fun `creates new Google Drive folder if none exists and deliverable is not sensitive`() {
      insertProjectAcceleratorDetails(fileNaming = fileNaming)

      configureMockGoogleDrive()

      receiveDocument()

      verify(exactly = 1) {
        googleDriveWriter.findOrCreateFolders(
            googleDriveId,
            googleDriveParentFolderId,
            listOf("$fileNaming [Internal]"),
        )
      }

      assertEquals(
          googleDriveNewFolderUrl,
          projectAcceleratorDetailsDao.fetchOneByProjectId(projectId)?.googleFolderUrl,
          "Google folder URL",
      )
    }

    @Test
    fun `truncates filename if it is too long`() {
      insertProjectAcceleratorDetails(fileNaming = fileNaming)

      configureMockGoogleDrive()

      val description = "x".repeat(SubmissionService.MAX_FILENAME_LENGTH * 2)
      receiveDocument(description = description)

      val expectedFileName =
          "Deliverable 1_1970-01-01_${fileNaming}_$description"
              .take(SubmissionService.MAX_FILENAME_LENGTH - 4) + ".doc"

      verify {
        googleDriveWriter.uploadFile(
            any(),
            expectedFileName,
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
        )
      }
    }

    @Test
    fun `creates new Google Drive folder for new application`() {
      insertApplication(internalName = fileNaming)

      configureMockGoogleDrive()

      receiveDocument()

      verify(exactly = 1) {
        googleDriveWriter.findOrCreateFolders(
            googleDriveId,
            googleDriveParentFolderId,
            listOf("$fileNaming [Internal]"),
        )
      }

      assertEquals(
          googleDriveNewFolderUrl,
          projectAcceleratorDetailsDao.fetchOneByProjectId(projectId)?.googleFolderUrl,
          "Google folder URL",
      )
    }

    @Test
    fun `publishes event and throws exception if upload to document store fails`() {
      insertProjectAcceleratorDetails(fileNaming = fileNaming, googleFolderUrl = googleDriveFolder)

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
          "Deliverable 1_1970-01-01_${fileNaming}_description.doc",
          ProjectDocumentStorageFailedException(projectId, cause = exception),
      )
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
        fileName: String? = null,
        exception: E? = null,
    ) {
      assertThrows<E> { receiveDocument() }

      val cause = (exception as? ProjectDocumentStorageFailedException)?.cause ?: exception

      eventPublisher.assertEventPublished(
          DeliverableDocumentUploadFailedEvent(
              deliverableId,
              projectId,
              reason,
              documentStore,
              originalName,
              folder,
              fileName,
              cause,
          )
      )
    }

    private fun receiveDocument(description: String = "description"): SubmissionDocumentId =
        service.receiveDocument(
            inputStream,
            originalName,
            projectId,
            deliverableId,
            description,
            contentType,
        )
  }

  private fun configureMockGoogleDrive() {
    every { config.accelerator } returns
        TerrawareServerConfig.AcceleratorConfig(
            applicationGoogleFolderId = googleDriveParentFolderId
        )
    every {
      googleDriveWriter.findOrCreateFolders(googleDriveId, googleDriveParentFolderId, any())
    } returns googleDriveNewFolderId
    every { googleDriveWriter.getDriveIdForFile(any()) } returns googleDriveId
    every { googleDriveWriter.getFileIdForFolderUrl(googleDriveNewFolderUrl) } returns
        googleDriveNewFolderId
    every { googleDriveWriter.shareFile(googleDriveNewFolderId) } returns googleDriveNewFolderUrl
    every {
      googleDriveWriter.uploadFile(
          googleDriveNewFolderId,
          any(),
          any(),
          any(),
          googleDriveId,
          any(),
          any(),
          any(),
          any(),
      )
    } returns "file"
  }
}
