package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ProjectDocumentSettingsStore
import com.terraformation.backend.accelerator.db.SubmissionDocumentStore
import com.terraformation.backend.accelerator.document.StoredFile
import com.terraformation.backend.accelerator.document.SubmissionDocumentReceiver
import com.terraformation.backend.accelerator.model.ExistingDeliverableModel
import com.terraformation.backend.accelerator.model.ExistingProjectDocumentSettingsModel
import com.terraformation.backend.accelerator.model.ExistingSubmissionDocumentModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.file.DropboxWriter
import com.terraformation.backend.file.GoogleDriveWriter
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.net.URI
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

class SubmissionServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()

  private val dropboxWriter: DropboxWriter = mockk()
  private val dropboxReceiver: SubmissionDocumentReceiver = mockk()
  private val googleDriveWriter: GoogleDriveWriter = mockk()
  private val googleDriveReceiver: SubmissionDocumentReceiver = mockk()

  private val deliverableStore: DeliverableStore by lazy { DeliverableStore(dslContext) }
  private val projectDocumentSettingsStore: ProjectDocumentSettingsStore by lazy {
    ProjectDocumentSettingsStore(dslContext)
  }
  private val submissionDocumentStore: SubmissionDocumentStore by lazy {
    SubmissionDocumentStore(dslContext)
  }

  private val service: SubmissionService by lazy {
    SubmissionService(
        clock,
        dropboxWriter,
        dslContext,
        googleDriveWriter,
        deliverableStore,
        projectDocumentSettingsStore)
  }

  private val inputStream = ByteArrayInputStream(ByteArray(1))
  private val contentType = "application/pdf"

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertModule()

    every { googleDriveReceiver.documentStore } returns DocumentStore.Google
    every { googleDriveReceiver.rename(any(), any()) } returns Unit

    every { user.canReadDeliverable(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canCreateSubmission(any()) } returns true
  }

  @Nested
  inner class ReceiveDocument {
    @Test
    fun `creates the submission document on upload`() {
      val projectId: ProjectId = insertProject()
      insertProjectDocumentSettings(projectId)
      val deliverableId: DeliverableId = insertDeliverable()

      val deliverable = deliverableStore.fetchOneById(deliverableId)
      val projectDocumentSettings = projectDocumentSettingsStore.fetchOneById(projectId)

      val mockDocumentDescription = "The budget"
      val mockStoredFileLocation = "test-location"

      val expectedFileName = "Deliverable 1_1970-01-01_PHL_CCCO2_The budget.pdf"

      every { googleDriveReceiver.upload(any(), any(), any()) } returns
          StoredFile(expectedFileName, mockStoredFileLocation)

      val submissionDocumentId =
          receiveDocument(
              deliverable = deliverable,
              description = mockDocumentDescription,
              projectDocumentSettings = projectDocumentSettings,
              projectId = projectId,
              originalName = "test-budget&?.pdf",
          )

      verify {
        googleDriveReceiver.upload(
            inputStream = inputStream,
            fileName = expectedFileName,
            contentType = contentType,
        )
      }

      val submissionDocument = submissionDocumentStore.fetchOneById(submissionDocumentId)
      assertEquals(
          submissionDocument,
          ExistingSubmissionDocumentModel(
              id = submissionDocumentId,
              documentStore = DocumentStore.Google,
              description = mockDocumentDescription,
              location = mockStoredFileLocation,
              name = expectedFileName,
              submissionId = SubmissionId(1)))
    }

    @Test
    fun `handles upload file name collisions correctly`() {
      val projectId: ProjectId = insertProject()
      insertProjectDocumentSettings(projectId)
      val deliverableId: DeliverableId = insertDeliverable()

      val deliverable = deliverableStore.fetchOneById(deliverableId)
      val projectDocumentSettings = projectDocumentSettingsStore.fetchOneById(projectId)

      val mockDocumentDescription = "The budget"
      val mockStoredFileLocation = "test-location"

      val expectedFileName1 = "Deliverable 1_1970-01-01_PHL_CCCO2_The budget.pdf"
      val expectedFileName2 = "Deliverable 1_1970-01-01_PHL_CCCO2_The budget_2.pdf"

      val mockStoredFile = StoredFile(expectedFileName1, "test-location")
      every { googleDriveReceiver.upload(any(), any(), any()) } returns mockStoredFile

      val submissionDocumentId1 =
          receiveDocument(
              deliverable = deliverable,
              description = mockDocumentDescription,
              projectDocumentSettings = projectDocumentSettings,
              projectId = projectId,
              originalName = "test-budget&?.pdf",
          )
      val submissionDocumentId2 =
          receiveDocument(
              deliverable = deliverable,
              description = mockDocumentDescription,
              projectDocumentSettings = projectDocumentSettings,
              projectId = projectId,
              originalName = "test-budget.pdf",
          )

      val submissionDocument1 = submissionDocumentStore.fetchOneById(submissionDocumentId1)
      val submissionDocument2 = submissionDocumentStore.fetchOneById(submissionDocumentId2)

      verify {
        googleDriveReceiver.upload(
            inputStream = inputStream,
            fileName = expectedFileName1,
            contentType = contentType,
        )
      }
      verify {
        googleDriveReceiver.rename(
            storedFile = mockStoredFile,
            newName = expectedFileName2,
        )
      }

      assertEquals(
          listOf(submissionDocument1, submissionDocument2),
          listOf(
              ExistingSubmissionDocumentModel(
                  id = submissionDocumentId1,
                  documentStore = DocumentStore.Google,
                  description = mockDocumentDescription,
                  location = mockStoredFileLocation,
                  name = expectedFileName1,
                  submissionId = SubmissionId(2)),
              ExistingSubmissionDocumentModel(
                  id = submissionDocumentId2,
                  documentStore = DocumentStore.Google,
                  description = mockDocumentDescription,
                  location = mockStoredFileLocation,
                  name = expectedFileName2,
                  submissionId = SubmissionId(2))))
    }
  }

  fun receiveDocument(
      deliverable: ExistingDeliverableModel,
      description: String,
      projectDocumentSettings: ExistingProjectDocumentSettingsModel,
      projectId: ProjectId,
      originalName: String,
  ) =
      service.receiveDocument(
          contentType = contentType,
          deliverable = deliverable,
          description = description,
          inputStream = inputStream,
          originalName = originalName,
          projectId = projectId,
          projectDocumentSettings = projectDocumentSettings,
          receiver = googleDriveReceiver)

    fun insertProjectDocumentSettings(projectId: ProjectId) = insertProjectDocumentSettings(
        dropboxFolderPath = "/terraware-uploads",
        fileNaming = "PHL_CCCO2",
        googleFolderUrl = URI("https://drive.google.com/drive/folders/FAKEhYWOWJ-l6ZI"),
        projectId = projectId,
    )
}
