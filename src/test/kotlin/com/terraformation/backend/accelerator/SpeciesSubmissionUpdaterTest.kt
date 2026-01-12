package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedEvent
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionsRow
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SpeciesSubmissionUpdaterTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val submissionStore: SubmissionStore by lazy {
    SubmissionStore(clock, dslContext, eventPublisher)
  }

  private val updater: SpeciesSubmissionUpdater by lazy {
    SpeciesSubmissionUpdater(dslContext, submissionStore)
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()

    every { user.canCreateSubmission(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadSubmission(any()) } returns true
  }

  @Nested
  inner class ResetSubmission {
    @Test
    fun `resets the submission status if it exists and is currently 'Approved'`() {
      val cohortId = insertCohort()
      val projectId = insertProject(cohortId = cohortId)
      val speciesId = insertSpecies()
      val moduleId = insertModule()
      val deliverableId =
          insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)
      val submissionId =
          insertSubmission(
              deliverableId = deliverableId,
              feedback = "So far so good",
              internalComment = "Internal comment",
              projectId = projectId,
              submissionStatus = SubmissionStatus.Approved,
          )

      val participantProjectSpeciesId =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId)

      updater.on(
          ParticipantProjectSpeciesAddedEvent(
              deliverableId = deliverableId,
              participantProjectSpecies =
                  ExistingParticipantProjectSpeciesModel(
                      id = participantProjectSpeciesId,
                      projectId = projectId,
                      speciesId = speciesId,
                      submissionStatus = SubmissionStatus.NotSubmitted,
                  ),
          )
      )

      val userId = currentUser().userId
      val now = clock.instant

      val actual = submissionsDao.fetchOneById(submissionId)
      val expected =
          SubmissionsRow(
              createdBy = userId,
              createdTime = now,
              deliverableId = deliverableId,
              feedback = "So far so good",
              id = submissionId,
              internalComment = "Internal comment",
              modifiedBy = userId,
              modifiedTime = now,
              projectId = projectId,
              submissionStatusId = SubmissionStatus.NotSubmitted,
          )

      assertEquals(expected, actual, "The submission's status was not updated to 'Not Submitted'")
    }

    @Test
    fun `does nothing if the submission status is not 'Approved'`() {
      val cohortId = insertCohort()
      val projectId = insertProject(cohortId = cohortId)
      val speciesId = insertSpecies()
      val moduleId = insertModule()
      val deliverableId =
          insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)
      val submissionId =
          insertSubmission(
              deliverableId = deliverableId,
              feedback = "So far so good",
              internalComment = "Internal comment",
              projectId = projectId,
              submissionStatus = SubmissionStatus.InReview,
          )

      val participantProjectSpeciesId =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId)

      val before = submissionsDao.fetchOneById(submissionId)

      updater.on(
          ParticipantProjectSpeciesAddedEvent(
              deliverableId = deliverableId,
              participantProjectSpecies =
                  ExistingParticipantProjectSpeciesModel(
                      id = participantProjectSpeciesId,
                      projectId = projectId,
                      speciesId = speciesId,
                      submissionStatus = SubmissionStatus.NotSubmitted,
                  ),
          )
      )

      val after = submissionsDao.fetchOneById(submissionId)

      assertEquals(before, after, "The submission was modified")
    }
  }
}
