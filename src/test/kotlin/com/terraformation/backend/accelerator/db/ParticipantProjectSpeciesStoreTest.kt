package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesEditedEvent
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.ParticipantProjectsForSpecies
import com.terraformation.backend.accelerator.model.SpeciesForParticipantProject
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantProjectSpeciesRow
import com.terraformation.backend.db.default_schema.SpeciesNativeCategory
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

class ParticipantProjectSpeciesStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  val clock = TestClock()
  val eventPublisher = TestEventPublisher()

  private val store: ParticipantProjectSpeciesStore by lazy {
    ParticipantProjectSpeciesStore(
        clock, dslContext, eventPublisher, participantProjectSpeciesDao, projectsDao)
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
  inner class FetchLastCreatedSpeciesTime {
    @Test
    fun `fetches the last created species time for a given project`() {
      val participantId = insertParticipant()
      val projectId = insertProject(participantId = participantId)
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      val speciesId3 = insertSpecies()

      insertParticipantProjectSpecies(
          createdTime = Instant.EPOCH,
          modifiedTime = Instant.EPOCH.plusSeconds(333),
          projectId = projectId,
          speciesId = speciesId1)
      insertParticipantProjectSpecies(
          createdTime = Instant.EPOCH.plusSeconds(2),
          modifiedTime = Instant.EPOCH.plusSeconds(331),
          projectId = projectId,
          speciesId = speciesId2)
      insertParticipantProjectSpecies(
          createdTime = Instant.EPOCH.plusSeconds(1),
          modifiedTime = Instant.EPOCH.plusSeconds(332),
          projectId = projectId,
          speciesId = speciesId3)

      assertEquals(Instant.EPOCH.plusSeconds(2), store.fetchLastCreatedSpeciesTime(projectId))
    }

    @Test
    fun `throws an exception if no permission to read the project`() {
      val projectId = insertProject()

      every { user.canReadProject(projectId) } returns false

      assertThrows<ProjectNotFoundException> { store.fetchLastCreatedSpeciesTime(projectId) }
    }
  }

  @Nested
  inner class FetchLastModifiedSpeciesTime {
    @Test
    fun `fetches the last updated species time for a given project`() {
      val participantId = insertParticipant()
      val projectId = insertProject(participantId = participantId)
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      val speciesId3 = insertSpecies()

      insertParticipantProjectSpecies(
          createdTime = Instant.EPOCH.plusSeconds(333),
          modifiedTime = Instant.EPOCH,
          projectId = projectId,
          speciesId = speciesId1)
      insertParticipantProjectSpecies(
          createdTime = Instant.EPOCH.plusSeconds(331),
          modifiedTime = Instant.EPOCH.plusSeconds(2),
          projectId = projectId,
          speciesId = speciesId2)
      insertParticipantProjectSpecies(
          createdTime = Instant.EPOCH.plusSeconds(332),
          modifiedTime = Instant.EPOCH.plusSeconds(1),
          projectId = projectId,
          speciesId = speciesId3)

      assertEquals(Instant.EPOCH.plusSeconds(2), store.fetchLastModifiedSpeciesTime(projectId))
    }

    @Test
    fun `throws an exception if no permission to read the project`() {
      val projectId = insertProject()

      every { user.canReadProject(projectId) } returns false

      assertThrows<ProjectNotFoundException> { store.fetchLastModifiedSpeciesTime(projectId) }
    }
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

      val userId = user.userId
      val now = Instant.EPOCH

      assertEquals(
          ExistingParticipantProjectSpeciesModel(
              createdBy = userId,
              createdTime = now,
              feedback = "feedback",
              id = participantProjectSpeciesId,
              modifiedBy = userId,
              modifiedTime = now,
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
  inner class FetchParticipantProjectsForSpecies {
    @Test
    fun `fetches the projects a species is associated to by species ID with an active deliverable`() {
      val cohortId = insertCohort()
      val moduleId = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleId)
      val deliverableId =
          insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)
      // This one should not be returned because it is not a species type deliverable
      insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Document)

      val participantId = insertParticipant(cohortId = cohortId)
      val projectId1 = insertProject(participantId = participantId)
      val projectId2 = insertProject(participantId = participantId)

      val speciesId = insertSpecies()
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(projectId = projectId1, speciesId = speciesId)
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(projectId = projectId2, speciesId = speciesId)

      assertEquals(
          listOf(
              ParticipantProjectsForSpecies(
                  activeDeliverableId = deliverableId,
                  participantProjectSpeciesId = participantProjectSpeciesId1,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  projectId = projectId1,
                  projectName = "Project 1",
                  speciesId = speciesId),
              ParticipantProjectsForSpecies(
                  activeDeliverableId = deliverableId,
                  participantProjectSpeciesId = participantProjectSpeciesId2,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  projectId = projectId2,
                  projectName = "Project 2",
                  speciesId = speciesId)),
          store.fetchParticipantProjectsForSpeciesDeliverables(inserted.organizationId, speciesId))
    }

    @Test
    fun `fetches the projects a species is associated to by species ID without an active deliverable`() {
      val cohortId = insertCohort()

      val moduleIdOld = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleIdOld)
      insertDeliverable(moduleId = moduleIdOld, deliverableTypeId = DeliverableType.Species)

      // Ensure the previously created module is in the past, so there is no active deliverable
      clock.instant = Instant.EPOCH.plus(7, ChronoUnit.DAYS)

      val participantId = insertParticipant(cohortId = cohortId)
      val projectId1 = insertProject(participantId = participantId)
      val projectId2 = insertProject(participantId = participantId)

      val speciesId = insertSpecies()
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(projectId = projectId1, speciesId = speciesId)
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(projectId = projectId2, speciesId = speciesId)

      assertEquals(
          listOf(
              ParticipantProjectsForSpecies(
                  activeDeliverableId = null,
                  participantProjectSpeciesId = participantProjectSpeciesId1,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  projectId = projectId1,
                  projectName = "Project 1",
                  speciesId = speciesId),
              ParticipantProjectsForSpecies(
                  activeDeliverableId = null,
                  participantProjectSpeciesId = participantProjectSpeciesId2,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  projectId = projectId2,
                  projectName = "Project 2",
                  speciesId = speciesId)),
          store.fetchParticipantProjectsForSpeciesDeliverables(inserted.organizationId, speciesId))
    }

    @Test
    fun `does not include active deliverable ID if the user does not have permission to view project deliverables`() {
      val cohortId = insertCohort()
      val moduleId = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleId)
      insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)

      val participantId = insertParticipant(cohortId = cohortId)
      val projectId1 = insertProject(participantId = participantId)
      val projectId2 = insertProject(participantId = participantId)

      val speciesId = insertSpecies()
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(projectId = projectId1, speciesId = speciesId)
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(projectId = projectId2, speciesId = speciesId)

      every { user.canReadProjectDeliverables(any()) } returns false

      assertEquals(
          listOf(
              ParticipantProjectsForSpecies(
                  activeDeliverableId = null,
                  participantProjectSpeciesId = participantProjectSpeciesId1,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  projectId = projectId1,
                  projectName = "Project 1",
                  speciesId = speciesId),
              ParticipantProjectsForSpecies(
                  activeDeliverableId = null,
                  participantProjectSpeciesId = participantProjectSpeciesId2,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  projectId = projectId2,
                  projectName = "Project 2",
                  speciesId = speciesId)),
          store.fetchParticipantProjectsForSpeciesDeliverables(inserted.organizationId, speciesId))
    }

    @Test
    fun `returns an empty list of the user does not have permission to read the project`() {
      val cohortId = insertCohort()
      val moduleId = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleId)
      insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)

      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(participantId = participantId)

      val speciesId = insertSpecies()
      insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId)

      every { user.canReadProject(any()) } returns false

      assertEquals(
          emptyList<ParticipantProjectsForSpecies>(),
          store.fetchParticipantProjectsForSpeciesDeliverables(inserted.organizationId, speciesId))
    }
  }

  @Nested
  inner class FetchSpeciesForParticipantProject {
    @Test
    fun `fetches the species and participant project species data associated to a participant project`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(participantId = participantId)

      val speciesId1 = insertSpecies(scientificName = "Acacia Kochi")
      val speciesId2 = insertSpecies(scientificName = "Acacia Koa")
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId1)
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId2)

      assertEquals(
          listOf(
              SpeciesForParticipantProject(
                  participantProjectSpeciesId = participantProjectSpeciesId1,
                  participantProjectSpeciesRationale = null,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  projectId = projectId,
                  speciesId = speciesId1,
                  speciesScientificName = "Acacia Kochi",
                  speciesCommonName = null),
              SpeciesForParticipantProject(
                  participantProjectSpeciesId = participantProjectSpeciesId2,
                  participantProjectSpeciesRationale = null,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  projectId = projectId,
                  speciesId = speciesId2,
                  speciesScientificName = "Acacia Koa",
                  speciesCommonName = null)),
          store.fetchSpeciesForParticipantProjects(projectId))
    }

    @Test
    fun `returns an empty list of the user does not have permission to read the project`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(participantId = participantId)

      val speciesId = insertSpecies()
      insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId)

      every { user.canReadProject(any()) } returns false

      assertEquals(
          emptyList<SpeciesForParticipantProject>(),
          store.fetchSpeciesForParticipantProjects(projectId))
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

      val participantProjectSpecies =
          store.create(
              NewParticipantProjectSpeciesModel(
                  feedback = "feedback",
                  id = null,
                  projectId = projectId,
                  rationale = "rationale",
                  speciesId = speciesId))

      val userId = user.userId
      val now = Instant.EPOCH

      assertEquals(
          ParticipantProjectSpeciesRow(
              createdBy = userId,
              createdTime = now,
              feedback = "feedback",
              id = participantProjectSpecies.id,
              modifiedBy = userId,
              modifiedTime = now,
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

    @Test
    fun `creates an entity for each project ID and species ID pairing`() {
      val participantId = insertParticipant()
      val projectId1 = insertProject(participantId = participantId)
      val projectId2 = insertProject(participantId = participantId)
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()

      every { user.canCreateParticipantProjectSpecies(projectId1) } returns true
      every { user.canCreateParticipantProjectSpecies(projectId2) } returns true

      store.create(setOf(projectId1, projectId2), setOf(speciesId1, speciesId2))

      val userId = user.userId
      val now = Instant.EPOCH

      assertEquals(
          listOf(
              ParticipantProjectSpeciesRow(
                  createdBy = userId,
                  createdTime = now,
                  feedback = null,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId1,
                  rationale = null,
                  speciesId = speciesId1,
                  submissionStatusId = SubmissionStatus.NotSubmitted),
              ParticipantProjectSpeciesRow(
                  createdBy = userId,
                  createdTime = now,
                  feedback = null,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId1,
                  rationale = null,
                  speciesId = speciesId2,
                  submissionStatusId = SubmissionStatus.NotSubmitted),
              ParticipantProjectSpeciesRow(
                  createdBy = userId,
                  createdTime = now,
                  feedback = null,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId2,
                  rationale = null,
                  speciesId = speciesId1,
                  submissionStatusId = SubmissionStatus.NotSubmitted),
              ParticipantProjectSpeciesRow(
                  createdBy = userId,
                  createdTime = now,
                  feedback = null,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId2,
                  rationale = null,
                  speciesId = speciesId2,
                  submissionStatusId = SubmissionStatus.NotSubmitted)),
          participantProjectSpeciesDao.findAll().map { it.copy(id = null) })
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates the entity with the supplied fields`() {
      val participantId = insertParticipant()
      val projectId = insertProject(participantId = participantId)
      val speciesId = insertSpecies()
      val participantProjectSpeciesId =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId)

      store.update(participantProjectSpeciesId) {
        it.copy(
            feedback = "Looks good",
            internalComment = "We should approve",
            speciesNativeCategory = SpeciesNativeCategory.Native,
            submissionStatus = SubmissionStatus.Approved)
      }

      val userId = user.userId
      val now = Instant.EPOCH

      assertEquals(
          ParticipantProjectSpeciesRow(
              createdBy = userId,
              createdTime = now,
              feedback = "Looks good",
              id = participantProjectSpeciesId,
              internalComment = "We should approve",
              modifiedBy = userId,
              modifiedTime = now,
              projectId = projectId,
              speciesId = speciesId,
              speciesNativeCategoryId = SpeciesNativeCategory.Native,
              submissionStatusId = SubmissionStatus.Approved),
          participantProjectSpeciesDao.fetchOneById(participantProjectSpeciesId))

      eventPublisher.assertEventPublished(
          ParticipantProjectSpeciesEditedEvent(
              newParticipantProjectSpecies =
                  ExistingParticipantProjectSpeciesModel(
                      createdBy = userId,
                      createdTime = now,
                      id = participantProjectSpeciesId,
                      internalComment = "We should approve",
                      feedback = "Looks good",
                      modifiedBy = userId,
                      modifiedTime = now,
                      projectId = projectId,
                      speciesId = speciesId,
                      speciesNativeCategory = SpeciesNativeCategory.Native,
                      submissionStatus = SubmissionStatus.Approved),
              oldParticipantProjectSpecies =
                  ExistingParticipantProjectSpeciesModel(
                      createdBy = userId,
                      createdTime = now,
                      id = participantProjectSpeciesId,
                      modifiedBy = userId,
                      modifiedTime = now,
                      projectId = projectId,
                      speciesId = speciesId,
                      submissionStatus = SubmissionStatus.NotSubmitted),
              projectId = projectId))
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

      val userId = user.userId
      val now = Instant.EPOCH

      assertEquals(
          listOf(
              ParticipantProjectSpeciesRow(
                  createdBy = userId,
                  createdTime = now,
                  feedback = null,
                  id = participantProjectSpeciesId3,
                  internalComment = null,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId,
                  rationale = null,
                  speciesId = speciesId3,
                  speciesNativeCategoryId = null,
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

      val userId = user.userId
      val now = Instant.EPOCH

      // If the current user does not have permission to delete any entity in the list,
      // the entire delete fails and there are no changes
      assertEquals(
          listOf(
              ParticipantProjectSpeciesRow(
                  createdBy = userId,
                  createdTime = now,
                  feedback = null,
                  id = participantProjectSpeciesId1,
                  internalComment = null,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId,
                  rationale = null,
                  speciesId = speciesId1,
                  speciesNativeCategoryId = null,
                  submissionStatusId = SubmissionStatus.NotSubmitted),
              ParticipantProjectSpeciesRow(
                  createdBy = userId,
                  createdTime = now,
                  feedback = null,
                  id = participantProjectSpeciesId2,
                  internalComment = null,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId,
                  rationale = null,
                  speciesId = speciesId2,
                  speciesNativeCategoryId = null,
                  submissionStatusId = SubmissionStatus.NotSubmitted)),
          participantProjectSpeciesDao.findAll())
    }
  }
}
