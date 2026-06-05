package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.dummyTerrawareServerConfig
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.LocalDropboxWriter
import com.terraformation.backend.file.LocalGoogleDriveWriter
import com.terraformation.backend.mockUser
import io.mockk.every
import jakarta.ws.rs.core.MediaType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SubmissionServiceLocalStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val config = dummyTerrawareServerConfig()
  private val eventPublisher = TestEventPublisher()
  private val fileStore = InMemoryFileStore()
  private val dropboxWriter = LocalDropboxWriter(fileStore)
  private val googleDriveWriter = LocalGoogleDriveWriter(fileStore)

  private val service: SubmissionService by lazy {
    SubmissionService(clock, config, dropboxWriter, dslContext, eventPublisher, googleDriveWriter)
  }

  private val fileNaming = "xyz"

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
    insertModule()

    every { user.canCreateSubmission(any()) } returns true
  }

  @Test
  fun `stores non-sensitive document via local Google Drive writer`() {
    val deliverableId = insertDeliverable()
    insertProjectAcceleratorDetails(
        fileNaming = fileNaming,
        googleFolderUrl = "https://drive.google.com/drive/folders/abc",
    )

    val documentId = receiveDocument(deliverableId)

    val document = submissionDocumentsDao.fetchOneById(documentId)!!
    assertEquals(DocumentStore.Google, document.documentStoreId, "document store")
    assertArrayEquals(
        byteArrayOf(1, 2, 3),
        googleDriveWriter.downloadFile(document.location!!).readAllBytes(),
        "stored bytes",
    )
  }

  @Test
  fun `stores sensitive document via local Dropbox writer`() {
    val deliverableId = insertDeliverable(isSensitive = true)
    insertProjectAcceleratorDetails(fileNaming = fileNaming, dropboxFolderPath = "/Sensitive")

    val documentId = receiveDocument(deliverableId)

    val document = submissionDocumentsDao.fetchOneById(documentId)!!
    assertEquals(DocumentStore.Dropbox, document.documentStoreId, "document store")
    assertArrayEquals(
        byteArrayOf(1, 2, 3),
        fileStore.read(dropboxWriter.shareFile(document.location!!)).readAllBytes(),
        "stored bytes",
    )
  }

  private fun receiveDocument(deliverableId: DeliverableId) =
      service.receiveDocument(
          byteArrayOf(1, 2, 3).inputStream(),
          "file.doc",
          inserted.projectId,
          deliverableId,
          "description",
          MediaType.APPLICATION_OCTET_STREAM,
      )
}
