package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.toGibberish
import com.terraformation.backend.i18n.use
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProjectDeliverableSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables = SearchTables(clock)
  private val moduleStartDate = LocalDate.of(2024, 4, 1)
  private val moduleEndDate = LocalDate.of(2024, 4, 11)

  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(
        userId = inserted.userId,
        organizationId = inserted.organizationId,
        role = Role.Admin,
    )
    insertCohort()
    insertModule()
    insertCohortModule(
        inserted.cohortId,
        inserted.moduleId,
        startDate = moduleStartDate,
        endDate = moduleEndDate,
    )
    projectId = insertProject(cohortId = inserted.cohortId, phase = CohortPhase.Phase0DueDiligence)

    every { user.canReadAllAcceleratorDetails() } returns true
    every { user.organizationRoles } returns mapOf(inserted.organizationId to Role.Admin)
  }

  @Test
  fun `returns deliverable details with project submissions if present`() {
    val deliverableWithSubmission =
        insertDeliverable(
            deliverableCategoryId = DeliverableCategory.Compliance,
            deliverableTypeId = DeliverableType.Document,
            isSensitive = true,
            isRequired = true,
            moduleId = inserted.moduleId,
            name = "Deliverable with submission",
            position = 1,
            descriptionHtml = "Descriptions of deliverable with submission",
        )

    val submissionId =
        insertSubmission(
            deliverableId = deliverableWithSubmission,
            feedback = "Feedback for deliverable with submission",
            projectId = projectId,
            submissionStatus = SubmissionStatus.Approved,
        )

    val deliverableWithoutSubmissions =
        insertDeliverable(
            deliverableCategoryId = DeliverableCategory.CarbonEligibility,
            deliverableTypeId = DeliverableType.Document,
            isSensitive = false,
            isRequired = true,
            moduleId = inserted.moduleId,
            name = "Deliverable without submission",
            position = 2,
            descriptionHtml = "Descriptions of deliverable without submission",
        )

    val prefix = SearchFieldPrefix(searchTables.projectDeliverables)
    val fields =
        listOf(
                "id",
                "category",
                "description",
                "dueDate",
                "feedback",
                "name",
                "position",
                "required",
                "sensitive",
                "status",
                "submissionId",
                "type",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$deliverableWithSubmission",
                    "category" to DeliverableCategory.Compliance.getDisplayName(Locales.GIBBERISH),
                    "description" to "Descriptions of deliverable with submission",
                    "dueDate" to "$moduleEndDate",
                    "feedback" to "Feedback for deliverable with submission",
                    "name" to "Deliverable with submission",
                    "position" to "1",
                    "required" to "true".toGibberish(),
                    "sensitive" to "true".toGibberish(),
                    "status" to SubmissionStatus.Approved.getDisplayName(Locales.GIBBERISH),
                    "submissionId" to "$submissionId",
                    "type" to DeliverableType.Document.getDisplayName(Locales.GIBBERISH),
                ),
                mapOf(
                    "id" to "$deliverableWithoutSubmissions",
                    "category" to
                        DeliverableCategory.CarbonEligibility.getDisplayName(Locales.GIBBERISH),
                    "description" to "Descriptions of deliverable without submission",
                    "dueDate" to "$moduleEndDate",
                    "name" to "Deliverable without submission",
                    "position" to "2",
                    "required" to "true".toGibberish(),
                    "sensitive" to "false".toGibberish(),
                    "type" to DeliverableType.Document.getDisplayName(Locales.GIBBERISH),
                ),
            ),
            null,
        )

    val actual =
        Locales.GIBBERISH.use {
          searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))
        }

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `includes project and module as sublists`() {
    val deliverableId = insertDeliverable(moduleId = inserted.moduleId)
    insertSubmission(deliverableId = deliverableId, projectId = projectId)

    val prefix = SearchFieldPrefix(searchTables.projectDeliverables)
    val fields = listOf("id", "project_id", "module_id").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$deliverableId",
                    "project_id" to "$projectId",
                    "module_id" to "${inserted.moduleId}",
                )
            )
        )

    val actual = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `can filter by project and only includes deliverables that are part of project cohort`() {
    val moduleId = inserted.moduleId

    val deliverableWithSubmission = insertDeliverable(moduleId = moduleId)
    val submissionId =
        insertSubmission(
            deliverableId = deliverableWithSubmission,
            projectId = projectId,
            submissionStatus = SubmissionStatus.Approved,
        )

    val hiddenProject =
        insertProject(cohortId = inserted.cohortId, phase = CohortPhase.Phase0DueDiligence)
    insertSubmission(
        deliverableId = deliverableWithSubmission,
        projectId = hiddenProject,
        submissionStatus = SubmissionStatus.Rejected,
    )

    val deliverableWithoutSubmissions = insertDeliverable(moduleId = moduleId)

    val hiddenModule = insertModule(name = "Hidden module")
    insertDeliverable(moduleId = hiddenModule)

    val prefix = SearchFieldPrefix(searchTables.projectDeliverables)
    val fields = listOf("id", "submissionId").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$deliverableWithSubmission",
                    "submissionId" to "$submissionId",
                ),
                mapOf(
                    "id" to "$deliverableWithoutSubmissions",
                ),
            ),
            null,
        )

    val actual =
        searchService.search(
            prefix,
            fields,
            mapOf(prefix to FieldNode(prefix.resolve("project.id"), listOf("$projectId"))),
        )

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `can search for project deliverable as sublists with projects as prefix`() {
    val deliverableWithSubmission = insertDeliverable(moduleId = inserted.moduleId)
    insertSubmission(deliverableId = deliverableWithSubmission, projectId = projectId)

    val deliverableWithoutSubmissions = insertDeliverable(moduleId = inserted.moduleId)

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields = listOf("id", "projectDeliverables.id").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$projectId",
                    "projectDeliverables" to
                        listOf(
                            mapOf("id" to "$deliverableWithSubmission"),
                            mapOf("id" to "$deliverableWithoutSubmissions"),
                        ),
                )
            )
        )

    val actual = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `returns deliverable due date according to project overrides`() {
    val deliverableId = insertDeliverable(moduleId = inserted.moduleId)

    val prefix = SearchFieldPrefix(searchTables.projectDeliverables)
    val fields = listOf("id", "dueDate").map { prefix.resolve(it) }

    assertJsonEquals(
        SearchResults(
            listOf(
                mapOf("id" to "$deliverableId", "dueDate" to "$moduleEndDate"),
            )
        ),
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
        "Search project deliverables with module end date",
    )

    val projectDueDate = moduleEndDate.plusDays(5)
    insertDeliverableProjectDueDate(deliverableId, projectId, projectDueDate)
    assertJsonEquals(
        SearchResults(
            listOf(
                mapOf("id" to "$deliverableId", "dueDate" to "$projectDueDate"),
            )
        ),
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
        "Search project deliverables with project due date override",
    )
  }

  @Test
  fun `returns only deliverables of projects visible to non-accelerator admin users`() {
    every { user.canReadAllAcceleratorDetails() } returns false

    val moduleId = inserted.moduleId

    val deliverableWithSubmission = insertDeliverable(moduleId = moduleId)
    val submissionId =
        insertSubmission(
            deliverableId = deliverableWithSubmission,
            projectId = projectId,
            submissionStatus = SubmissionStatus.Approved,
        )
    val deliverableWithoutSubmissions = insertDeliverable(moduleId = moduleId)

    val otherCohort = insertCohort()
    insertCohortModule(cohortId = otherCohort, moduleId = inserted.moduleId)

    val otherOrganization = insertOrganization()
    val otherProject =
        insertProject(
            cohortId = otherCohort,
            organizationId = otherOrganization,
            phase = CohortPhase.Phase0DueDiligence,
        )
    insertSubmission(
        deliverableId = deliverableWithSubmission,
        projectId = otherProject,
        submissionStatus = SubmissionStatus.Rejected,
    )

    val hiddenModule = insertModule(name = "Hidden module")
    insertDeliverable(moduleId = hiddenModule)

    val prefix = SearchFieldPrefix(searchTables.projectDeliverables)
    val fields = listOf("id", "submissionId", "project_id").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$deliverableWithSubmission",
                    "project_id" to "$projectId",
                    "submissionId" to "$submissionId",
                ),
                mapOf(
                    "id" to "$deliverableWithoutSubmissions",
                    "project_id" to "$projectId",
                ),
            ),
            null,
        )

    val actual = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))

    assertJsonEquals(expected, actual)
  }
}
