package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantProjectSpeciesRow
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ParticipantProjectSpeciesStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val store: ParticipantProjectSpeciesStore by lazy {
    ParticipantProjectSpeciesStore(dslContext, participantProjectSpeciesDao, projectsDao)
  }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()

    every { user.canCreateParticipantProjectSpecies(any()) } returns true
    every { user.canCreateSubmission(any()) } returns true
    every { user.canReadParticipantProjectSpecies(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadProjectDeliverables(any()) } returns true
    every { user.canUpdateParticipantProjectSpecies(any()) } returns true
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `populates all fields and includes associated entities where applicable`() {
      val participantId = insertParticipant()
      val projectId = insertProject(participantId = participantId)
      val speciesId = insertSpecies()
      val participantProjectSpeciesId =
          insertParticipantProjectSpecies(
              feedback = "feedback",
              projectId = projectId,
              rationale = "rationale",
              speciesId = speciesId)

      assertEquals(
          ExistingParticipantProjectSpeciesModel(
              feedback = "feedback",
              id = participantProjectSpeciesId,
              projectId = projectId,
              rationale = "rationale",
              speciesId = speciesId,
              submissionStatus = SubmissionStatus.NotSubmitted,
          ),
          store.fetchOneById(participantProjectSpeciesId))
    }

    @Test
    fun `throws exception if no permission to read participant project species`() {
      val participantId = insertParticipant()
      val projectId = insertProject(participantId = participantId)
      val speciesId = insertSpecies()
      val participantProjectSpeciesId =
          insertParticipantProjectSpecies(
              feedback = "feedback",
              projectId = projectId,
              rationale = "rationale",
              speciesId = speciesId)

      every { user.canReadParticipantProjectSpecies(participantProjectSpeciesId) } returns false

      assertThrows<ParticipantProjectSpeciesNotFoundException> {
        store.fetchOneById(participantProjectSpeciesId)
      }
    }
  }

  @Nested
  inner class FindAll {
    @Test
    fun `only includes participant project species the user has permission to read`() {
      val participantId1 = insertParticipant()
      val projectId1 = insertProject(participantId = participantId1)
      val speciesId1 = insertSpecies()
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(projectId = projectId1, speciesId = speciesId1)

      val participantId2 = insertParticipant()
      val projectId2 = insertProject(participantId = participantId2)
      val speciesId2 = insertSpecies()
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(projectId = projectId2, speciesId = speciesId2)

      every { user.canReadParticipantProjectSpecies(participantProjectSpeciesId2) } returns false

      assertEquals(
          listOf(participantProjectSpeciesId1),
          store.findAllForProject(projectId1).map { it.id },
          "Participant Project Species IDs")
      assertEquals(
          emptyList<ParticipantProjectSpeciesId>(),
          store.findAllForProject(projectId2).map { it.id },
          "Participant Project Species IDs")
    }
  }

  @Nested
  inner class Create {
    @Test
    fun `creates the entity with the supplied fields`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(participantId = participantId)
      val speciesId = insertSpecies()
      val moduleId = insertModule()
      insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)
      insertCohortModule(cohortId = cohortId, moduleId = moduleId)

      val participantProjectSpecies =
          store.create(
              NewParticipantProjectSpeciesModel(
                  feedback = "feedback",
                  id = null,
                  projectId = projectId,
                  rationale = "rationale",
                  speciesId = speciesId))

      assertEquals(
          ParticipantProjectSpeciesRow(
              feedback = "feedback",
              id = participantProjectSpecies.id,
              projectId = projectId,
              rationale = "rationale",
              speciesId = speciesId,
              submissionStatusId = SubmissionStatus.NotSubmitted),
          participantProjectSpeciesDao.fetchOneById(participantProjectSpecies.id))
    }

    @Test
    fun `does not create the species association if the project is not associated to a participant`() {
      val projectId = insertProject()
      val speciesId = insertSpecies()

      assertThrows<ProjectNotInParticipantException> {
        store.create(
            NewParticipantProjectSpeciesModel(
                feedback = "feedback",
                id = null,
                projectId = projectId,
                rationale = "rationale",
                speciesId = speciesId))
      }
    }

    @Test
    fun `throws an exception if no permission to create a participant project species`() {
      val participantId = insertParticipant()
      val projectId = insertProject(participantId = participantId)
      val speciesId = insertSpecies()

      every { user.canCreateParticipantProjectSpecies(projectId) } returns false

      assertThrows<AccessDeniedException> {
        store.create(
            NewParticipantProjectSpeciesModel(
                feedback = "feedback",
                id = null,
                projectId = projectId,
                rationale = "rationale",
                speciesId = speciesId))
      }
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates the entity with the supplied fields`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(participantId = participantId)
      val speciesId = insertSpecies()
      val moduleId = insertModule()
      insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)
      insertCohortModule(cohortId = cohortId, moduleId = moduleId)
      val participantProjectSpeciesId =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId)

      store.update(participantProjectSpeciesId) {
        it.copy(feedback = "Looks good", submissionStatus = SubmissionStatus.Approved)
      }

      assertEquals(
          ParticipantProjectSpeciesRow(
              feedback = "Looks good",
              id = participantProjectSpeciesId,
              projectId = projectId,
              speciesId = speciesId,
              submissionStatusId = SubmissionStatus.Approved),
          participantProjectSpeciesDao.fetchOneById(participantProjectSpeciesId))
    }

    @Test
    fun `throws exception if the entry does not exist`() {
      every { user.canUpdateParticipantProjectSpecies(any()) } returns true

      assertThrows<ParticipantProjectSpeciesNotFoundException> {
        store.update(ParticipantProjectSpeciesId(1)) { it }
      }
    }

    @Test
    fun `throws exception if no permission to update the entry`() {
      val participantId = insertParticipant()
      val projectId = insertProject(participantId = participantId)
      val speciesId = insertSpecies()
      val participantProjectSpeciesId =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId)

      every { user.canUpdateParticipantProjectSpecies(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.update(participantProjectSpeciesId) { it.copy(feedback = "Needs some work") }
      }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `deletes the supplied list of entities by ID`() {
      val participantId = insertParticipant()
      val projectId = insertProject(participantId = participantId)
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      val speciesId3 = insertSpecies()
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId1)
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId2)
      val participantProjectSpeciesId3 =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId3)

      every { user.canDeleteParticipantProjectSpecies(any()) } returns true

      store.delete(setOf(participantProjectSpeciesId1, participantProjectSpeciesId2))

      assertEquals(
          listOf(
              ParticipantProjectSpeciesRow(
                  feedback = null,
                  id = participantProjectSpeciesId3,
                  projectId = projectId,
                  rationale = null,
                  speciesId = speciesId3,
                  submissionStatusId = SubmissionStatus.NotSubmitted)),
          participantProjectSpeciesDao.findAll())
    }

    @Test
    fun `throws exception if no permission to delete an entry`() {
      val participantId = insertParticipant()
      val projectId = insertProject(participantId = participantId)
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId1)
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId2)

      every { user.canDeleteParticipantProjectSpecies(participantProjectSpeciesId1) } returns true
      every { user.canDeleteParticipantProjectSpecies(participantProjectSpeciesId2) } returns false

      assertThrows<AccessDeniedException> {
        store.delete(setOf(participantProjectSpeciesId1, participantProjectSpeciesId2))
      }

      // If the current user does not have permission to delete any entity in the list,
      // the entire delete fails and there are no changes
      assertEquals(
          listOf(
              ParticipantProjectSpeciesRow(
                  feedback = null,
                  id = participantProjectSpeciesId1,
                  projectId = projectId,
                  rationale = null,
                  speciesId = speciesId1,
                  submissionStatusId = SubmissionStatus.NotSubmitted),
              ParticipantProjectSpeciesRow(
                  feedback = null,
                  id = participantProjectSpeciesId2,
                  projectId = projectId,
                  rationale = null,
                  speciesId = speciesId2,
                  submissionStatusId = SubmissionStatus.NotSubmitted)),
          participantProjectSpeciesDao.findAll())
    }
  }
}
