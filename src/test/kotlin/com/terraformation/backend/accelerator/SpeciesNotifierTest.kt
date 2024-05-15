package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ParticipantProjectSpeciesStore
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedToProjectNotificationDueEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesEditedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SpeciesNotifierTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val store: ParticipantProjectSpeciesStore by lazy {
    ParticipantProjectSpeciesStore(
        clock, dslContext, eventPublisher, participantProjectSpeciesDao, projectsDao)
  }

  private val notifier: SpeciesNotifier by lazy {
    SpeciesNotifier(
        TestClock(),
        store,
        eventPublisher,
        mockk(),
        SubmissionStore(clock, dslContext, eventPublisher),
        SystemUser(usersDao),
    )
  }

  private lateinit var deliverableId: DeliverableId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertModule()
    insertCohort()
    insertCohortModule()
    insertParticipant(cohortId = inserted.cohortId)

    projectId = insertProject(participantId = inserted.participantId)
    deliverableId = insertDeliverable(deliverableTypeId = DeliverableType.Species)

    every { user.canReadProjectDeliverables(any()) } returns true
    every { user.canReadParticipantProjectSpecies(any()) } returns true
  }

  @Nested
  inner class NotifyIfNoNewerAddedSpecies {
    @Test
    fun `does not publish event if there were new species added to a deliverable`() {
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(
              createdTime = Instant.EPOCH, projectId = projectId, speciesId = speciesId1)
      insertParticipantProjectSpecies(
          createdTime = Instant.EPOCH.plusSeconds(1), projectId = projectId, speciesId = speciesId2)

      notifier.notifyIfNoNewerUpdates(
          ParticipantProjectSpeciesAddedEvent(
              deliverableId,
              participantProjectSpecies = store.fetchOneById(participantProjectSpeciesId1)))

      eventPublisher.assertEventNotPublished(
          ParticipantProjectSpeciesAddedToProjectNotificationDueEvent::class.java)
    }

    @Test
    fun `publishes event if this is the latest species added to the deliverable`() {
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      insertParticipantProjectSpecies(
          createdTime = Instant.EPOCH, projectId = projectId, speciesId = speciesId1)
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(
              createdTime = Instant.EPOCH.plusSeconds(1),
              projectId = projectId,
              speciesId = speciesId2)

      notifier.notifyIfNoNewerUpdates(
          ParticipantProjectSpeciesAddedEvent(
              deliverableId,
              participantProjectSpecies = store.fetchOneById(participantProjectSpeciesId2)))

      eventPublisher.assertEventPublished(
          ParticipantProjectSpeciesAddedToProjectNotificationDueEvent(
              deliverableId, projectId, speciesId2))
    }
  }

  @Nested
  inner class NotifyIfNoNewerUpdatedSpecies {
    @Test
    fun `does not publish event if there were new, qualifying species updates for a deliverable`() {
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(
              modifiedTime = Instant.EPOCH, projectId = projectId, speciesId = speciesId1)
      insertParticipantProjectSpecies(
          modifiedTime = Instant.EPOCH.plusSeconds(1),
          projectId = projectId,
          speciesId = speciesId2)

      val existing = store.fetchOneById(participantProjectSpeciesId1)

      notifier.notifyIfNoNewerUpdates(
          ParticipantProjectSpeciesEditedEvent(
              oldParticipantProjectSpecies = existing,
              newParticipantProjectSpecies = existing,
              projectId = projectId))

      eventPublisher.assertEventNotPublished(
          ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent::class.java)
    }

    @Test
    fun `publishes event if this is the latest qualifying species update for the deliverable`() {
      insertSubmission(projectId = projectId, deliverableId = deliverableId)
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      insertParticipantProjectSpecies(
          modifiedTime = Instant.EPOCH, projectId = projectId, speciesId = speciesId1)
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(
              modifiedTime = Instant.EPOCH.plusSeconds(1),
              projectId = projectId,
              speciesId = speciesId2)

      val existing = store.fetchOneById(participantProjectSpeciesId2)

      notifier.notifyIfNoNewerUpdates(
          ParticipantProjectSpeciesEditedEvent(
              oldParticipantProjectSpecies = existing,
              newParticipantProjectSpecies = existing,
              projectId = projectId))

      eventPublisher.assertEventPublished(
          ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent(
              deliverableId, projectId, speciesId2))
    }
  }
}
