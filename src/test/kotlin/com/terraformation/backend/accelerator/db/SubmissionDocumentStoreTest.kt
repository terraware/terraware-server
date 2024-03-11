package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.model.SubmissionDocumentModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SubmissionDocumentStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val store: SubmissionDocumentStore by lazy { SubmissionDocumentStore(dslContext) }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertCohort()
    insertModule()
    insertCohortModule()
    insertDeliverable()
    insertProject()

    every { user.canReadSubmissionDocument(any()) } returns true
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `gets the submission document`() {
      val submissionId = insertSubmission()
      val submissionDocumentId = insertSubmissionDocument()

      assertEquals(
          SubmissionDocumentModel(
              id = submissionDocumentId,
              submissionId = submissionId,
              location = "Location 1",
              name = "Submission Document 1",
              originalName = "Original Name 1",
              createdTime = Instant.EPOCH,
              description = null,
              documentStore = DocumentStore.Google),
          store.fetchOneById(submissionDocumentId))
    }

    @Test
    fun `throws exception if no permission to read submission document`() {
      insertSubmission()
      val submissionDocumentId = insertSubmissionDocument()

      every { user.canReadSubmissionDocument(any()) } returns false

      assertThrows<SubmissionDocumentNotFoundException> { store.fetchOneById(submissionDocumentId) }
    }
  }
}
