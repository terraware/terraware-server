package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.ParticipantProjectSpeciesService
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionsRow
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ParticipantProjectSpeciesServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val service: ParticipantProjectSpeciesService by lazy {
    ParticipantProjectSpeciesService(
        dslContext,
        ParticipantProjectSpeciesStore(dslContext, participantProjectSpeciesDao, projectsDao),
        SubmissionStore(clock, dslContext, eventPublisher))
  }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()

    every { user.canCreateParticipantProjectSpecies(any()) } returns true
    every { user.canCreateSubmission(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadProjectDeliverables(any()) } returns true
    every { user.canReadSubmission(any()) } returns true
  }

  @Nested
  inner class Create {
    @Test
    fun `creates a submission for the project and deliverable if one does not exist for the active module when a species is added to a project`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(participantId = participantId)
      val speciesId = insertSpecies()
      val moduleId = insertModule()
      val deliverableId =
          insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)
      insertCohortModule(cohortId = cohortId, moduleId = moduleId)

      service.create(
          NewParticipantProjectSpeciesModel(
              feedback = "feedback",
              id = null,
              projectId = projectId,
              rationale = "rationale",
              speciesId = speciesId))

      val userId = currentUser().userId
      val now = clock.instant

      assertEquals(
          listOf(
              SubmissionsRow(
                  createdBy = userId,
                  createdTime = now,
                  deliverableId = deliverableId,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId,
                  submissionStatusId = SubmissionStatus.NotSubmitted)),
          submissionsDao.fetchByDeliverableId(deliverableId).map { it.copy(id = null) })
    }

    @Test
    fun `does not create another submission for a project if a deliverable submission for the active module already exists`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(participantId = participantId)
      val speciesId = insertSpecies()
      val moduleId = insertModule()
      val deliverableId =
          insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)
      insertCohortModule(cohortId = cohortId, moduleId = moduleId)
      val submissionId =
          insertSubmission(
              deliverableId = deliverableId, feedback = "So far so good", projectId = projectId)

      service.create(
          NewParticipantProjectSpeciesModel(
              feedback = "feedback",
              id = null,
              projectId = projectId,
              rationale = "rationale",
              speciesId = speciesId))

      val userId = currentUser().userId
      val now = clock.instant

      assertEquals(
          listOf(
              SubmissionsRow(
                  createdBy = userId,
                  createdTime = now,
                  deliverableId = deliverableId,
                  feedback = "So far so good",
                  id = submissionId,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId,
                  submissionStatusId = SubmissionStatus.NotSubmitted)),
          submissionsDao.fetchByDeliverableId(deliverableId))
    }

    @Test
    fun `creates an entity for each project ID and species ID pairing and ensures there is a submission for each project deliverable`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val moduleId = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleId)
      val deliverableId =
          insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)

      val projectId1 = insertProject(participantId = participantId)
      val projectId2 = insertProject(participantId = participantId)
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()

      service.create(setOf(projectId1, projectId2), setOf(speciesId1, speciesId2))

      val userId = currentUser().userId
      val now = clock.instant

      assertEquals(
          listOf(
              SubmissionsRow(
                  createdBy = userId,
                  createdTime = now,
                  deliverableId = deliverableId,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId1,
                  submissionStatusId = SubmissionStatus.NotSubmitted),
              SubmissionsRow(
                  createdBy = userId,
                  createdTime = now,
                  deliverableId = deliverableId,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId2,
                  submissionStatusId = SubmissionStatus.NotSubmitted)),
          submissionsDao.fetchByDeliverableId(deliverableId).map { it.copy(id = null) })
    }
  }
}
