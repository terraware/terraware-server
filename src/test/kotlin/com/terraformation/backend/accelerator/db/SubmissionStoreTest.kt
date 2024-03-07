package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.ExistingSubmissionModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SubmissionStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val store: SubmissionStore by lazy { SubmissionStore(clock, dslContext, submissionsDao) }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertModule()

    every { user.canReadSubmission(any()) } returns true
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `fetches the submission`() {
      val projectId = insertProject()
      val deliverableId = insertDeliverable()
      val submissionId = insertSubmission()

      val submissionDocumentIds =
          setOf(
              insertSubmissionDocument(submissionId = submissionId),
              insertSubmissionDocument(submissionId = submissionId))

      assertEquals(
          ExistingSubmissionModel(
              id = submissionId,
              feedback = null,
              internalComment = null,
              projectId = projectId,
              deliverableId = deliverableId,
              submissionDocumentIds = submissionDocumentIds,
              submissionStatus = SubmissionStatus.NotSubmitted),
          store.fetchOneById(submissionId))
    }

    @Test
    fun `throws exception if no permission to read submissions`() {
      insertProject()
      insertDeliverable()
      val submissionId = insertSubmission()

      every { user.canReadSubmission(submissionId) } returns false

      assertThrows<SubmissionNotFoundException> { store.fetchOneById(submissionId) }
    }
  }
}
