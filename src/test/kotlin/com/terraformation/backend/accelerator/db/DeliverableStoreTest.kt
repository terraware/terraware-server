package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.accelerator.model.SubmissionDocumentModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.mockUser
import io.mockk.every
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class DeliverableStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  private val clock = TestClock()
  private val store: DeliverableStore by lazy { DeliverableStore(dslContext) }

  @BeforeEach
  fun setUp() {
    every { user.canReadAllDeliverables() } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadOrganizationDeliverables(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadProjectDeliverables(any()) } returns true
    every { user.canReadModule(any()) } returns true
  }

  @Nested
  inner class FetchDeliverableSubmissions {
    @Test
    fun `can filter by IDs`() {
      // Organization 1
      //   Project 1
      //   Project 2
      // Organization 2
      //   Project 3
      //   Project 4
      //
      // Module 1
      //   Deliverable 1
      //     Submission 1 (project 1)
      //       Document 1
      //       Document 2
      //   Deliverable 2
      // Module 2
      //   Deliverable 3
      //
      // Cohort 1
      //   Module 1
      //   Module 2
      //   Project 1 (organization 1)
      //   Project 2 (organization 1)
      //   Project 3 (organization 2)
      // Cohort 2
      //   Module 2
      //   Participant 3
      //     Project 4 (organization 2)

      val now = Instant.ofEpochSecond(30)
      clock.instant = now

      val cohortId1 = insertCohort(name = "Cohort 1")
      val cohortId2 = insertCohort(name = "Cohort 2")

      val organizationId1 = insertOrganization()
      val organizationId2 = insertOrganization()
      val projectId1 = insertProject(organizationId = organizationId1, cohortId = cohortId1)
      val projectId2 = insertProject(organizationId = organizationId1, cohortId = cohortId1)
      val projectId3 = insertProject(organizationId = organizationId2, cohortId = cohortId1)
      val projectId4 = insertProject(organizationId = organizationId2, cohortId = cohortId2)

      val moduleId1 = insertModule(name = "Name 1")
      val deliverableId1 = insertDeliverable()
      val deliverableId2 = insertDeliverable(deliverableCategoryId = DeliverableCategory.Compliance)
      val moduleId2 = insertModule(name = "Name 2")
      val deliverableId3 = insertDeliverable(descriptionHtml = null)
      insertDeliverableDocument(templateUrl = "https://example.com/")

      insertCohortModule(
          cohortId1,
          moduleId1,
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 1, 2),
          title = "Module 1",
      )
      insertCohortModule(
          cohortId1,
          moduleId2,
          startDate = LocalDate.of(2024, 2, 1),
          endDate = LocalDate.of(2024, 2, 2),
          title = "Module 2",
      )
      insertCohortModule(
          cohortId2,
          moduleId2,
          startDate = LocalDate.of(2024, 3, 1),
          endDate = LocalDate.of(2024, 3, 2),
          title = "Module 3",
      )

      val submissionId1 =
          insertSubmission(
              deliverableId = deliverableId1,
              feedback = "feedback",
              modifiedTime = now,
              internalComment = "comment",
              projectId = projectId1,
              submissionStatus = SubmissionStatus.InReview,
          )
      val documentId1 = insertSubmissionDocument()
      val documentId2 = insertSubmissionDocument()

      fun DeliverableSubmissionModel.forProject2() =
          copy(
              projectId = projectId2,
              projectName = "Project 2",
          )

      fun DeliverableSubmissionModel.forProject3() =
          copy(
              organizationId = organizationId2,
              organizationName = "Organization 2",
              projectId = projectId3,
              projectName = "Project 3",
          )

      fun DeliverableSubmissionModel.forProject4() =
          copy(
              cohortId = cohortId2,
              cohortName = "Cohort 2",
              dueDate = LocalDate.of(2024, 3, 2),
              moduleTitle = "Module 3",
              organizationId = organizationId2,
              organizationName = "Organization 2",
              projectId = projectId4,
              projectName = "Project 4",
          )

      fun DeliverableSubmissionModel.withoutSubmission() =
          copy(
              documents = emptyList(),
              feedback = null,
              modifiedTime = null,
              internalComment = null,
              status = SubmissionStatus.NotSubmitted,
              submissionId = null,
          )

      val deliverable1Project1 =
          DeliverableSubmissionModel(
              category = DeliverableCategory.FinancialViability,
              cohortId = cohortId1,
              cohortName = "Cohort 1",
              deliverableId = deliverableId1,
              descriptionHtml = "Description 1",
              documents =
                  listOf(
                      SubmissionDocumentModel(
                          createdTime = Instant.EPOCH,
                          description = null,
                          documentStore = DocumentStore.Google,
                          id = documentId1,
                          location = "Location 1",
                          name = "Submission Document 1",
                          originalName = "Original Name 1",
                      ),
                      SubmissionDocumentModel(
                          createdTime = Instant.EPOCH,
                          description = null,
                          documentStore = DocumentStore.Google,
                          id = documentId2,
                          location = "Location 2",
                          name = "Submission Document 2",
                          originalName = "Original Name 2",
                      ),
                  ),
              dueDate = LocalDate.of(2024, 1, 2),
              feedback = "feedback",
              internalComment = "comment",
              modifiedTime = now,
              moduleId = moduleId1,
              moduleName = "Name 1",
              moduleTitle = "Module 1",
              name = "Deliverable 1",
              organizationId = organizationId1,
              organizationName = "Organization 1",
              position = 1,
              projectDealName = null,
              projectId = projectId1,
              projectName = "Project 1",
              required = false,
              sensitive = false,
              status = SubmissionStatus.InReview,
              submissionId = submissionId1,
              templateUrl = null,
              type = DeliverableType.Document,
          )
      val deliverable2Project1 =
          deliverable1Project1
              .copy(
                  category = DeliverableCategory.Compliance,
                  deliverableId = deliverableId2,
                  descriptionHtml = "Description 2",
                  name = "Deliverable 2",
                  position = 2,
              )
              .withoutSubmission()
      val deliverable3Project1 =
          deliverable2Project1.copy(
              category = DeliverableCategory.FinancialViability,
              deliverableId = deliverableId3,
              descriptionHtml = null,
              dueDate = LocalDate.of(2024, 2, 2),
              moduleId = moduleId2,
              moduleName = "Name 2",
              moduleTitle = "Module 2",
              name = "Deliverable 3",
              position = 3,
              templateUrl = URI("https://example.com/"),
          )

      val deliverable1Project2 = deliverable1Project1.withoutSubmission().forProject2()
      val deliverable2Project2 = deliverable2Project1.forProject2()
      val deliverable3Project2 = deliverable3Project1.forProject2()

      val deliverable1Project3 = deliverable1Project2.forProject3()
      val deliverable2Project3 = deliverable2Project2.forProject3()
      val deliverable3Project3 = deliverable3Project2.forProject3()

      val deliverable3Project4 = deliverable3Project1.forProject4()

      assertEquals(
          listOf(
              deliverable1Project1,
              deliverable1Project2,
              deliverable2Project1,
              deliverable2Project2,
              deliverable3Project1,
              deliverable3Project2,
          ),
          store.fetchDeliverableSubmissions(organizationId = organizationId1),
          "All deliverables for organization 1",
      )
      assertEquals(
          listOf(
              deliverable1Project3,
              deliverable2Project3,
              deliverable3Project3,
              deliverable3Project4,
          ),
          store.fetchDeliverableSubmissions(organizationId = organizationId2),
          "All deliverables for organization 2",
      )

      assertEquals(
          listOf(
              deliverable1Project1,
              deliverable2Project1,
              deliverable3Project1,
          ),
          store.fetchDeliverableSubmissions(projectId = projectId1),
          "All deliverables for project 1",
      )
      assertEquals(
          listOf(
              deliverable1Project2,
              deliverable2Project2,
              deliverable3Project2,
          ),
          store.fetchDeliverableSubmissions(projectId = projectId2),
          "All deliverables for project 2",
      )
      assertEquals(
          listOf(
              deliverable1Project3,
              deliverable2Project3,
              deliverable3Project3,
          ),
          store.fetchDeliverableSubmissions(projectId = projectId3),
          "All deliverables for project 3",
      )
      assertEquals(
          listOf(deliverable3Project4),
          store.fetchDeliverableSubmissions(projectId = projectId4),
          "All deliverables for project 4",
      )

      assertEquals(
          listOf(
              deliverable1Project1,
              deliverable1Project2,
              deliverable1Project3,
          ),
          store.fetchDeliverableSubmissions(deliverableId = deliverableId1),
          "Deliverable 1 for all projects",
      )

      assertEquals(
          listOf(
              deliverable1Project1,
              deliverable1Project2,
              deliverable1Project3,
              deliverable2Project1,
              deliverable2Project2,
              deliverable2Project3,
              deliverable3Project1,
              deliverable3Project2,
              deliverable3Project3,
              deliverable3Project4,
          ),
          store.fetchDeliverableSubmissions(),
          "All deliverables",
      )

      assertEquals(
          listOf(deliverable1Project1),
          store.fetchDeliverableSubmissions(projectId = projectId1, deliverableId = deliverableId1),
          "Single deliverable with submitted documents for project 1",
      )
      assertEquals(
          listOf(deliverable1Project3),
          store.fetchDeliverableSubmissions(projectId = projectId3, deliverableId = deliverableId1),
          "Single deliverable with no submissions for project 3",
      )

      assertEquals(
          listOf(
              deliverable1Project1,
              deliverable1Project2,
              deliverable1Project3,
              deliverable2Project1,
              deliverable2Project2,
              deliverable2Project3,
          ),
          store.fetchDeliverableSubmissions(moduleId = moduleId1),
          "All deliverables by module",
      )

      assertEquals(
          listOf(
              deliverable1Project1,
              deliverable1Project2,
              deliverable2Project1,
              deliverable2Project2,
          ),
          store.fetchDeliverableSubmissions(organizationId = organizationId1, moduleId = moduleId1),
          "Deliverables by module for organization 1",
      )

      assertEquals(
          listOf(deliverable1Project1, deliverable2Project1),
          store.fetchDeliverableSubmissions(projectId = projectId1, moduleId = moduleId1),
          "Deliverables by module for project 1",
      )

      assertEquals(
          emptyList<DeliverableSubmissionModel>(),
          store.fetchDeliverableSubmissions(moduleId = moduleId1, deliverableId = deliverableId3),
          "Empty result for single deliverable not in module",
      )
    }

    @Test
    fun `returns non-application submission information for projects not in participants`() {
      val organizationId = insertOrganization()
      val projectId = insertProject()
      val moduleId = insertModule()
      val deliverableId = insertDeliverable()
      val submissionId = insertSubmission(submissionStatus = SubmissionStatus.Approved)
      val documentId = insertSubmissionDocument()

      // Pre-screen and application submissions should not be included
      insertModule(phase = CohortPhase.PreScreen)
      insertDeliverable()
      insertSubmission()
      insertModule(phase = CohortPhase.Application)
      insertDeliverable()
      insertSubmission()

      assertEquals(
          listOf(
              DeliverableSubmissionModel(
                  category = DeliverableCategory.FinancialViability,
                  cohortId = null,
                  cohortName = null,
                  deliverableId = deliverableId,
                  descriptionHtml = "Description 1",
                  documents =
                      listOf(
                          SubmissionDocumentModel(
                              createdTime = Instant.EPOCH,
                              description = null,
                              documentStore = DocumentStore.Google,
                              id = documentId,
                              location = "Location 1",
                              name = "Submission Document 1",
                              originalName = "Original Name 1",
                          ),
                      ),
                  dueDate = null,
                  feedback = null,
                  internalComment = null,
                  modifiedTime = Instant.EPOCH,
                  moduleId = moduleId,
                  moduleName = "Module 1",
                  moduleTitle = null,
                  name = "Deliverable 1",
                  organizationId = organizationId,
                  organizationName = "Organization 1",
                  position = 1,
                  projectDealName = null,
                  projectId = projectId,
                  projectName = "Project 1",
                  required = false,
                  sensitive = false,
                  status = SubmissionStatus.Approved,
                  submissionId = submissionId,
                  templateUrl = null,
                  type = DeliverableType.Document,
              )
          ),
          store.fetchDeliverableSubmissions(),
      )
    }

    @Test
    fun `returns application submissions for non-participant projects if deliverable ID is specified`() {
      val organizationId = insertOrganization()
      val projectId = insertProject()
      val moduleId = insertModule(phase = CohortPhase.PreScreen)
      val deliverableId = insertDeliverable()
      val submissionId = insertSubmission(submissionStatus = SubmissionStatus.Approved)
      val documentId = insertSubmissionDocument()

      assertEquals(
          listOf(
              DeliverableSubmissionModel(
                  category = DeliverableCategory.FinancialViability,
                  cohortId = null,
                  cohortName = null,
                  deliverableId = deliverableId,
                  descriptionHtml = "Description 1",
                  documents =
                      listOf(
                          SubmissionDocumentModel(
                              createdTime = Instant.EPOCH,
                              description = null,
                              documentStore = DocumentStore.Google,
                              id = documentId,
                              location = "Location 1",
                              name = "Submission Document 1",
                              originalName = "Original Name 1",
                          ),
                      ),
                  dueDate = null,
                  feedback = null,
                  internalComment = null,
                  modifiedTime = Instant.EPOCH,
                  moduleId = moduleId,
                  moduleName = "Module 1",
                  moduleTitle = null,
                  name = "Deliverable 1",
                  organizationId = organizationId,
                  organizationName = "Organization 1",
                  position = 1,
                  projectDealName = null,
                  projectId = projectId,
                  projectName = "Project 1",
                  required = false,
                  sensitive = false,
                  status = SubmissionStatus.Approved,
                  submissionId = submissionId,
                  templateUrl = null,
                  type = DeliverableType.Document,
              )
          ),
          store.fetchDeliverableSubmissions(deliverableId = deliverableId),
      )
    }

    @Test
    fun `returns application submissions for participant projects if deliverable ID is specified`() {
      val organizationId = insertOrganization()
      val cohortId = insertCohort(name = "Cohort Name")
      val projectId = insertProject(cohortId = cohortId)
      val moduleId = insertModule(phase = CohortPhase.PreScreen)
      val deliverableId = insertDeliverable()
      val submissionId = insertSubmission(submissionStatus = SubmissionStatus.Approved)
      insertModule(phase = CohortPhase.Phase0DueDiligence)
      insertCohortModule()

      assertEquals(
          listOf(
              DeliverableSubmissionModel(
                  category = DeliverableCategory.FinancialViability,
                  cohortId = cohortId,
                  cohortName = "Cohort Name",
                  deliverableId = deliverableId,
                  descriptionHtml = "Description 1",
                  documents = emptyList(),
                  dueDate = null,
                  feedback = null,
                  internalComment = null,
                  modifiedTime = Instant.EPOCH,
                  moduleId = moduleId,
                  moduleName = "Module 1",
                  moduleTitle = null,
                  name = "Deliverable 1",
                  organizationId = organizationId,
                  organizationName = "Organization 1",
                  position = 1,
                  projectDealName = null,
                  projectId = projectId,
                  projectName = "Project 1",
                  required = false,
                  sensitive = false,
                  status = SubmissionStatus.Approved,
                  submissionId = submissionId,
                  templateUrl = null,
                  type = DeliverableType.Document,
              )
          ),
          store.fetchDeliverableSubmissions(deliverableId = deliverableId),
      )
    }

    @Test
    fun `returns default submission for non-participant project if deliverable and project IDs are specified`() {
      val organizationId = insertOrganization()
      val projectId = insertProject()
      val moduleId = insertModule(phase = CohortPhase.PreScreen)
      val deliverableId = insertDeliverable()

      assertEquals(
          listOf(
              DeliverableSubmissionModel(
                  category = DeliverableCategory.FinancialViability,
                  cohortId = null,
                  cohortName = null,
                  deliverableId = deliverableId,
                  descriptionHtml = "Description 1",
                  documents = emptyList(),
                  dueDate = null,
                  feedback = null,
                  internalComment = null,
                  modifiedTime = null,
                  moduleId = moduleId,
                  moduleName = "Module 1",
                  moduleTitle = null,
                  name = "Deliverable 1",
                  organizationId = organizationId,
                  organizationName = "Organization 1",
                  position = 1,
                  projectDealName = null,
                  projectId = projectId,
                  projectName = "Project 1",
                  required = false,
                  sensitive = false,
                  status = SubmissionStatus.NotSubmitted,
                  submissionId = null,
                  templateUrl = null,
                  type = DeliverableType.Document,
              )
          ),
          store.fetchDeliverableSubmissions(deliverableId = deliverableId, projectId = projectId),
      )
    }

    @Test
    fun `returns due dates according to cohort or project overrides`() {
      val cohortWithDueDate = insertCohort()
      val cohortWithoutDueDate = insertCohort()

      val moduleId = insertModule()
      val deliverableId = insertDeliverable()

      insertOrganization()

      val projectWithProjectDueDate = insertProject(cohortId = cohortWithDueDate)
      val projectWithDefaultDueDate = insertProject(cohortId = cohortWithoutDueDate)

      val cohortModuleEndDate = LocalDate.of(2024, 1, 2)

      insertCohortModule(
          cohortWithDueDate,
          moduleId,
          startDate = cohortModuleEndDate.minusDays(1),
          endDate = cohortModuleEndDate,
      )

      insertCohortModule(
          cohortWithoutDueDate,
          moduleId,
          startDate = cohortModuleEndDate.minusDays(1),
          endDate = cohortModuleEndDate,
      )

      val projectDueDate = LocalDate.of(2024, 3, 1)

      insertDeliverableProjectDueDate(deliverableId, projectWithProjectDueDate, projectDueDate)

      assertEquals(
          projectDueDate,
          store
              .fetchDeliverableSubmissions(
                  projectId = projectWithProjectDueDate,
                  deliverableId = deliverableId,
              )
              .firstOrNull()
              ?.dueDate,
          "Deliverable for project with project due date",
      )

      assertEquals(
          cohortModuleEndDate,
          store
              .fetchDeliverableSubmissions(
                  projectId = projectWithDefaultDueDate,
                  deliverableId = deliverableId,
              )
              .firstOrNull()
              ?.dueDate,
          "Deliverable for project with neither project nor cohort due date",
      )
    }

    @Test
    fun `throws exception if no permission to read entities`() {
      every { user.canReadAllDeliverables() } returns false
      every { user.canReadOrganization(any()) } returns false
      every { user.canReadOrganizationDeliverables(any()) } returns false
      every { user.canReadProject(any()) } returns false
      every { user.canReadProjectDeliverables(any()) } returns false
      every { user.canReadModule(any()) } returns false

      val organizationId = insertOrganization()
      val projectId = insertProject()
      val moduleId = insertModule()
      val deliverableId = insertDeliverable()

      assertThrows<OrganizationNotFoundException> {
        store.fetchDeliverableSubmissions(organizationId = organizationId)
      }
      assertThrows<ProjectNotFoundException> {
        store.fetchDeliverableSubmissions(projectId = projectId)
      }
      assertThrows<ModuleNotFoundException> {
        store.fetchDeliverableSubmissions(moduleId = moduleId)
      }
      assertThrows<AccessDeniedException> {
        store.fetchDeliverableSubmissions(deliverableId = deliverableId)
      }
    }
  }
}
