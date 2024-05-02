package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.db.DatabaseTest
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

class ParticipantProjectSpeciesStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val store: ParticipantProjectSpeciesStore by lazy {
    ParticipantProjectSpeciesStore(dslContext, participantProjectSpeciesDao, projectsDao)
  }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()

    every { user.canReadParticipantProjectSpecies(any()) } returns true
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
      val participantId = insertParticipant()
      val projectId = insertProject(participantId = participantId)
      val speciesId = insertSpecies()

      every { user.canCreateParticipantProjectSpecies(projectId) } returns true

      val participantProjectSpecies =
          store.create(
              NewParticipantProjectSpeciesModel(
                  feedback = "feedback",
                  id = null,
                  projectId = projectId,
                  rationale = "rationale",
                  speciesId = speciesId,
                  submissionStatus = SubmissionStatus.NotSubmitted))

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

      every { user.canCreateParticipantProjectSpecies(projectId) } returns true

      assertThrows<ProjectNotInParticipantException> {
        store.create(
            NewParticipantProjectSpeciesModel(
                feedback = "feedback",
                id = null,
                projectId = projectId,
                rationale = "rationale",
                speciesId = speciesId,
                submissionStatus = SubmissionStatus.NotSubmitted))
      }
    }
  }
}
