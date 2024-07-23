package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.accelerator.model.SubmissionDocumentModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.mockUser
import io.mockk.every
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class DeliverableStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val store: DeliverableStore by lazy { DeliverableStore(dslContext) }

  @BeforeEach
  fun setUp() {
    every { user.canReadAllDeliverables() } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadOrganizationDeliverables(any()) } returns true
    every { user.canReadParticipant(any()) } returns true
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
      //   Participant 1
      //     Project 1 (organization 1)
      //     Project 3 (organization 2)
      //   Participant 2
      //     Project 2 (organization 1)
      // Cohort 2
      //   Module 2
      //   Participant 3
      //     Project 4 (organization 2)

      val cohortId1 = insertCohort(id = 1)
      val cohortId2 = insertCohort(id = 2)
      val participantId1 = insertParticipant(id = 1, cohortId = cohortId1)
      val participantId2 = insertParticipant(id = 2, cohortId = cohortId1)
      val participantId3 = insertParticipant(id = 3, cohortId = cohortId2)

      val organizationId1 = insertOrganization(id = 1)
      val organizationId2 = insertOrganization(id = 2)
      val projectId1 =
          insertProject(id = 1, organizationId = organizationId1, participantId = participantId1)
      val projectId2 =
          insertProject(id = 2, organizationId = organizationId1, participantId = participantId2)
      val projectId3 =
          insertProject(id = 3, organizationId = organizationId2, participantId = participantId1)
      val projectId4 =
          insertProject(id = 4, organizationId = organizationId2, participantId = participantId3)

      val moduleId1 = insertModule(id = 1, name = "Name 1")
      val deliverableId1 = insertDeliverable(id = 1, moduleId = 1)
      val deliverableId2 =
          insertDeliverable(
              id = 2, deliverableCategoryId = DeliverableCategory.Compliance, moduleId = 1)
      val moduleId2 = insertModule(id = 2, name = "Name 2")
      val deliverableId3 = insertDeliverable(id = 3, moduleId = 2, descriptionHtml = null)
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
              internalComment = "comment",
              projectId = projectId1,
              submissionStatus = SubmissionStatus.InReview)
      val documentId1 = insertSubmissionDocument()
      val documentId2 = insertSubmissionDocument()

      fun DeliverableSubmissionModel.forProject2() =
          copy(
              participantId = participantId2,
              participantName = "Participant 2",
              projectId = projectId2,
              projectName = "Project 2",
          )

      fun DeliverableSubmissionModel.forProject3() =
          copy(
              organizationId = organizationId2,
              organizationName = "Organization 2",
              participantId = participantId1,
              participantName = "Participant 1",
              projectId = projectId3,
              projectName = "Project 3",
          )

      fun DeliverableSubmissionModel.forProject4() =
          copy(
              dueDate = LocalDate.of(2024, 3, 2),
              moduleTitle = "Module 3",
              organizationId = organizationId2,
              organizationName = "Organization 2",
              participantId = participantId3,
              participantName = "Participant 3",
              projectId = projectId4,
              projectName = "Project 4",
          )

      fun DeliverableSubmissionModel.withoutSubmission() =
          copy(
              documents = emptyList(),
              feedback = null,
              internalComment = null,
              status = SubmissionStatus.NotSubmitted,
              submissionId = null,
          )

      val deliverable1Project1 =
          DeliverableSubmissionModel(
              category = DeliverableCategory.FinancialViability,
              deliverableId = deliverableId1,
              descriptionHtml = "Description 1",
              documents =
                  listOf(
                      SubmissionDocumentModel(
                          Instant.EPOCH,
                          null,
                          DocumentStore.Google,
                          documentId1,
                          "Submission Document 1",
                          "Original Name 1",
                      ),
                      SubmissionDocumentModel(
                          Instant.EPOCH,
                          null,
                          DocumentStore.Google,
                          documentId2,
                          "Submission Document 2",
                          "Original Name 2",
                      ),
                  ),
              dueDate = LocalDate.of(2024, 1, 2),
              feedback = "feedback",
              internalComment = "comment",
              moduleId = moduleId1,
              moduleName = "Name 1",
              moduleTitle = "Module 1",
              name = "Deliverable 1",
              organizationId = organizationId1,
              organizationName = "Organization 1",
              participantId = participantId1,
              participantName = "Participant 1",
              projectId = projectId1,
              projectName = "Project 1",
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
          "All deliverables for organization 1")
      assertEquals(
          listOf(
              deliverable1Project3,
              deliverable2Project3,
              deliverable3Project3,
              deliverable3Project4,
          ),
          store.fetchDeliverableSubmissions(organizationId = organizationId2),
          "All deliverables for organization 2")

      assertEquals(
          listOf(
              deliverable1Project1,
              deliverable1Project3,
              deliverable2Project1,
              deliverable2Project3,
              deliverable3Project1,
              deliverable3Project3,
          ),
          store.fetchDeliverableSubmissions(participantId = participantId1),
          "All deliverables for participant 1 (projects 1 and 3)")
      assertEquals(
          listOf(
              deliverable1Project2,
              deliverable2Project2,
              deliverable3Project2,
          ),
          store.fetchDeliverableSubmissions(participantId = participantId2),
          "All deliverables for participant 2 (project 2)")
      assertEquals(
          listOf(deliverable3Project4),
          store.fetchDeliverableSubmissions(participantId = participantId3),
          "All deliverables for participant 3 (project 4)")

      assertEquals(
          listOf(
              deliverable1Project1,
              deliverable2Project1,
              deliverable3Project1,
          ),
          store.fetchDeliverableSubmissions(projectId = projectId1),
          "All deliverables for project 1")
      assertEquals(
          listOf(
              deliverable1Project2,
              deliverable2Project2,
              deliverable3Project2,
          ),
          store.fetchDeliverableSubmissions(projectId = projectId2),
          "All deliverables for project 2")
      assertEquals(
          listOf(
              deliverable1Project3,
              deliverable2Project3,
              deliverable3Project3,
          ),
          store.fetchDeliverableSubmissions(projectId = projectId3),
          "All deliverables for project 3")
      assertEquals(
          listOf(deliverable3Project4),
          store.fetchDeliverableSubmissions(projectId = projectId4),
          "All deliverables for project 4")

      assertEquals(
          listOf(
              deliverable1Project1,
              deliverable1Project2,
              deliverable1Project3,
          ),
          store.fetchDeliverableSubmissions(deliverableId = deliverableId1),
          "Deliverable 1 for all projects")

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
          "All deliverables")

      assertEquals(
          listOf(deliverable1Project1),
          store.fetchDeliverableSubmissions(projectId = projectId1, deliverableId = deliverableId1),
          "Single deliverable with submitted documents for project 1")
      assertEquals(
          listOf(deliverable1Project3),
          store.fetchDeliverableSubmissions(projectId = projectId3, deliverableId = deliverableId1),
          "Single deliverable with no submissions for project 3")

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
          "All deliverables by module")

      assertEquals(
          listOf(
              deliverable1Project1,
              deliverable1Project2,
              deliverable2Project1,
              deliverable2Project2),
          store.fetchDeliverableSubmissions(organizationId = organizationId1, moduleId = moduleId1),
          "Deliverables by module for organization 1")

      assertEquals(
          listOf(
              deliverable1Project1,
              deliverable1Project3,
              deliverable2Project1,
              deliverable2Project3),
          store.fetchDeliverableSubmissions(participantId = participantId1, moduleId = moduleId1),
          "Deliverables by module for participant 1")

      assertEquals(
          listOf(deliverable1Project1, deliverable2Project1),
          store.fetchDeliverableSubmissions(projectId = projectId1, moduleId = moduleId1),
          "Deliverables by module for project 1")

      assertEquals(
          emptyList<DeliverableSubmissionModel>(),
          store.fetchDeliverableSubmissions(moduleId = moduleId1, deliverableId = deliverableId3),
          "Empty result for single deliverable not in module")
    }

    @Test
    fun `returns due dates according to cohort or project overrides`() {
      val cohortWithDueDate = insertCohort(id = 1)
      val cohortWithoutDueDate = insertCohort(id = 2)

      val participantWithDueDate = insertParticipant(id = 1, cohortId = cohortWithDueDate)
      val participantWithoutDueDate = insertParticipant(id = 3, cohortId = cohortWithoutDueDate)

      val moduleId = insertModule(id = 1)
      val deliverableId = insertDeliverable(id = 1, moduleId = 1)

      insertOrganization()

      val projectWithProjectDueDate = insertProject(id = 1, participantId = participantWithDueDate)
      val projectWithCohortDueDate = insertProject(id = 2, participantId = participantWithDueDate)
      val projectWithDefaultDueDate =
          insertProject(id = 3, participantId = participantWithoutDueDate)

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

      val cohortDueDate = LocalDate.of(2024, 2, 1)
      val projectDueDate = LocalDate.of(2024, 3, 1)

      insertDeliverableCohortDueDate(deliverableId, cohortWithDueDate, cohortDueDate)
      insertDeliverableProjectDueDate(deliverableId, projectWithProjectDueDate, projectDueDate)

      assertEquals(
          projectDueDate,
          store
              .fetchDeliverableSubmissions(
                  projectId = projectWithProjectDueDate, deliverableId = deliverableId)
              .firstOrNull()
              ?.dueDate,
          "Deliverable for project with project due date")

      assertEquals(
          cohortDueDate,
          store
              .fetchDeliverableSubmissions(
                  projectId = projectWithCohortDueDate, deliverableId = deliverableId)
              .firstOrNull()
              ?.dueDate,
          "Deliverable for project with cohort due date and no project due date")

      assertEquals(
          cohortModuleEndDate,
          store
              .fetchDeliverableSubmissions(
                  projectId = projectWithDefaultDueDate, deliverableId = deliverableId)
              .firstOrNull()
              ?.dueDate,
          "Deliverable for project with neither project nor cohort due date")
    }

    @Test
    fun `throws exception if no permission to read entities`() {
      every { user.canReadAllDeliverables() } returns false
      every { user.canReadOrganization(any()) } returns false
      every { user.canReadOrganizationDeliverables(any()) } returns false
      every { user.canReadParticipant(any()) } returns false
      every { user.canReadProject(any()) } returns false
      every { user.canReadProjectDeliverables(any()) } returns false
      every { user.canReadModule(any()) } returns false

      insertOrganization()
      val participantId = insertParticipant()
      val projectId = insertProject()
      val moduleId = insertModule()
      val deliverableId = insertDeliverable()

      assertThrows<OrganizationNotFoundException> {
        store.fetchDeliverableSubmissions(organizationId = organizationId)
      }
      assertThrows<ParticipantNotFoundException> {
        store.fetchDeliverableSubmissions(participantId = participantId)
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
