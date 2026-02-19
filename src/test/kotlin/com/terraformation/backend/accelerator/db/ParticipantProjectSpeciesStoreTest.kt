package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesEditedEvent
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.ParticipantProjectsForSpecies
import com.terraformation.backend.accelerator.model.SpeciesForParticipantProject
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantProjectSpeciesRow
import com.terraformation.backend.db.accelerator.tables.records.ParticipantProjectSpeciesRecord
import com.terraformation.backend.db.default_schema.SpeciesNativeCategory
import com.terraformation.backend.mockUser
import com.terraformation.backend.species.model.ExistingSpeciesModel
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

class ParticipantProjectSpeciesStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  val clock = TestClock()
  val eventPublisher = TestEventPublisher()

  private val store: ParticipantProjectSpeciesStore by lazy {
    ParticipantProjectSpeciesStore(
        clock,
        dslContext,
        eventPublisher,
        participantProjectSpeciesDao,
        projectsDao,
    )
  }

  @BeforeEach
  fun setUp() {
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
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      val speciesId3 = insertSpecies()

      insertParticipantProjectSpecies(
          createdTime = Instant.EPOCH,
          modifiedTime = Instant.EPOCH.plusSeconds(333),
          projectId = projectId,
          speciesId = speciesId1,
      )
      insertParticipantProjectSpecies(
          createdTime = Instant.EPOCH.plusSeconds(2),
          modifiedTime = Instant.EPOCH.plusSeconds(331),
          projectId = projectId,
          speciesId = speciesId2,
      )
      insertParticipantProjectSpecies(
          createdTime = Instant.EPOCH.plusSeconds(1),
          modifiedTime = Instant.EPOCH.plusSeconds(332),
          projectId = projectId,
          speciesId = speciesId3,
      )

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
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      val speciesId3 = insertSpecies()

      insertParticipantProjectSpecies(
          createdTime = Instant.EPOCH.plusSeconds(333),
          modifiedTime = Instant.EPOCH,
          projectId = projectId,
          speciesId = speciesId1,
      )
      insertParticipantProjectSpecies(
          createdTime = Instant.EPOCH.plusSeconds(331),
          modifiedTime = Instant.EPOCH.plusSeconds(2),
          projectId = projectId,
          speciesId = speciesId2,
      )
      insertParticipantProjectSpecies(
          createdTime = Instant.EPOCH.plusSeconds(332),
          modifiedTime = Instant.EPOCH.plusSeconds(1),
          projectId = projectId,
          speciesId = speciesId3,
      )

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
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val speciesId = insertSpecies()
      val participantProjectSpeciesId =
          insertParticipantProjectSpecies(
              feedback = "feedback",
              projectId = projectId,
              rationale = "rationale",
              speciesId = speciesId,
          )

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
          store.fetchOneById(participantProjectSpeciesId),
      )
    }

    @Test
    fun `throws exception if no permission to read participant project species`() {
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val speciesId = insertSpecies()
      val participantProjectSpeciesId =
          insertParticipantProjectSpecies(
              feedback = "feedback",
              projectId = projectId,
              rationale = "rationale",
              speciesId = speciesId,
          )

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
      val moduleId = insertModule()
      val deliverableId =
          insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)
      // This one should not be returned because it is not a species type deliverable
      insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Document)

      val projectId1 = insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertProjectModule()
      val projectId2 = insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertProjectModule()

      val speciesId = insertSpecies()
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(projectId = projectId1, speciesId = speciesId)
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(projectId = projectId2, speciesId = speciesId)

      assertEquals(
          listOf(
              ParticipantProjectsForSpecies(
                  deliverableId = deliverableId,
                  participantProjectSpeciesId = participantProjectSpeciesId1,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  participantProjectSpeciesNativeCategory = null,
                  projectId = projectId1,
                  projectName = "Project 1",
                  speciesId = speciesId,
              ),
              ParticipantProjectsForSpecies(
                  deliverableId = deliverableId,
                  participantProjectSpeciesId = participantProjectSpeciesId2,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  participantProjectSpeciesNativeCategory = null,
                  projectId = projectId2,
                  projectName = "Project 2",
                  speciesId = speciesId,
              ),
          ),
          store.fetchParticipantProjectsForSpecies(speciesId),
      )
    }

    @Test
    fun `fetches the projects a species is associated to by species ID with the most recent deliverable if there is no active deliverable`() {
      // This module goes from 0 to 6 days
      val moduleIdOld = insertModule()
      insertDeliverable(moduleId = moduleIdOld, deliverableTypeId = DeliverableType.Species)

      // This module goes from 7 to 13 days
      val moduleIdMostRecent = insertModule()
      val deliverableIdMostRecent =
          insertDeliverable(
              moduleId = moduleIdMostRecent,
              deliverableTypeId = DeliverableType.Species,
          )

      // The clock is between these two modules

      // This module goes from 21 to 27 days
      val moduleIdFuture = insertModule()
      insertDeliverable(moduleId = moduleIdFuture, deliverableTypeId = DeliverableType.Species)

      // Between the most recent and future module
      clock.instant = Instant.EPOCH.plus(20, ChronoUnit.DAYS)

      val projectId1 = insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertProjectModule(moduleId = moduleIdOld)
      insertProjectModule(moduleId = moduleIdMostRecent)
      insertProjectModule(
          moduleId = moduleIdFuture,
          startDate = LocalDate.EPOCH.plusDays(21),
          endDate = LocalDate.EPOCH.plusDays(27),
      )
      val projectId2 = insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertProjectModule(moduleId = moduleIdOld)
      insertProjectModule(moduleId = moduleIdMostRecent)
      insertProjectModule(
          moduleId = moduleIdFuture,
          startDate = LocalDate.EPOCH.plusDays(21),
          endDate = LocalDate.EPOCH.plusDays(27),
      )

      val speciesId = insertSpecies()
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(projectId = projectId1, speciesId = speciesId)
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(projectId = projectId2, speciesId = speciesId)

      assertEquals(
          listOf(
              ParticipantProjectsForSpecies(
                  deliverableId = deliverableIdMostRecent,
                  participantProjectSpeciesId = participantProjectSpeciesId1,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  participantProjectSpeciesNativeCategory = null,
                  projectId = projectId1,
                  projectName = "Project 1",
                  speciesId = speciesId,
              ),
              ParticipantProjectsForSpecies(
                  deliverableId = deliverableIdMostRecent,
                  participantProjectSpeciesId = participantProjectSpeciesId2,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  participantProjectSpeciesNativeCategory = null,
                  projectId = projectId2,
                  projectName = "Project 2",
                  speciesId = speciesId,
              ),
          ),
          store.fetchParticipantProjectsForSpecies(speciesId),
      )
    }

    @Test
    fun `fetches the projects a species is associated to by species ID without an active or recent deliverable`() {
      val projectId1 = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val projectId2 = insertProject(phase = CohortPhase.Phase0DueDiligence)

      val speciesId = insertSpecies()
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(projectId = projectId1, speciesId = speciesId)
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(projectId = projectId2, speciesId = speciesId)

      assertEquals(
          listOf(
              ParticipantProjectsForSpecies(
                  deliverableId = null,
                  participantProjectSpeciesId = participantProjectSpeciesId1,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  participantProjectSpeciesNativeCategory = null,
                  projectId = projectId1,
                  projectName = "Project 1",
                  speciesId = speciesId,
              ),
              ParticipantProjectsForSpecies(
                  deliverableId = null,
                  participantProjectSpeciesId = participantProjectSpeciesId2,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  participantProjectSpeciesNativeCategory = null,
                  projectId = projectId2,
                  projectName = "Project 2",
                  speciesId = speciesId,
              ),
          ),
          store.fetchParticipantProjectsForSpecies(speciesId),
      )
    }

    @Test
    fun `does not include active deliverable ID if the user does not have permission to view project deliverables`() {
      val moduleId = insertModule()
      insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)

      val projectId1 = insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertProjectModule()
      val projectId2 = insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertProjectModule()

      val speciesId = insertSpecies()
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(projectId = projectId1, speciesId = speciesId)
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(projectId = projectId2, speciesId = speciesId)

      every { user.canReadProjectDeliverables(any()) } returns false

      assertEquals(
          listOf(
              ParticipantProjectsForSpecies(
                  deliverableId = null,
                  participantProjectSpeciesId = participantProjectSpeciesId1,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  participantProjectSpeciesNativeCategory = null,
                  projectId = projectId1,
                  projectName = "Project 1",
                  speciesId = speciesId,
              ),
              ParticipantProjectsForSpecies(
                  deliverableId = null,
                  participantProjectSpeciesId = participantProjectSpeciesId2,
                  participantProjectSpeciesSubmissionStatus = SubmissionStatus.NotSubmitted,
                  participantProjectSpeciesNativeCategory = null,
                  projectId = projectId2,
                  projectName = "Project 2",
                  speciesId = speciesId,
              ),
          ),
          store.fetchParticipantProjectsForSpecies(speciesId),
      )
    }

    @Test
    fun `returns an empty list of the user does not have permission to read the project`() {
      val moduleId = insertModule()
      insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)

      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertProjectModule()

      val speciesId = insertSpecies()
      insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId)

      every { user.canReadProject(any()) } returns false

      assertEquals(
          emptyList<ParticipantProjectsForSpecies>(),
          store.fetchParticipantProjectsForSpecies(speciesId),
      )
    }
  }

  @Nested
  inner class FetchSpeciesForParticipantProject {
    @Test
    fun `fetches the species and participant project species data associated to a participant project`() {
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)

      val speciesId1 = insertSpecies(scientificName = "Acacia Kochi")
      val speciesId2 = insertSpecies(scientificName = "Juniperus scopulorum")
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId1)
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId2)

      val userId = currentUser().userId
      val now = Instant.EPOCH

      assertEquals(
          listOf(
              SpeciesForParticipantProject(
                  participantProjectSpecies =
                      ExistingParticipantProjectSpeciesModel(
                          createdBy = userId,
                          createdTime = now,
                          id = participantProjectSpeciesId1,
                          modifiedBy = userId,
                          modifiedTime = now,
                          projectId = projectId,
                          speciesId = speciesId1,
                          submissionStatus = SubmissionStatus.NotSubmitted,
                      ),
                  project =
                      ExistingProjectModel(
                          createdBy = userId,
                          createdTime = now,
                          id = projectId,
                          modifiedBy = userId,
                          modifiedTime = now,
                          name = "Project 1",
                          organizationId = inserted.organizationId,
                          phase = CohortPhase.Phase0DueDiligence,
                      ),
                  species =
                      ExistingSpeciesModel(
                          commonName = null,
                          createdTime = now,
                          id = speciesId1,
                          modifiedTime = now,
                          organizationId = inserted.organizationId,
                          scientificName = "Acacia Kochi",
                      ),
              ),
              SpeciesForParticipantProject(
                  participantProjectSpecies =
                      ExistingParticipantProjectSpeciesModel(
                          createdBy = userId,
                          createdTime = now,
                          id = participantProjectSpeciesId2,
                          modifiedBy = userId,
                          modifiedTime = now,
                          projectId = projectId,
                          speciesId = speciesId2,
                          submissionStatus = SubmissionStatus.NotSubmitted,
                      ),
                  project =
                      ExistingProjectModel(
                          createdBy = userId,
                          createdTime = now,
                          id = projectId,
                          modifiedBy = userId,
                          modifiedTime = now,
                          name = "Project 1",
                          organizationId = inserted.organizationId,
                          phase = CohortPhase.Phase0DueDiligence,
                      ),
                  species =
                      ExistingSpeciesModel(
                          commonName = null,
                          createdTime = now,
                          id = speciesId2,
                          modifiedTime = now,
                          organizationId = inserted.organizationId,
                          scientificName = "Juniperus scopulorum",
                      ),
              ),
          ),
          store.fetchSpeciesForParticipantProject(projectId),
      )
    }

    @Test
    fun `returns an empty list of the user does not have permission to read the project`() {
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)

      val speciesId = insertSpecies()
      insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId)

      every { user.canReadProject(any()) } returns false

      assertEquals(
          emptyList<SpeciesForParticipantProject>(),
          store.fetchSpeciesForParticipantProject(projectId),
      )
    }
  }

  @Nested
  inner class FindAll {
    @Test
    fun `only includes participant project species the user has permission to read`() {
      val projectId1 = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val speciesId1 = insertSpecies()
      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(projectId = projectId1, speciesId = speciesId1)

      val projectId2 = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val speciesId2 = insertSpecies()
      val participantProjectSpeciesId2 =
          insertParticipantProjectSpecies(projectId = projectId2, speciesId = speciesId2)

      every { user.canReadParticipantProjectSpecies(participantProjectSpeciesId2) } returns false

      assertEquals(
          listOf(participantProjectSpeciesId1),
          store.findAllForProject(projectId1).map { it.id },
          "Participant Project Species IDs",
      )
      assertEquals(
          emptyList<ParticipantProjectSpeciesId>(),
          store.findAllForProject(projectId2).map { it.id },
          "Participant Project Species IDs",
      )
    }
  }

  @Nested
  inner class Create {
    @Test
    fun `creates the entity with the supplied fields`() {
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val speciesId = insertSpecies()

      val participantProjectSpecies =
          store.create(
              NewParticipantProjectSpeciesModel(
                  feedback = "feedback",
                  id = null,
                  projectId = projectId,
                  rationale = "rationale",
                  speciesId = speciesId,
              )
          )

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
              submissionStatusId = SubmissionStatus.NotSubmitted,
          ),
          participantProjectSpeciesDao.fetchOneById(participantProjectSpecies.id),
      )
    }

    @Test
    fun `does not create the species association if the project is not associated to a cohort`() {
      val projectId = insertProject()
      val speciesId = insertSpecies()

      assertThrows<ProjectNotInCohortPhaseException> {
        store.create(
            NewParticipantProjectSpeciesModel(
                feedback = "feedback",
                id = null,
                projectId = projectId,
                rationale = "rationale",
                speciesId = speciesId,
            )
        )
      }
    }

    @Test
    fun `throws an exception if no permission to create a participant project species`() {
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val speciesId = insertSpecies()

      every { user.canCreateParticipantProjectSpecies(projectId) } returns false

      assertThrows<AccessDeniedException> {
        store.create(
            NewParticipantProjectSpeciesModel(
                feedback = "feedback",
                id = null,
                projectId = projectId,
                rationale = "rationale",
                speciesId = speciesId,
            )
        )
      }
    }

    @Test
    fun `creates an entity for each project ID and species ID pairing`() {
      val projectId1 = insertProject(phase = CohortPhase.Phase1FeasibilityStudy)
      val projectId2 = insertProject(phase = CohortPhase.Phase2PlanAndScale)
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()

      every { user.canCreateParticipantProjectSpecies(projectId1) } returns true
      every { user.canCreateParticipantProjectSpecies(projectId2) } returns true

      store.create(setOf(projectId1, projectId2), setOf(speciesId1, speciesId2))

      val userId = user.userId
      val now = Instant.EPOCH

      assertTableEquals(
          listOf(
              ParticipantProjectSpeciesRecord(
                  createdBy = userId,
                  createdTime = now,
                  feedback = null,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId1,
                  rationale = null,
                  speciesId = speciesId1,
                  submissionStatusId = SubmissionStatus.NotSubmitted,
              ),
              ParticipantProjectSpeciesRecord(
                  createdBy = userId,
                  createdTime = now,
                  feedback = null,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId1,
                  rationale = null,
                  speciesId = speciesId2,
                  submissionStatusId = SubmissionStatus.NotSubmitted,
              ),
              ParticipantProjectSpeciesRecord(
                  createdBy = userId,
                  createdTime = now,
                  feedback = null,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId2,
                  rationale = null,
                  speciesId = speciesId1,
                  submissionStatusId = SubmissionStatus.NotSubmitted,
              ),
              ParticipantProjectSpeciesRecord(
                  createdBy = userId,
                  createdTime = now,
                  feedback = null,
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId2,
                  rationale = null,
                  speciesId = speciesId2,
                  submissionStatusId = SubmissionStatus.NotSubmitted,
              ),
          )
      )
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates the entity with the supplied fields`() {
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val speciesId = insertSpecies()
      val participantProjectSpeciesId =
          insertParticipantProjectSpecies(projectId = projectId, speciesId = speciesId)

      store.update(participantProjectSpeciesId) {
        it.copy(
            feedback = "Looks good",
            internalComment = "We should approve",
            speciesNativeCategory = SpeciesNativeCategory.Native,
            submissionStatus = SubmissionStatus.Approved,
        )
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
              submissionStatusId = SubmissionStatus.Approved,
          ),
          participantProjectSpeciesDao.fetchOneById(participantProjectSpeciesId),
      )

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
                      submissionStatus = SubmissionStatus.Approved,
                  ),
              oldParticipantProjectSpecies =
                  ExistingParticipantProjectSpeciesModel(
                      createdBy = userId,
                      createdTime = now,
                      id = participantProjectSpeciesId,
                      modifiedBy = userId,
                      modifiedTime = now,
                      projectId = projectId,
                      speciesId = speciesId,
                      submissionStatus = SubmissionStatus.NotSubmitted,
                  ),
              projectId = projectId,
          )
      )
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
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
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
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
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
                  submissionStatusId = SubmissionStatus.NotSubmitted,
              )
          ),
          participantProjectSpeciesDao.findAll(),
      )
    }

    @Test
    fun `throws exception if no permission to delete an entry`() {
      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
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
      assertTableEquals(
          listOf(
              ParticipantProjectSpeciesRecord(
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
                  submissionStatusId = SubmissionStatus.NotSubmitted,
              ),
              ParticipantProjectSpeciesRecord(
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
                  submissionStatusId = SubmissionStatus.NotSubmitted,
              ),
          )
      )
    }
  }
}
