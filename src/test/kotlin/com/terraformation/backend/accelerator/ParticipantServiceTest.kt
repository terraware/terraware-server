package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ParticipantStore
import com.terraformation.backend.accelerator.event.CohortParticipantAddedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectAddedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectRemovedEvent
import com.terraformation.backend.accelerator.model.ParticipantModel
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantsRow
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ParticipantServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val service: ParticipantService by lazy {
    ParticipantService(
        dslContext,
        ParticipantStore(clock, dslContext, eventPublisher, participantsDao),
        ProjectStore(
            clock,
            dslContext,
            eventPublisher,
            parentStore,
            projectsDao,
            projectInternalUsersDao,
        ),
    )
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()

    every { user.canAddParticipantProject(any(), any()) } returns true
    every { user.canCreateParticipant() } returns true
    every { user.canDeleteParticipantProject(any(), any()) } returns true
    every { user.canReadParticipant(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canUpdateParticipant(any()) } returns true
    every { user.canUpdateProject(any()) } returns true
  }

  @Nested
  inner class Create {
    @Test
    fun `creates participant and assigns projects to it`() {
      val cohortId = insertCohort()
      val existingParticipantId = insertParticipant()
      val projectId1 = insertProject(participantId = existingParticipantId)
      val projectId2 = insertProject()
      val projectId3 = insertProject(participantId = existingParticipantId)

      val model =
          service.create(
              ParticipantModel.create(
                  cohortId = cohortId,
                  name = "part",
                  projectIds = setOf(projectId1, projectId2),
              )
          )

      assertEquals(
          ParticipantsRow(
              cohortId = cohortId,
              createdBy = user.userId,
              createdTime = clock.instant,
              id = model.id,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              name = "part",
          ),
          participantsDao.fetchOneById(model.id),
      )

      assertEquals(
          mapOf(
              projectId1 to model.id,
              projectId2 to model.id,
              projectId3 to existingParticipantId,
          ),
          projectsDao.findAll().associate { it.id to it.participantId },
          "Participant IDs for projects",
      )

      eventPublisher.assertExactEventsPublished(
          setOf(
              CohortParticipantAddedEvent(cohortId, model.id),
              ParticipantProjectAddedEvent(user.userId, model.id, projectId1),
              ParticipantProjectAddedEvent(user.userId, model.id, projectId2),
              ParticipantProjectRemovedEvent(existingParticipantId, projectId1, user.userId),
          )
      )
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates participant details and adds and removes projects`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant()
      val projectIdToRemove = insertProject(participantId = participantId)
      val projectIdToKeep = insertProject(participantId = participantId)
      val projectIdToAdd = insertProject()

      clock.instant = Instant.ofEpochSecond(1234)

      service.update(participantId) {
        it.copy(
            cohortId = cohortId,
            name = "new name",
            projectIds = setOf(projectIdToAdd, projectIdToKeep),
        )
      }

      assertEquals(
          ParticipantsRow(
              cohortId = cohortId,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              id = participantId,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              name = "new name",
          ),
          participantsDao.fetchOneById(participantId),
      )

      assertEquals(
          mapOf(
              projectIdToRemove to null,
              projectIdToAdd to participantId,
              projectIdToKeep to participantId,
          ),
          projectsDao.findAll().associate { it.id to it.participantId },
          "Participant IDs for projects",
      )

      // Shouldn't see events related to projectIdToKeep since it wasn't modified.
      eventPublisher.assertExactEventsPublished(
          setOf(
              CohortParticipantAddedEvent(cohortId, participantId),
              ParticipantProjectAddedEvent(user.userId, participantId, projectIdToAdd),
              ParticipantProjectRemovedEvent(participantId, projectIdToRemove, user.userId),
          )
      )
    }

    @Test
    fun `rolls back all changes if no permission to add participant project`() {
      val participantId = insertParticipant(name = "old name")
      val projectId = insertProject()

      every { user.canAddParticipantProject(participantId, projectId) } returns false

      assertThrows<AccessDeniedException> {
        service.update(participantId) { it.copy(name = "new name", projectIds = setOf(projectId)) }
      }

      assertEquals(
          "old name",
          participantsDao.fetchOneById(participantId)!!.name,
          "Participant name",
      )
      assertNull(projectsDao.fetchOneById(projectId)!!.participantId, "Project participant ID")
    }
  }
}
