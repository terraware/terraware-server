package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.ExistingSubmissionModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.mockUser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SubmissionStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val store: SubmissionStore by lazy { SubmissionStore(clock, dslContext, submissionsDao) }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertModule()
    // TODO
    // every { user.canReadSubmission(any()) } returns true
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `fetches the submission`() {
      val projectId = insertProject()
      val deliverableId = insertDeliverable()
      val submissionId = insertSubmission()

      assertEquals(
          ExistingSubmissionModel(
              id = submissionId,
              feedback = "",
              internalComment = "",
              projectId = projectId,
              deliverableId = deliverableId,
              submissionDocumentIds = emptySet(),
              submissionStatus = SubmissionStatus.NotSubmitted),
          store.fetchOneById(submissionId))
    }

    //    @Test
    //    fun `throws exception if no permission to read submissions`() {
    //      val submissionId = insertSubmission(id = 1)
    //
    //      every { user.canReadSubmission(submissionId) } returns false
    //
    //      assertThrows<SubmissionNotFoundException> { store.fetchOneById(submissionId) }
    //    }
  }
}
