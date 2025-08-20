package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.CohortParticipantAddedEvent
import com.terraformation.backend.accelerator.model.ExistingParticipantModel
import com.terraformation.backend.accelerator.model.ParticipantModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ParticipantId
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

class ParticipantStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store: ParticipantStore by lazy {
    ParticipantStore(clock, dslContext, eventPublisher, participantsDao)
  }

  @BeforeEach
  fun setUp() {
    every { user.canReadParticipant(any()) } returns true
    every { user.canReadProject(any()) } returns true
  }

  @Nested
  inner class Create {
    @Test
    fun `creates participant`() {
      every { user.canCreateParticipant() } returns true

      clock.instant = Instant.EPOCH.plusSeconds(500)

      val model = store.create(ParticipantModel.create("test"))

      assertEquals(
          listOf(
              ParticipantsRow(
                  id = model.id,
                  name = "test",
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )
          ),
          participantsDao.findAll(),
      )
    }

    @Test
    fun `creates participant with initial cohort`() {
      every { user.canCreateParticipant() } returns true

      val cohortId = insertCohort()

      clock.instant = Instant.EPOCH.plusSeconds(500)

      val model = store.create(ParticipantModel.create(cohortId = cohortId, name = "test"))

      assertEquals(
          listOf(
              ParticipantsRow(
                  cohortId = cohortId,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  id = model.id,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
                  name = "test",
              )
          ),
          participantsDao.findAll(),
      )

      eventPublisher.assertEventPublished(CohortParticipantAddedEvent(cohortId, model.id))
    }

    @Test
    fun `throws exception if no permission to create participants`() {
      assertThrows<AccessDeniedException> { store.create(ParticipantModel.create("test")) }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `deletes empty participant`() {
      val participantIdToKeep = insertParticipant()
      val participantIdToDelete = insertParticipant()

      every { user.canDeleteParticipant(any()) } returns true

      store.delete(participantIdToDelete)

      assertEquals(
          listOf(participantIdToKeep),
          participantsDao.findAll().map { it.id },
          "Participant IDs after delete",
      )
    }

    @Test
    fun `throws exception and does not delete participant if it has projects`() {
      val participantId = insertParticipant()
      insertOrganization()
      insertProject(participantId = participantId)

      every { user.canDeleteParticipant(participantId) } returns true

      assertThrows<ParticipantHasProjectsException> { store.delete(participantId) }
    }

    @Test
    fun `throws exception if participant does not exist`() {
      every { user.canDeleteParticipant(any()) } returns true

      assertThrows<ParticipantNotFoundException> { store.delete(ParticipantId(1)) }
    }

    @Test
    fun `throws exception if no permission to delete participant`() {
      val participantId = insertParticipant()

      assertThrows<AccessDeniedException> { store.delete(participantId) }
    }
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `populates all fields including visible project list`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId, name = "Test Name")

      insertOrganization()
      val projectId1 = insertProject(participantId = participantId)
      val projectId2 = insertProject(participantId = participantId)
      val invisibleProjectId = insertProject(participantId = participantId)
      insertProject()

      every { user.canReadProject(invisibleProjectId) } returns false

      assertEquals(
          ExistingParticipantModel(
              cohortId = cohortId,
              id = participantId,
              name = "Test Name",
              projectIds = setOf(projectId1, projectId2),
          ),
          store.fetchOneById(participantId),
      )
    }

    @Test
    fun `throws exception if no permission to read participant`() {
      val participantId = insertParticipant()

      every { user.canReadParticipant(participantId) } returns false

      assertThrows<ParticipantNotFoundException> { store.fetchOneById(participantId) }
    }
  }

  @Nested
  inner class FindAll {
    @Test
    fun `only includes participants the user has permission to read`() {
      val participantId1 = insertParticipant()
      val participantId2 = insertParticipant()
      val invisibleParticipantId = insertParticipant(name = "Not Visible")

      every { user.canReadParticipant(invisibleParticipantId) } returns false

      assertEquals(
          listOf(participantId1, participantId2),
          store.findAll().map { it.id },
          "Participant IDs",
      )
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates editable fields`() {
      val cohortId = insertCohort()
      val otherUserId = insertUser()
      val participantId = insertParticipant(name = "Old Name", createdBy = otherUserId)

      every { user.canUpdateParticipant(participantId) } returns true

      clock.instant = Instant.ofEpochSecond(1)

      store.update(participantId) { it.copy(cohortId = cohortId, name = "New Name") }

      assertEquals(
          ParticipantsRow(
              cohortId = cohortId,
              createdBy = otherUserId,
              createdTime = Instant.EPOCH,
              id = participantId,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              name = "New Name",
          ),
          participantsDao.fetchOneById(participantId),
      )

      eventPublisher.assertEventPublished(CohortParticipantAddedEvent(cohortId, participantId))
    }

    @Test
    fun `throws exception if participant does not exist`() {
      every { user.canUpdateParticipant(any()) } returns true

      assertThrows<ParticipantNotFoundException> { store.update(ParticipantId(1)) { it } }
    }

    @Test
    fun `throws exception if no permission to update participant`() {
      val participantId = insertParticipant()

      assertThrows<AccessDeniedException> { store.update(participantId) { it.copy(name = "New") } }
    }
  }
}
