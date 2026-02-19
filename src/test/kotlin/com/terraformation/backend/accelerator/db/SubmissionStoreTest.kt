package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.DeliverableStatusUpdatedEvent
import com.terraformation.backend.accelerator.model.ExistingSpeciesDeliverableSubmissionModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionsRow
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
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
    insertOrganization()

    every { user.canReadProject(any()) } returns true
    every { user.canReadSubmission(any()) } returns true
    every { user.canReadProjectDeliverables(any()) } returns true
  }

  @Nested
  inner class FetchMostRecentSpeciesDeliverableSubmission {
    @Test
    fun `fetches the deliverable ID if no submission present for an active deliverable`() {
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)

      // Module goes from epoch -> epoch + 6 days
      val moduleIdOld = insertModule()
      insertProjectModule()
      insertDeliverable(moduleId = moduleIdOld, deliverableTypeId = DeliverableType.Species)

      // Module goes from epoch + 6 days -> epoch + 12 days
      val moduleIdActive = insertModule()
      insertProjectModule()
      val deliverableIdActive =
          insertDeliverable(moduleId = moduleIdActive, deliverableTypeId = DeliverableType.Species)

      // Module goes from epoch + 12 days -> epoch + 18 days
      val moduleIdFuture = insertModule()
      insertProjectModule()
      insertDeliverable(moduleId = moduleIdFuture, deliverableTypeId = DeliverableType.Species)

      clock.instant = Instant.EPOCH.plus(7, ChronoUnit.DAYS)

      assertEquals(
          ExistingSpeciesDeliverableSubmissionModel(
              deliverableId = deliverableIdActive,
              submissionId = null,
          ),
          store.fetchMostRecentSpeciesDeliverableSubmission(projectId),
      )
    }

    @Test
    fun `fetches the deliverable ID if no submission present for the most recent inactive deliverable if there is no active deliverable`() {
      val projectId = insertProject(phase = CohortPhase.Phase1FeasibilityStudy)

      // Module goes from epoch -> epoch + 6 days
      val moduleIdOld = insertModule()
      insertProjectModule()
      insertDeliverable(moduleId = moduleIdOld, deliverableTypeId = DeliverableType.Species)

      // Module goes from epoch + 6 days -> epoch + 12 days
      val moduleIdMostRecent = insertModule()
      insertProjectModule()
      val deliverableIdMostRecent =
          insertDeliverable(
              moduleId = moduleIdMostRecent,
              deliverableTypeId = DeliverableType.Species,
          )

      // Clock date is between these two modules

      // Module goes from epoch + 20 days -> epoch + 30 days
      val moduleIdFuture = insertModule()
      insertProjectModule(
          endDate = LocalDate.EPOCH.plusDays(30),
          startDate = LocalDate.EPOCH.plusDays(20),
      )
      insertDeliverable(moduleId = moduleIdFuture, deliverableTypeId = DeliverableType.Species)

      // Set clock to a week after the most recent module deliverable was active
      clock.instant = Instant.EPOCH.plus(19, ChronoUnit.DAYS)

      assertEquals(
          ExistingSpeciesDeliverableSubmissionModel(
              deliverableId = deliverableIdMostRecent,
              submissionId = null,
          ),
          store.fetchMostRecentSpeciesDeliverableSubmission(projectId),
      )
    }

    @Test
    fun `fetches both deliverable ID and submission ID if present for active deliverable`() {
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)

      // Module goes from epoch -> epoch + 6 days
      val moduleIdOld = insertModule()
      insertProjectModule()
      val deliverableIdOld =
          insertDeliverable(moduleId = moduleIdOld, deliverableTypeId = DeliverableType.Species)
      insertSubmission(deliverableId = deliverableIdOld, projectId = projectId)

      // Module goes from epoch + 6 days -> epoch + 12 days
      val moduleIdActive = insertModule()
      insertProjectModule()
      val deliverableIdActive =
          insertDeliverable(moduleId = moduleIdActive, deliverableTypeId = DeliverableType.Species)
      val submissionIdActive =
          insertSubmission(deliverableId = deliverableIdActive, projectId = projectId)

      // Module goes from epoch + 12 days -> epoch + 18 days
      val moduleIdFuture = insertModule()
      insertProjectModule()
      val deliverableIdFuture =
          insertDeliverable(moduleId = moduleIdFuture, deliverableTypeId = DeliverableType.Species)
      insertSubmission(deliverableId = deliverableIdFuture, projectId = projectId)

      clock.instant = Instant.EPOCH.plus(7, ChronoUnit.DAYS)

      assertEquals(
          ExistingSpeciesDeliverableSubmissionModel(
              deliverableId = deliverableIdActive,
              submissionId = submissionIdActive,
          ),
          store.fetchMostRecentSpeciesDeliverableSubmission(projectId),
      )
    }

    @Test
    fun `fetches both deliverable ID and submission ID if present for most recent deliverable if there is no active deliverable`() {
      val projectId = insertProject(phase = CohortPhase.Phase2PlanAndScale)

      // Module goes from epoch -> epoch + 6 days
      val moduleIdOld = insertModule()
      insertProjectModule()
      val deliverableIdOld =
          insertDeliverable(moduleId = moduleIdOld, deliverableTypeId = DeliverableType.Species)
      insertSubmission(deliverableId = deliverableIdOld, projectId = projectId)

      // Module goes from epoch + 6 days -> epoch + 12 days
      val moduleIdMostRecent = insertModule()
      insertProjectModule()
      val deliverableIdActive =
          insertDeliverable(
              moduleId = moduleIdMostRecent,
              deliverableTypeId = DeliverableType.Species,
          )
      val submissionIdActive =
          insertSubmission(deliverableId = deliverableIdActive, projectId = projectId)

      // Clock date is between these two modules

      // Module goes from epoch + 20 days -> epoch + 30 days
      val moduleIdFuture = insertModule()
      insertProjectModule(
          endDate = LocalDate.EPOCH.plusDays(30),
          startDate = LocalDate.EPOCH.plusDays(20),
      )
      val deliverableIdFuture =
          insertDeliverable(moduleId = moduleIdFuture, deliverableTypeId = DeliverableType.Species)
      insertSubmission(deliverableId = deliverableIdFuture, projectId = projectId)

      // Set clock to a week after the most recent module deliverable was active
      clock.instant = Instant.EPOCH.plus(19, ChronoUnit.DAYS)

      assertEquals(
          ExistingSpeciesDeliverableSubmissionModel(
              deliverableId = deliverableIdActive,
              submissionId = submissionIdActive,
          ),
          store.fetchMostRecentSpeciesDeliverableSubmission(projectId),
      )
    }

    @Test
    fun `throws an exception if no permission to read the submission`() {
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)

      val moduleId = insertModule()
      insertProjectModule()
      val deliverableId =
          insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)
      val submissionId = insertSubmission(deliverableId = deliverableId, projectId = projectId)

      every { user.canReadSubmission(submissionId) } returns false

      assertThrows<SubmissionNotFoundException> {
        store.fetchMostRecentSpeciesDeliverableSubmission(projectId)
      }
    }

    @Test
    fun `throws an exception if no permission to read project deliverables`() {
      val projectId = insertProject()

      every { user.canReadProjectDeliverables(projectId) } returns false

      assertThrows<AccessDeniedException> {
        store.fetchMostRecentSpeciesDeliverableSubmission(projectId)
      }
    }
  }

  @Nested
  inner class CreateSubmission {
    @BeforeEach
    fun setUp() {
      every { user.canCreateSubmission(any()) } returns true
    }

    @Test
    fun `creates submission if needed`() {
      insertModule()
      val projectId = insertProject()
      val deliverableId = insertDeliverable()

      val submissionId = store.createSubmission(deliverableId, projectId)

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
                  submissionStatusId = SubmissionStatus.NotSubmitted,
              )
          ),
          submissionsDao.findAll(),
      )
    }

    @Test
    fun `updates existing submission`() {
      insertModule()
      val projectId = insertProject()
      val deliverableId = insertDeliverable()

      val submissionId = insertSubmission(submissionStatus = SubmissionStatus.NotSubmitted)

      clock.instant = Instant.ofEpochSecond(123)

      store.createSubmission(deliverableId, projectId, SubmissionStatus.Completed)

      assertEquals(
          listOf(
              SubmissionsRow(
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  deliverableId = deliverableId,
                  id = submissionId,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
                  projectId = projectId,
                  submissionStatusId = SubmissionStatus.Completed,
              )
          ),
          submissionsDao.findAll(),
      )
    }

    @Test
    fun `throws exception if creating submission for internal only statuses`() {
      insertModule()
      val projectId = insertProject()
      val deliverableId = insertDeliverable()

      val illegalStatuses =
          setOf(
              SubmissionStatus.Approved,
              SubmissionStatus.Rejected,
              SubmissionStatus.NeedsTranslation,
              SubmissionStatus.NotNeeded,
          )

      illegalStatuses.forEach {
        assertThrows<IllegalArgumentException> {
          store.createSubmission(deliverableId, projectId, it)
        }
      }
    }

    @Test
    fun `throws exception if no permission to create submission`() {
      insertModule()
      val projectId = insertProject()
      val deliverableId = insertDeliverable()

      every { user.canCreateSubmission(projectId) } returns false

      assertThrows<AccessDeniedException> {
        store.createSubmission(deliverableId, projectId, SubmissionStatus.NotSubmitted)
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
                  submissionStatusId = SubmissionStatus.InReview,
              )
          ),
          submissionsDao.findAll(),
      )
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
          deliverableId,
          projectId,
          SubmissionStatus.Approved,
          feedback,
          internalComment,
      )

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
                  submissionStatusId = SubmissionStatus.Approved,
              )
          ),
          submissionsDao.findAll(),
      )
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
          "Quack",
      )

      eventPublisher.assertEventPublished(
          DeliverableStatusUpdatedEvent(
              deliverableId,
              projectId,
              SubmissionStatus.InReview,
              SubmissionStatus.Rejected,
              submissionId,
          )
      )
    }

    @Test
    fun `does not publish event if status has not changed`() {
      insertModule()
      val projectId = insertProject()
      val deliverableId = insertDeliverable()
      insertSubmission(submissionStatus = SubmissionStatus.InReview)

      store.updateSubmissionStatus(
          deliverableId,
          projectId,
          SubmissionStatus.InReview,
          null,
          "This is amazing",
      )

      eventPublisher.assertNoEventsPublished()
    }

    @Test
    fun `allows non-admin users to reset submission status to Not Submitted`() {
      insertModule()
      val projectId = insertProject()
      val deliverableId = insertDeliverable()
      insertSubmission(submissionStatus = SubmissionStatus.InReview)

      every { user.canCreateSubmission(projectId) } returns true
      every { user.canUpdateSubmissionStatus(deliverableId, projectId) } returns false

      store.updateSubmissionStatus(
          deliverableId,
          projectId,
          SubmissionStatus.NotSubmitted,
          null,
          null,
      )

      assertEquals(
          SubmissionStatus.NotSubmitted,
          submissionsDao.findAll().first().submissionStatusId,
      )
    }

    @Test
    fun `throws exception if no permission to update submission status`() {
      insertModule()
      val projectId = insertProject()
      val deliverableId = insertDeliverable()
      insertSubmission()

      every { user.canCreateSubmission(projectId) } returns true
      every { user.canUpdateSubmissionStatus(deliverableId, projectId) } returns false

      assertThrows<AccessDeniedException> {
        store.updateSubmissionStatus(
            deliverableId,
            projectId,
            SubmissionStatus.Rejected,
            null,
            null,
        )
      }
    }
  }
}
