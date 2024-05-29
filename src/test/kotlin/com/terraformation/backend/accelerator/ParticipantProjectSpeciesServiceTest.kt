package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ParticipantProjectSpeciesStore
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.event.DeliverableStatusUpdatedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedEvent
import com.terraformation.backend.accelerator.model.NewParticipantProjectSpeciesModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantProjectSpeciesRow
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionSnapshotsRow
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionsRow
import com.terraformation.backend.db.default_schema.SpeciesNativeCategory
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.mockUser
import com.terraformation.backend.species.event.SpeciesEditedEvent
import com.terraformation.backend.species.model.ExistingSpeciesModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ParticipantProjectSpeciesServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val fileStore = InMemoryFileStore()
  private val thumbnailStore: ThumbnailStore = mockk()
  private val fileService: FileService by lazy {
    FileService(dslContext, clock, mockk(), filesDao, fileStore, thumbnailStore)
  }

  private val participantProjectSpeciesStore: ParticipantProjectSpeciesStore by lazy {
    spyk(
        ParticipantProjectSpeciesStore(
            clock, dslContext, eventPublisher, participantProjectSpeciesDao, projectsDao))
  }

  private val submissionStore: SubmissionStore by lazy {
    spyk(SubmissionStore(clock, dslContext, eventPublisher))
  }

  private val service: ParticipantProjectSpeciesService by lazy {
    ParticipantProjectSpeciesService(
        clock,
        dslContext,
        deliverablesDao,
        eventPublisher,
        fileService,
        participantProjectSpeciesStore,
        submissionStore)
  }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()

    every { user.canCreateParticipantProjectSpecies(any()) } returns true
    every { user.canCreateSubmission(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadParticipantProjectSpecies(any()) } returns true
    every { user.canReadProjectDeliverables(any()) } returns true
    every { user.canReadSubmission(any()) } returns true
    every { user.canUpdateParticipantProjectSpecies(any()) } returns true
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

      val existingModel =
          service.create(
              NewParticipantProjectSpeciesModel(
                  feedback = "feedback",
                  id = null,
                  modifiedTime = Instant.EPOCH,
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

      assertEquals(
          listOf(
              ParticipantProjectSpeciesRow(
                  createdBy = userId,
                  createdTime = now,
                  feedback = "feedback",
                  modifiedBy = userId,
                  modifiedTime = now,
                  projectId = projectId,
                  rationale = "rationale",
                  speciesId = speciesId,
                  submissionStatusId = SubmissionStatus.NotSubmitted)),
          participantProjectSpeciesDao.findAll().map { it.copy(id = null) })

      eventPublisher.assertEventPublished(
          ParticipantProjectSpeciesAddedEvent(
              deliverableId = deliverableId, participantProjectSpecies = existingModel))
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

      val existingModels =
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

      eventPublisher.assertEventsPublished(
          existingModels.toSet().map {
            ParticipantProjectSpeciesAddedEvent(
                deliverableId = deliverableId, participantProjectSpecies = it)
          })

      // This test is to ensure that we do not over-fetch deliverable submissions for projects
      // that have multiple species added to them. This does not test for correct-ness of the
      // create operations
      verify(exactly = 1) {
        submissionStore.fetchActiveSpeciesDeliverableSubmission(projectId1)
        submissionStore.fetchActiveSpeciesDeliverableSubmission(projectId2)
      }
    }
  }

  @Nested
  inner class UpdateStatusEvent {
    @Test
    fun `updates the status for the participant project species if species fields are edited by non-accelerator-users`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)

      val projectId1 = insertProject(participantId = participantId)
      val projectId2 = insertProject(participantId = participantId)
      val projectId3 = insertProject(participantId = participantId)
      val speciesId = insertSpecies()

      val participantProjectSpeciesId1 =
          insertParticipantProjectSpecies(
              projectId = projectId1,
              speciesId = speciesId,
              submissionStatus = SubmissionStatus.Approved)
      // This one is ignored since it is already "In Review"
      insertParticipantProjectSpecies(
          projectId = projectId2,
          speciesId = speciesId,
          submissionStatus = SubmissionStatus.InReview)
      val participantProjectSpeciesId3 =
          insertParticipantProjectSpecies(
              projectId = projectId3,
              speciesId = speciesId,
              submissionStatus = SubmissionStatus.Rejected)

      every { user.canReadAllAcceleratorDetails() } returns false

      service.on(
          SpeciesEditedEvent(
              species =
                  ExistingSpeciesModel(
                      id = speciesId,
                      organizationId = inserted.organizationId,
                      scientificName = "Species 1")))

      verify(exactly = 1) {
        participantProjectSpeciesStore.update(participantProjectSpeciesId1, any())
        participantProjectSpeciesStore.update(participantProjectSpeciesId3, any())
      }
    }

    @Test
    fun `does not update the status of the participant project species if the species fields are edited by accelerator-users`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)

      val projectId = insertProject(participantId = participantId)
      val speciesId = insertSpecies()

      insertParticipantProjectSpecies(
          projectId = projectId,
          speciesId = speciesId,
          submissionStatus = SubmissionStatus.Approved)

      every { user.canReadAllAcceleratorDetails() } returns true

      service.on(
          SpeciesEditedEvent(
              species =
                  ExistingSpeciesModel(
                      id = speciesId,
                      organizationId = inserted.organizationId,
                      scientificName = "Species 1")))

      verify(exactly = 0) { participantProjectSpeciesStore.update(any(), any()) }
    }
  }

  @Nested
  inner class SaveSnapshot {
    @Test
    fun `it saves a snapshot of species data if the associated submission is approved`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val moduleId = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleId)
      val deliverableId =
          insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)

      val projectId = insertProject(participantId = participantId)
      val submissionId = insertSubmission(deliverableId = deliverableId, projectId = projectId)

      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      val speciesId3 = insertSpecies(commonName = "Common name 3")

      insertParticipantProjectSpecies(
          projectId = projectId,
          speciesId = speciesId1,
          speciesNativeCategory = SpeciesNativeCategory.NonNative,
          submissionStatus = SubmissionStatus.Approved)
      insertParticipantProjectSpecies(
          projectId = projectId,
          rationale = "It is a great tree",
          speciesId = speciesId2,
          speciesNativeCategory = SpeciesNativeCategory.Native,
          submissionStatus = SubmissionStatus.InReview)
      insertParticipantProjectSpecies(
          feedback = "Need to know native status",
          projectId = projectId,
          speciesId = speciesId3,
          submissionStatus = SubmissionStatus.Rejected)

      service.on(
          DeliverableStatusUpdatedEvent(
              deliverableId = deliverableId,
              projectId = projectId,
              oldStatus = SubmissionStatus.InReview,
              newStatus = SubmissionStatus.Approved,
              submissionId = submissionId))

      val submissionSnapshot = submissionSnapshotsDao.fetchBySubmissionId(submissionId).first()

      assertEquals(submissionId, submissionSnapshot.submissionId, "Submission ID")

      val stream = fileService.readFile(submissionSnapshot.fileId!!)

      val expected =
          "Project ID,Species ID,Status,Rationale,Feedback,Internal Comment,Native / Non-Native,Species Scientific Name,Species Common Name\r\n" +
              "$projectId,$speciesId1,Approved,,,,Non-native,Species 1,\r\n" +
              "$projectId,$speciesId2,In Review,It is a great tree,,,Native,Species 2,\r\n" +
              "$projectId,$speciesId3,Rejected,,Need to know native status,,,Species 3,Common name 3\r\n"

      assertEquals(expected, String(stream.readAllBytes()), "CSV contents")
    }

    @Test
    fun `it does nothing if the submission is not approved`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val moduleId = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleId)
      val deliverableId =
          insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Species)

      val projectId = insertProject(participantId = participantId)
      val submissionId = insertSubmission(deliverableId = deliverableId, projectId = projectId)

      val speciesId = insertSpecies()

      insertParticipantProjectSpecies(
          projectId = projectId,
          speciesId = speciesId,
          speciesNativeCategory = SpeciesNativeCategory.NonNative,
          submissionStatus = SubmissionStatus.Approved)

      service.on(
          DeliverableStatusUpdatedEvent(
              deliverableId = deliverableId,
              projectId = projectId,
              oldStatus = SubmissionStatus.NotSubmitted,
              newStatus = SubmissionStatus.InReview,
              submissionId = submissionId))

      assertEquals(emptyList<SubmissionSnapshotsRow>(), submissionSnapshotsDao.findAll())

      verify(exactly = 0) {
        participantProjectSpeciesStore.fetchSpeciesForParticipantProject(projectId)
      }
    }

    @Test
    fun `it does nothing if the submission is not for a species list deliverable`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val moduleId = insertModule()
      insertCohortModule(cohortId = cohortId, moduleId = moduleId)
      val deliverableId =
          insertDeliverable(moduleId = moduleId, deliverableTypeId = DeliverableType.Document)

      val projectId = insertProject(participantId = participantId)
      val submissionId = insertSubmission(deliverableId = deliverableId, projectId = projectId)

      service.on(
          DeliverableStatusUpdatedEvent(
              deliverableId = deliverableId,
              projectId = projectId,
              oldStatus = SubmissionStatus.InReview,
              newStatus = SubmissionStatus.Approved,
              submissionId = submissionId))

      assertEquals(emptyList<SubmissionSnapshotsRow>(), submissionSnapshotsDao.findAll())

      verify(exactly = 0) {
        participantProjectSpeciesStore.fetchSpeciesForParticipantProject(projectId)
      }
    }
  }
}
