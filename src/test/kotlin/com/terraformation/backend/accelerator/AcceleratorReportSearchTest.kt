package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.AcceleratorPhase
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AcceleratorReportSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables = SearchTables(clock)

  private val prefix: SearchFieldPrefix by lazy {
    SearchFieldPrefix(searchTables.acceleratorReports)
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId
  private lateinit var creatorUserId: UserId
  private lateinit var submitterUserId: UserId

  @BeforeEach
  fun setUp() {
    creatorUserId = inserted.userId
    organizationId = insertOrganization()
    insertOrganizationUser(userId = creatorUserId, role = Role.Admin)
    submitterUserId = insertUser(firstName = "Sam", lastName = "Submitter")
    insertOrganizationUser(userId = submitterUserId, role = Role.Admin)
    projectId = insertProject(name = "Report project", phase = AcceleratorPhase.Phase0DueDiligence)
    insertProjectReportConfig()

    every { user.canReadAllAcceleratorDetails() } returns true
    every { user.organizationRoles } returns mapOf(organizationId to Role.Admin)
  }

  @Test
  fun `returns report details`() {
    val reportId =
        insertReport(
            additionalComments = "Additional comments",
            createdBy = creatorUserId,
            createdTime = Instant.ofEpochSecond(1000),
            endDate = LocalDate.of(2026, 6, 30),
            feedback = "Feedback",
            financialSummaries = "Financial summaries",
            highlights = "Highlights",
            internalComment = "Internal comment",
            modifiedBy = creatorUserId,
            modifiedTime = Instant.ofEpochSecond(2000),
            quarter = ReportQuarter.Q2,
            startDate = LocalDate.of(2026, 4, 1),
            status = ReportStatus.Approved,
            submittedBy = submitterUserId,
            submittedTime = Instant.ofEpochSecond(3000),
        )

    val fields =
        listOf(
                "additionalComments",
                "createdTime",
                "endDate",
                "feedback",
                "financialSummaries",
                "highlights",
                "id",
                "modifiedTime",
                "quarter",
                "startDate",
                "status",
                "submittedTime",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "additionalComments" to "Additional comments",
                    "createdTime" to "1970-01-01T00:16:40Z",
                    "endDate" to "2026-06-30",
                    "feedback" to "Feedback",
                    "financialSummaries" to "Financial summaries",
                    "highlights" to "Highlights",
                    "id" to "$reportId",
                    "modifiedTime" to "1970-01-01T00:33:20Z",
                    "quarter" to "Q2",
                    "startDate" to "2026-04-01",
                    "status" to "Approved",
                    "submittedTime" to "1970-01-01T00:50:00Z",
                )
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
  }

  @Test
  fun `does not expose internal comments`() {
    assertThrows(IllegalArgumentException::class.java) { prefix.resolve("internalComment") }
  }

  @Test
  fun `omits values that are not set on unsubmitted reports`() {
    val reportId = insertReport(quarter = null, status = ReportStatus.NotSubmitted)

    val fields =
        listOf("id", "highlights", "quarter", "submittedTime", "submittedBy_id").map {
          prefix.resolve(it)
        }

    val expected = SearchResults(listOf(mapOf("id" to "$reportId")))

    assertJsonEquals(
        expected,
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
  }

  @Test
  fun `includes project and submitter sublists and audit user IDs`() {
    val reportId =
        insertReport(
            createdBy = creatorUserId,
            modifiedBy = creatorUserId,
            status = ReportStatus.Submitted,
            submittedBy = submitterUserId,
        )

    val fields =
        listOf(
                "id",
                "project.id",
                "project.name",
                "createdBy",
                "modifiedBy",
                "submittedBy.id",
                "submittedBy.lastName",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$reportId",
                    "project" to mapOf("id" to "$projectId", "name" to "Report project"),
                    "createdBy" to "$creatorUserId",
                    "modifiedBy" to "$creatorUserId",
                    "submittedBy" to mapOf("id" to "$submitterUserId", "lastName" to "Submitter"),
                )
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
  }

  @Test
  fun `returns the audit user IDs of reports generated by the system`() {
    val systemUserId = SystemUser(usersDao).userId
    val reportId = insertReport(createdBy = systemUserId, modifiedBy = systemUserId)

    val fields = listOf("id", "createdBy", "modifiedBy").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$reportId",
                    "createdBy" to "$systemUserId",
                    "modifiedBy" to "$systemUserId",
                )
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
  }

  @Test
  fun `can search for reports as sublists with projects as prefix`() {
    val reportId =
        insertReport(startDate = LocalDate.of(2026, 1, 1), endDate = LocalDate.of(2026, 3, 31))
    val laterReportId =
        insertReport(startDate = LocalDate.of(2026, 4, 1), endDate = LocalDate.of(2026, 6, 30))

    val projectsPrefix = SearchFieldPrefix(searchTables.projects)
    val fields = listOf("id", "acceleratorReports.id").map { projectsPrefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$projectId",
                    "acceleratorReports" to
                        listOf(mapOf("id" to "$reportId"), mapOf("id" to "$laterReportId")),
                )
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(projectsPrefix, fields, mapOf(projectsPrefix to NoConditionNode())),
    )
  }

  @Test
  fun `returns reports ordered by start date by default`() {
    val secondQuarterReportId =
        insertReport(
            startDate = LocalDate.of(2026, 4, 1),
            endDate = LocalDate.of(2026, 6, 30),
            quarter = ReportQuarter.Q2,
        )
    val firstQuarterReportId =
        insertReport(
            startDate = LocalDate.of(2026, 1, 1),
            endDate = LocalDate.of(2026, 3, 31),
            quarter = ReportQuarter.Q1,
        )

    val fields = listOf("id").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "$firstQuarterReportId"),
                mapOf("id" to "$secondQuarterReportId"),
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
  }

  @Test
  fun `can filter reports by project`() {
    val reportId = insertReport()

    val otherProjectId = insertProject(phase = AcceleratorPhase.Phase0DueDiligence)
    insertProjectReportConfig()
    insertReport()

    val fields = listOf("id", "project.id").map { prefix.resolve(it) }

    val expected =
        SearchResults(listOf(mapOf("id" to "$reportId", "project" to mapOf("id" to "$projectId"))))

    assertJsonEquals(
        expected,
        searchService.search(
            prefix,
            fields,
            mapOf(prefix to FieldNode(prefix.resolve("project.id"), listOf("$projectId"))),
        ),
        "Reports of project $otherProjectId should be excluded",
    )
  }

  @Test
  fun `returns only reports of organizations the user belongs to for non-internal users`() {
    every { user.canReadAllAcceleratorDetails() } returns false

    val reportId = insertReport()

    insertOrganization()
    insertProject(phase = AcceleratorPhase.Phase0DueDiligence)
    insertProjectReportConfig()
    insertReport()

    val fields = listOf("id").map { prefix.resolve(it) }

    assertJsonEquals(
        SearchResults(listOf(mapOf("id" to "$reportId"))),
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
        "Reports of other organizations should be excluded",
    )

    every { user.canReadAllAcceleratorDetails() } returns true

    assertJsonEquals(
        SearchResults(listOf(mapOf("id" to "$reportId"), mapOf("id" to "${inserted.reportId}"))),
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
        "Internal users should see reports of other organizations",
    )
  }

  @Test
  fun `does not return reports to contributors`() {
    every { user.canReadAllAcceleratorDetails() } returns false
    every { user.organizationRoles } returns mapOf(organizationId to Role.Contributor)

    insertReport()

    val fields = listOf("id").map { prefix.resolve(it) }

    assertJsonEquals(
        SearchResults(emptyList()),
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
        "Contributors may not read reports",
    )

    every { user.organizationRoles } returns emptyMap()

    assertJsonEquals(
        SearchResults(emptyList()),
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
        "Non-members may not read reports",
    )
  }
}
