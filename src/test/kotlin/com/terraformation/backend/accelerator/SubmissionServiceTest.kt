package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ProjectDocumentSettingsStore
import com.terraformation.backend.accelerator.document.StoredFile
import com.terraformation.backend.accelerator.document.SubmissionDocumentReceiver
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.file.DropboxWriter
import com.terraformation.backend.file.GoogleDriveWriter
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.net.URI
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SubmissionServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()

  private val dropboxWriter: DropboxWriter = mockk()
  private val dropboxReceiver: SubmissionDocumentReceiver = mockk()
  private val googleDriveWriter: GoogleDriveWriter = mockk()
  private val googleDriveReceiver: SubmissionDocumentReceiver = mockk()

  private val deliverableStore: DeliverableStore by lazy {
    DeliverableStore(clock, dslContext, deliverablesDao)
  }
  private val projectDocumentSettingsStore: ProjectDocumentSettingsStore by lazy {
    ProjectDocumentSettingsStore(dslContext)
  }

  private val service: SubmissionService by lazy {
    SubmissionService(clock, dropboxWriter, dslContext, googleDriveWriter, deliverableStore, projectDocumentSettingsStore)
  }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertModule()

    every { user.canReadDeliverable(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canCreateSubmission(any()) } returns true
  }

  @Nested
  inner class ReceiveDocument {
    @Test
    fun `creates the submission document on upload`() {
      val projectId = insertProject()
      val deliverableId = insertDeliverable()

      insertProjectDocumentSettings(
          dropboxFolderPath = "/terraware-uploads",
          fileNaming = "PHL_CCCO2",
          googleFolderUrl = URI("https://drive.google.com/drive/folders/FAKEhYWOWJ-l6ZI"),
          projectId = projectId,
      )

      val deliverable = deliverableStore.fetchOneById(deliverableId)
      val projectDocumentSettings = projectDocumentSettingsStore.fetchOneById(projectId)

      every { googleDriveReceiver.upload(any(), any(), any()) } returns StoredFile("test-storedName", "test-location")
      every { googleDriveReceiver.documentStore } returns DocumentStore.Google

      val submissionId =
          service.receiveDocument(
              contentType = "application/pdf",
              deliverable = deliverable,
              description = "The budget",
              inputStream = ByteArrayInputStream(ByteArray(1)),
              originalName = "test-budget.pdf",
              projectId = projectId,
              projectDocumentSettings = projectDocumentSettings,
              receiver = googleDriveReceiver)

      assertEquals(submissionId, SubmissionDocumentId(1))
    }
  }
}
