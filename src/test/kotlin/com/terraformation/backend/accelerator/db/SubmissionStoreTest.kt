package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.DeliverableStatusUpdatedEvent
import com.terraformation.backend.accelerator.model.ExistingSpeciesDeliverableSubmissionModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionsRow
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import java.time.temporal.ChronoUnit
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

    every { user.canReadProject(any()) } returns true
    every { user.canReadSubmission(any()) } returns true
    every { user.canReadProjectDeliverables(any()) } returns true
  }

  @Nested
  inner class FetchActiveSpeciesDeliverable {
    @Test
    fun `fetches the deliverable ID if no submission present`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(participantId = participantId)

      // Module goes from epoch -> epoch + 6 days
      val moduleIdOld = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleIdOld)
      insertDeliverable(moduleId = moduleIdOld, deliverableTypeId = DeliverableType.Species)

      // Module goes from epoch + 6 days -> epoch + 12 days
      val moduleIdActive = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleIdActive)
      val deliverableIdActive =
          insertDeliverable(moduleId = moduleIdActive, deliverableTypeId = DeliverableType.Species)

      // Module goes from epoch + 12 days -> epoch + 18 days
      val moduleIdFuture = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleIdFuture)
      insertDeliverable(moduleId = moduleIdFuture, deliverableTypeId = DeliverableType.Species)

      clock.instant = Instant.EPOCH.plus(7, ChronoUnit.DAYS)

      assertEquals(
          ExistingSpeciesDeliverableSubmissionModel(
              deliverableId = deliverableIdActive,
              submissionId = null,
          ),
          store.fetchActiveSpeciesDeliverableSubmission(projectId))
    }

    @Test
    fun `fetches both deliverable ID and submission ID if present`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(participantId = participantId)

      // Module goes from epoch -> epoch + 6 days
      val moduleIdOld = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleIdOld)
      val deliverableIdOld =
          insertDeliverable(moduleId = moduleIdOld, deliverableTypeId = DeliverableType.Species)
      insertSubmission(deliverableId = deliverableIdOld, projectId = projectId)

      // Module goes from epoch + 6 days -> epoch + 12 days
      val moduleIdActive = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleIdActive)
      val deliverableIdActive =
          insertDeliverable(moduleId = moduleIdActive, deliverableTypeId = DeliverableType.Species)
      val submissionIdActive =
          insertSubmission(deliverableId = deliverableIdActive, projectId = projectId)

      // Module goes from epoch + 12 days -> epoch + 18 days
      val moduleIdFuture = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleIdFuture)
      val deliverableIdFuture =
          insertDeliverable(moduleId = moduleIdFuture, deliverableTypeId = DeliverableType.Species)
      insertSubmission(deliverableId = deliverableIdFuture, projectId = projectId)

      clock.instant = Instant.EPOCH.plus(7, ChronoUnit.DAYS)

      assertEquals(
          ExistingSpeciesDeliverableSubmissionModel(
              deliverableId = deliverableIdActive,
              submissionId = submissionIdActive,
          ),
          store.fetchActiveSpeciesDeliverableSubmission(projectId))
    }

    @Test
    fun `throws an exception if no permission to read the submission`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(participantId = participantId)

      val moduleId = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleId)
      val deliverableId =
          insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)
      val submissionId = insertSubmission(deliverableId = deliverableId, projectId = projectId)

      every { user.canReadSubmission(submissionId) } returns false

      assertThrows<SubmissionNotFoundException> {
        store.fetchActiveSpeciesDeliverableSubmission(projectId)
      }
    }

    @Test
    fun `throws an exception if no permission to read project deliverables`() {
      val projectId = insertProject()

      every { user.canReadProjectDeliverables(projectId) } returns false

      assertThrows<AccessDeniedException> {
        store.fetchActiveSpeciesDeliverableSubmission(projectId)
      }
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
      insertModule()
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
      insertModule()
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
      insertModule()
      val projectId = insertProject()
      val deliverableId = insertDeliverable()
      val submissionId = insertSubmission(submissionStatus = SubmissionStatus.InReview)

      store.updateSubmissionStatus(
          deliverableId,
          projectId,
          SubmissionStatus.Rejected,
          "This is a picture of a duck, not a budget",
          "Quack")

      eventPublisher.assertEventPublished(
          DeliverableStatusUpdatedEvent(
              deliverableId,
              projectId,
              SubmissionStatus.InReview,
              SubmissionStatus.Rejected,
              submissionId))
    }

    @Test
    fun `does not publish event if status has not changed`() {
      insertModule()
      val projectId = insertProject()
      val deliverableId = insertDeliverable()
      insertSubmission(submissionStatus = SubmissionStatus.InReview)

      store.updateSubmissionStatus(
          deliverableId, projectId, SubmissionStatus.InReview, null, "This is amazing")

      eventPublisher.assertNoEventsPublished()
    }

    @Test
    fun `throws exception if no permission to update submission status`() {
      insertModule()
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
