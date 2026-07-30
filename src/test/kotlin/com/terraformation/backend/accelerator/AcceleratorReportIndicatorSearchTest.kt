package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.AcceleratorPhase
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportIndicatorStatus
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
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

class AcceleratorReportIndicatorSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables = SearchTables(clock)

  private val commonPrefix: SearchFieldPrefix by lazy {
    SearchFieldPrefix(searchTables.acceleratorReportCommonIndicators)
  }
  private val projectPrefix: SearchFieldPrefix by lazy {
    SearchFieldPrefix(searchTables.acceleratorReportProjectIndicators)
  }
  private val autoCalculatedPrefix: SearchFieldPrefix by lazy {
    SearchFieldPrefix(searchTables.acceleratorReportAutoCalculatedIndicators)
  }
  private val reportsPrefix: SearchFieldPrefix by lazy {
    SearchFieldPrefix(searchTables.acceleratorReports)
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId
  private lateinit var reportId: ReportId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertOrganizationUser(userId = inserted.userId, role = Role.Admin)
    projectId = insertProject(phase = AcceleratorPhase.Phase0DueDiligence)
    insertProjectReportConfig()
    reportId =
        insertReport(startDate = LocalDate.of(2026, 1, 1), endDate = LocalDate.of(2026, 3, 31))

    every { user.canReadAllAcceleratorDetails() } returns true
    every { user.organizationRoles } returns mapOf(organizationId to Role.Admin)
  }

  @Test
  fun `returns common indicator details`() {
    val indicatorId = insertCommonIndicator()
    insertReportCommonIndicator(
        indicatorId = indicatorId,
        modifiedTime = Instant.ofEpochSecond(1000),
        progressNotes = "Progress notes",
        projectsComments = "Project comments",
        status = ReportIndicatorStatus.OnTrack,
        value = 25,
    )

    val fields =
        listOf("id", "modifiedTime", "projectsComments", "status", "value").map {
          commonPrefix.resolve(it)
        }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$indicatorId",
                    "modifiedTime" to "1970-01-01T00:16:40Z",
                    "projectsComments" to "Project comments",
                    "status" to "On-Track",
                    "value" to "25",
                )
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(commonPrefix, fields, mapOf(commonPrefix to NoConditionNode())),
    )
  }

  @Test
  fun `returns project indicator details`() {
    val indicatorId = insertProjectIndicator(name = "Community members trained", refId = "2.1")
    insertReportProjectIndicator(
        indicatorId = indicatorId,
        status = ReportIndicatorStatus.Achieved,
        value = 40,
    )

    val fields = listOf("id", "status", "value").map { projectPrefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(mapOf("id" to "$indicatorId", "status" to "Achieved", "value" to "40"))
        )

    assertJsonEquals(
        expected,
        searchService.search(projectPrefix, fields, mapOf(projectPrefix to NoConditionNode())),
    )
  }

  @Test
  fun `returns auto-calculated indicator details`() {
    insertReportAutoCalculatedIndicator(
        indicator = AutoCalculatedIndicator.TreesPlanted,
        overrideValue = 900,
        status = ReportIndicatorStatus.Unlikely,
        systemTime = Instant.ofEpochSecond(2000),
        systemValue = 1000,
    )

    val fields =
        listOf("indicator", "status", "systemTime", "systemValue", "overrideValue").map {
          autoCalculatedPrefix.resolve(it)
        }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "indicator" to "Trees Planted",
                    "status" to "Unlikely",
                    "systemTime" to "1970-01-01T00:33:20Z",
                    "systemValue" to "1,000",
                    "overrideValue" to "900",
                )
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(
            autoCalculatedPrefix,
            fields,
            mapOf(autoCalculatedPrefix to NoConditionNode()),
        ),
    )
  }

  @Test
  fun `does not expose progress notes`() {
    listOf(commonPrefix, projectPrefix, autoCalculatedPrefix).forEach { prefix ->
      assertThrows(IllegalArgumentException::class.java) { prefix.resolve("progressNotes") }
    }
  }

  @Test
  fun `returns all three kinds of indicators as sublists of reports`() {
    val commonIndicatorId = insertCommonIndicator(refId = "1.1")
    insertReportCommonIndicator(indicatorId = commonIndicatorId, value = 1)
    val projectIndicatorId = insertProjectIndicator(refId = "2.1")
    insertReportProjectIndicator(indicatorId = projectIndicatorId, value = 2)
    insertReportAutoCalculatedIndicator(
        indicator = AutoCalculatedIndicator.SeedsCollected,
        systemValue = 3,
    )

    val fields =
        listOf(
                "id",
                "commonIndicators.id",
                "commonIndicators.value",
                "projectIndicators.id",
                "projectIndicators.value",
                "autoCalculatedIndicators.indicator",
                "autoCalculatedIndicators.systemValue",
            )
            .map { reportsPrefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$reportId",
                    "commonIndicators" to
                        listOf(mapOf("id" to "$commonIndicatorId", "value" to "1")),
                    "projectIndicators" to
                        listOf(mapOf("id" to "$projectIndicatorId", "value" to "2")),
                    "autoCalculatedIndicators" to
                        listOf(mapOf("indicator" to "Seeds Collected", "systemValue" to "3")),
                )
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(reportsPrefix, fields, mapOf(reportsPrefix to NoConditionNode())),
    )
  }

  @Test
  fun `returns indicators ordered by reference ID by default`() {
    val secondIndicatorId = insertCommonIndicator(refId = "1.2")
    insertReportCommonIndicator(indicatorId = secondIndicatorId)
    val firstIndicatorId = insertCommonIndicator(refId = "1.1")
    insertReportCommonIndicator(indicatorId = firstIndicatorId)

    val fields = listOf("id").map { commonPrefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(mapOf("id" to "$firstIndicatorId"), mapOf("id" to "$secondIndicatorId"))
        )

    assertJsonEquals(
        expected,
        searchService.search(commonPrefix, fields, mapOf(commonPrefix to NoConditionNode())),
    )
  }

  @Test
  fun `orders the same indicator in different reports by report`() {
    val laterReportId =
        insertReport(startDate = LocalDate.of(2026, 4, 1), endDate = LocalDate.of(2026, 6, 30))
    val indicatorId = insertCommonIndicator()
    insertReportCommonIndicator(reportId = laterReportId, indicatorId = indicatorId, value = 2)
    insertReportCommonIndicator(reportId = reportId, indicatorId = indicatorId, value = 1)

    val fields = listOf("value", "report.id").map { commonPrefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf("value" to "1", "report" to mapOf("id" to "$reportId")),
                mapOf("value" to "2", "report" to mapOf("id" to "$laterReportId")),
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(commonPrefix, fields, mapOf(commonPrefix to NoConditionNode())),
        "Rows for one indicator across reports are ordered by report",
    )
  }

  @Test
  fun `can filter indicators by report`() {
    val indicatorId = insertCommonIndicator()
    insertReportCommonIndicator(indicatorId = indicatorId, value = 1)

    val otherReportId =
        insertReport(startDate = LocalDate.of(2026, 4, 1), endDate = LocalDate.of(2026, 6, 30))
    insertReportCommonIndicator(reportId = otherReportId, indicatorId = indicatorId, value = 2)

    val fields = listOf("value", "report.id").map { commonPrefix.resolve(it) }

    val expected =
        SearchResults(listOf(mapOf("value" to "1", "report" to mapOf("id" to "$reportId"))))

    assertJsonEquals(
        expected,
        searchService.search(
            commonPrefix,
            fields,
            mapOf(
                commonPrefix to FieldNode(commonPrefix.resolve("report.id"), listOf("$reportId"))
            ),
        ),
    )
  }

  @Test
  fun `can filter by indicator fields without returning other indicators of the same report`() {
    val offTrackIndicatorId = insertCommonIndicator(refId = "1.1")
    insertReportCommonIndicator(
        indicatorId = offTrackIndicatorId,
        status = ReportIndicatorStatus.OffTrack,
    )
    val achievedIndicatorId = insertCommonIndicator(refId = "1.2")
    insertReportCommonIndicator(
        indicatorId = achievedIndicatorId,
        status = ReportIndicatorStatus.Achieved,
    )

    val fields = listOf("id", "status").map { commonPrefix.resolve(it) }

    val expected =
        SearchResults(listOf(mapOf("id" to "$offTrackIndicatorId", "status" to "Off-Track")))

    assertJsonEquals(
        expected,
        searchService.search(
            commonPrefix,
            fields,
            mapOf(commonPrefix to FieldNode(commonPrefix.resolve("status"), listOf("Off-Track"))),
        ),
    )
  }

  @Test
  fun `returns only indicators of reports the user can read`() {
    every { user.canReadAllAcceleratorDetails() } returns false

    val commonIndicatorId = insertCommonIndicator()
    insertReportCommonIndicator(indicatorId = commonIndicatorId, value = 1)
    val projectIndicatorId = insertProjectIndicator()
    insertReportProjectIndicator(indicatorId = projectIndicatorId, value = 1)
    insertReportAutoCalculatedIndicator(systemValue = 1)

    insertOrganization()
    insertProject(phase = AcceleratorPhase.Phase0DueDiligence)
    insertProjectReportConfig()
    val otherReportId =
        insertReport(startDate = LocalDate.of(2026, 1, 1), endDate = LocalDate.of(2026, 3, 31))
    insertReportCommonIndicator(
        reportId = otherReportId,
        indicatorId = commonIndicatorId,
        value = 2,
    )
    val otherProjectIndicatorId = insertProjectIndicator()
    insertReportProjectIndicator(
        reportId = otherReportId,
        indicatorId = otherProjectIndicatorId,
        value = 2,
    )
    insertReportAutoCalculatedIndicator(reportId = otherReportId, systemValue = 2)

    assertIndicatorValues(listOf("1"), "Indicators of other organizations should be excluded")

    every { user.organizationRoles } returns mapOf(organizationId to Role.Contributor)

    assertIndicatorValues(emptyList(), "Contributors may not read indicators")

    every { user.canReadAllAcceleratorDetails() } returns true

    assertIndicatorValues(
        listOf("1", "2"),
        "Internal users should see indicators of other organizations",
    )
  }

  private fun assertIndicatorValues(expectedValues: List<String>, message: String) {
    mapOf(
            commonPrefix to "value",
            projectPrefix to "value",
            autoCalculatedPrefix to "systemValue",
        )
        .forEach { (prefix, valueField) ->
          assertJsonEquals(
              SearchResults(expectedValues.map { mapOf(valueField to it) }),
              searchService.search(
                  prefix,
                  listOf(prefix.resolve(valueField)),
                  mapOf(prefix to NoConditionNode()),
              ),
              "$message (${prefix.root.name})",
          )
        }
  }

  @Test
  fun `omits values that have not been entered`() {
    val indicatorId = insertCommonIndicator()
    insertReportCommonIndicator(indicatorId = indicatorId)

    val fields =
        listOf("id", "value", "status", "projectsComments").map { commonPrefix.resolve(it) }

    val expected = SearchResults(listOf(mapOf("id" to "$indicatorId")))

    assertJsonEquals(
        expected,
        searchService.search(commonPrefix, fields, mapOf(commonPrefix to NoConditionNode())),
    )
  }

  @Test
  fun `can reach the report's project from an indicator`() {
    val indicatorId = insertProjectIndicator(projectId = projectId)
    insertReportProjectIndicator(indicatorId = indicatorId)

    val fields = listOf("id", "report.project.id").map { projectPrefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$indicatorId",
                    "report" to mapOf("project" to mapOf("id" to "$projectId")),
                )
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(projectPrefix, fields, mapOf(projectPrefix to NoConditionNode())),
    )
  }
}
