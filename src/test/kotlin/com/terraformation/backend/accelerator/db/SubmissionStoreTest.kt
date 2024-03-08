package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.DeliverableStatusUpdatedEvent
import com.terraformation.backend.accelerator.model.ExistingSubmissionModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionsRow
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class SubmissionStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store: SubmissionStore by lazy { SubmissionStore(clock, dslContext, eventPublisher) }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertModule()

    every { user.canReadProject(any()) } returns true
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

  @Nested
  inner class UpdateSubmissionStatus {
    @BeforeEach
    fun setUp() {
      every { user.canUpdateSubmissionStatus(any(), any()) } returns true
    }

    @Test
    fun `creates submission if needed`() {
      val projectId = insertProject()
      val deliverableId = insertDeliverable()

      val submissionId =
          store.updateSubmissionStatus(deliverableId, projectId, SubmissionStatus.InReview)

      assertEquals(
          listOf(
              SubmissionsRow(
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  deliverableId = deliverableId,
                  id = submissionId,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
                  projectId = projectId,
                  submissionStatusId = SubmissionStatus.InReview)),
          submissionsDao.findAll())
    }

    @Test
    fun `updates existing submission`() {
      val projectId = insertProject()
      val deliverableId = insertDeliverable()
      val feedback = "This looks great"
      val internalComment = "This made my eyes glaze over"

      val submissionId = insertSubmission(submissionStatus = SubmissionStatus.InReview)

      clock.instant = Instant.ofEpochSecond(123)

      store.updateSubmissionStatus(
          deliverableId, projectId, SubmissionStatus.Approved, feedback, internalComment)

      assertEquals(
          listOf(
              SubmissionsRow(
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  deliverableId = deliverableId,
                  feedback = feedback,
                  id = submissionId,
                  internalComment = internalComment,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
                  projectId = projectId,
                  submissionStatusId = SubmissionStatus.Approved)),
          submissionsDao.findAll())
    }

    @Test
    fun `publishes event if status has changed`() {
      val projectId = insertProject()
      val deliverableId = insertDeliverable()
      insertSubmission(submissionStatus = SubmissionStatus.InReview)

      store.updateSubmissionStatus(
          deliverableId,
          projectId,
          SubmissionStatus.Rejected,
          "This is a picture of a duck, not a budget",
          "Quack")

      eventPublisher.assertEventPublished(
          DeliverableStatusUpdatedEvent(
              deliverableId, projectId, SubmissionStatus.InReview, SubmissionStatus.Rejected))
    }

    @Test
    fun `does not publish event if status has not changed`() {
      val projectId = insertProject()
      val deliverableId = insertDeliverable()
      insertSubmission(submissionStatus = SubmissionStatus.InReview)

      store.updateSubmissionStatus(
          deliverableId, projectId, SubmissionStatus.InReview, null, "This is amazing")

      eventPublisher.assertNoEventsPublished()
    }

    @Test
    fun `throws exception if no permission to update submission status`() {
      val projectId = insertProject()
      val deliverableId = insertDeliverable()
      insertSubmission()

      every { user.canUpdateSubmissionStatus(deliverableId, projectId) } returns false

      assertThrows<AccessDeniedException> {
        store.updateSubmissionStatus(
            deliverableId, projectId, SubmissionStatus.NotSubmitted, null, null)
      }
    }
  }
}
