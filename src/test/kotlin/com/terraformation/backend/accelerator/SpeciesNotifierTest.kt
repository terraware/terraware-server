package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedToProjectNotificationDueEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesEditedEvent
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SpeciesNotifierTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val rateLimitedEventPublisher = TestEventPublisher()

  private val notifier: SpeciesNotifier by lazy {
    SpeciesNotifier(
        rateLimitedEventPublisher,
        SubmissionStore(TestClock(), dslContext, TestEventPublisher()),
    )
  }

  private lateinit var deliverableId: DeliverableId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertModule()
    insertCohort()
    insertCohortModule()
    insertParticipant(cohortId = inserted.cohortId)

    projectId = insertProject(participantId = inserted.participantId)
    deliverableId = insertDeliverable(deliverableTypeId = DeliverableType.Species)

    every { user.canReadProjectDeliverables(any()) } returns true
    every { user.canReadParticipantProjectSpecies(any()) } returns true
    every { user.canReadSubmission(any()) } returns true
  }

  @Nested
  inner class OnParticipantProjectSpeciesAddedEvent {
    @Test
    fun `publishes event on species add`() {
      val speciesId = insertSpecies()
      val inputEvent =
          ParticipantProjectSpeciesAddedEvent(
              deliverableId,
              ExistingParticipantProjectSpeciesModel(
                  id = ParticipantProjectSpeciesId(1),
                  speciesId = speciesId,
                  projectId = projectId,
              ),
          )
      val expectedEvent =
          ParticipantProjectSpeciesAddedToProjectNotificationDueEvent(
              deliverableId,
              projectId,
              speciesId,
          )

      notifier.on(inputEvent)

      rateLimitedEventPublisher.assertEventPublished(expectedEvent)
      assertIsEventListener<ParticipantProjectSpeciesAddedEvent>(notifier)
    }
  }

  @Nested
  inner class OnParticipantProjectSpeciesEditedEvent {
    @Test
    fun `does not publish event if species was not approved`() {
      val speciesId = insertSpecies()
      val participantProjectSpeciesId = insertParticipantProjectSpecies()
      insertSubmission(projectId = projectId, deliverableId = deliverableId)

      val old =
          ExistingParticipantProjectSpeciesModel(
              id = participantProjectSpeciesId,
              speciesId = speciesId,
              projectId = projectId,
              submissionStatus = SubmissionStatus.NotSubmitted,
          )
      val new =
          ExistingParticipantProjectSpeciesModel(
              id = participantProjectSpeciesId,
              speciesId = speciesId,
              projectId = projectId,
              submissionStatus = SubmissionStatus.InReview,
          )

      notifier.on(ParticipantProjectSpeciesEditedEvent(old, new, projectId))

      rateLimitedEventPublisher.assertNoEventsPublished()
    }

    @Test
    fun `does not publish event if nothing changed`() {
      val speciesId = insertSpecies()
      val participantProjectSpeciesId = insertParticipantProjectSpecies()
      insertSubmission(projectId = projectId, deliverableId = deliverableId)

      val model =
          ExistingParticipantProjectSpeciesModel(
              id = participantProjectSpeciesId,
              speciesId = speciesId,
              projectId = projectId,
              submissionStatus = SubmissionStatus.Approved,
          )

      notifier.on(ParticipantProjectSpeciesEditedEvent(model, model, projectId))

      rateLimitedEventPublisher.assertNoEventsPublished()
    }

    @Test
    fun `publishes event if approved species was edited`() {
      val speciesId = insertSpecies()
      val participantProjectSpeciesId = insertParticipantProjectSpecies()
      insertSubmission(projectId = projectId, deliverableId = deliverableId)

      val old =
          ExistingParticipantProjectSpeciesModel(
              id = participantProjectSpeciesId,
              speciesId = speciesId,
              projectId = projectId,
              submissionStatus = SubmissionStatus.Approved,
          )
      val new =
          ExistingParticipantProjectSpeciesModel(
              id = participantProjectSpeciesId,
              speciesId = speciesId,
              projectId = projectId,
              submissionStatus = SubmissionStatus.InReview,
          )

      val expectedEvent =
          ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent(
              deliverableId,
              projectId,
              speciesId,
          )

      notifier.on(ParticipantProjectSpeciesEditedEvent(old, new, projectId))

      rateLimitedEventPublisher.assertEventPublished(expectedEvent)
      assertIsEventListener<ParticipantProjectSpeciesEditedEvent>(notifier)
    }
  }
}
