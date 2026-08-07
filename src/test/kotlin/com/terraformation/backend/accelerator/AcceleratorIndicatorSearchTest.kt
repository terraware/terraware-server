package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.AcceleratorPhase
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorFrequency
import com.terraformation.backend.db.accelerator.IndicatorLevel
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
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AcceleratorIndicatorSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables = SearchTables(clock)

  private val commonPrefix: SearchFieldPrefix by lazy {
    SearchFieldPrefix(searchTables.commonIndicators)
  }
  private val projectPrefix: SearchFieldPrefix by lazy {
    SearchFieldPrefix(searchTables.projectIndicators)
  }
  private val autoCalculatedPrefix: SearchFieldPrefix by lazy {
    SearchFieldPrefix(searchTables.autoCalculatedIndicators)
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertOrganizationUser(userId = inserted.userId, role = Role.Admin)
    projectId = insertProject(phase = AcceleratorPhase.Phase0DueDiligence)

    every { user.canReadAllAcceleratorDetails() } returns true
    every { user.organizationRoles } returns mapOf(organizationId to Role.Admin)
  }

  @Test
  fun `returns common indicator details`() {
    val indicatorId =
        insertCommonIndicator(
            active = true,
            category = IndicatorCategory.Climate,
            classId = IndicatorClass.LifetimeCumulative,
            description = "Indicator description",
            frequency = IndicatorFrequency.Quarterly,
            isPublishable = true,
            level = IndicatorLevel.Outcome,
            name = "Hectares restored",
            precision = 2,
            refId = "1.2",
            unit = "Hectares",
        )

    val fields =
        listOf(
                "active",
                "category",
                "class",
                "description",
                "frequency",
                "id",
                "level",
                "name",
                "precision",
                "publishable",
                "refId",
                "unit",
            )
            .map { commonPrefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "active" to "true",
                    "category" to "Climate",
                    "class" to "Lifetime Cumulative",
                    "description" to "Indicator description",
                    "frequency" to "Quarterly",
                    "id" to "$indicatorId",
                    "level" to "Outcome",
                    "name" to "Hectares restored",
                    "precision" to "2",
                    "publishable" to "true",
                    "refId" to "1.2",
                    "unit" to "Hectares",
                )
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(commonPrefix, fields, mapOf(commonPrefix to NoConditionNode())),
    )
  }

  @Test
  fun `returns project indicator details with its project`() {
    val indicatorId =
        insertProjectIndicator(
            name = "Community members trained",
            projectId = projectId,
            refId = "2.1",
            unit = "People",
        )

    val fields =
        listOf("id", "name", "refId", "unit", "project.id").map { projectPrefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$indicatorId",
                    "name" to "Community members trained",
                    "refId" to "2.1",
                    "unit" to "People",
                    "project" to mapOf("id" to "$projectId"),
                )
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(projectPrefix, fields, mapOf(projectPrefix to NoConditionNode())),
    )
  }

  @Test
  fun `returns the built-in auto-calculated indicators`() {
    val fields =
        listOf("indicator", "name", "refId", "unit").map { autoCalculatedPrefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "indicator" to "Seeds Collected",
                    "name" to "Seeds Collected",
                    "refId" to "1.1",
                    "unit" to "Seeds",
                ),
                mapOf(
                    "indicator" to "Hectares Planted",
                    "name" to "Hectares Planted",
                    "refId" to "1.1.1.1",
                    "unit" to "Hectares",
                ),
                mapOf(
                    "indicator" to "Seedlings",
                    "name" to "Seedlings",
                    "refId" to "1.2",
                    "unit" to "Seedlings",
                ),
                mapOf(
                    "indicator" to "Trees Planted",
                    "name" to "Trees Planted",
                    "refId" to "1.3",
                    "unit" to "Trees",
                ),
                mapOf(
                    "indicator" to "Species Planted",
                    "name" to "Species Planted",
                    "refId" to "1.4",
                    "unit" to "Species",
                ),
                mapOf(
                    "indicator" to "Survival Rate",
                    "name" to "Survival Rate",
                    "refId" to "2",
                    "unit" to "%",
                ),
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
  fun `returns report entries as a sublist of each kind of indicator`() {
    insertProjectReportConfig()
    val reportId =
        insertReport(startDate = LocalDate.of(2026, 1, 1), endDate = LocalDate.of(2026, 3, 31))

    val commonIndicatorId = insertCommonIndicator()
    insertReportCommonIndicator(
        indicatorId = commonIndicatorId,
        status = ReportIndicatorStatus.OnTrack,
        value = 10,
    )
    val projectIndicatorId = insertProjectIndicator()
    insertReportProjectIndicator(indicatorId = projectIndicatorId, value = 20)
    insertReportAutoCalculatedIndicator(
        indicator = AutoCalculatedIndicator.TreesPlanted,
        systemValue = 30,
    )

    assertJsonEquals(
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$commonIndicatorId",
                    "reportIndicators" to
                        listOf(
                            mapOf(
                                "value" to "10",
                                "status" to "On-Track",
                                "report" to mapOf("id" to "$reportId"),
                            )
                        ),
                )
            )
        ),
        searchService.search(
            commonPrefix,
            listOf(
                    "id",
                    "reportIndicators.value",
                    "reportIndicators.status",
                    "reportIndicators.report.id",
                )
                .map { commonPrefix.resolve(it) },
            mapOf(commonPrefix to NoConditionNode()),
        ),
        "Common indicator report entries",
    )

    assertJsonEquals(
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$projectIndicatorId",
                    "reportIndicators" to listOf(mapOf("value" to "20")),
                )
            )
        ),
        searchService.search(
            projectPrefix,
            listOf("id", "reportIndicators.value").map { projectPrefix.resolve(it) },
            mapOf(projectPrefix to NoConditionNode()),
        ),
        "Project indicator report entries",
    )

    assertJsonEquals(
        SearchResults(
            listOf(
                mapOf(
                    "indicator" to "Trees Planted",
                    "reportIndicators" to listOf(mapOf("systemValue" to "30")),
                )
            )
        ),
        searchService.search(
            autoCalculatedPrefix,
            listOf("indicator", "reportIndicators.systemValue").map {
              autoCalculatedPrefix.resolve(it)
            },
            mapOf(
                autoCalculatedPrefix to
                    FieldNode(
                        autoCalculatedPrefix.resolve("indicator"),
                        listOf(AutoCalculatedIndicator.TreesPlanted.jsonValue),
                    )
            ),
        ),
        "Auto-calculated indicator report entries",
    )
  }

  @Test
  fun `returns indicator details from a report indicator`() {
    insertProjectReportConfig()
    insertReport(startDate = LocalDate.of(2026, 1, 1), endDate = LocalDate.of(2026, 3, 31))

    val indicatorId = insertCommonIndicator(name = "Hectares restored", refId = "1.2")
    insertReportCommonIndicator(indicatorId = indicatorId, value = 10)

    val prefix = SearchFieldPrefix(searchTables.acceleratorReportCommonIndicators)
    val fields = listOf("value", "indicator.name", "indicator.refId").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "value" to "10",
                    "indicator" to mapOf("name" to "Hectares restored", "refId" to "1.2"),
                )
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
  }

  @Test
  fun `returns each indicator's own report values`() {
    insertProjectReportConfig()
    insertReport(startDate = LocalDate.of(2026, 1, 1), endDate = LocalDate.of(2026, 3, 31))

    val firstIndicatorId = insertCommonIndicator(refId = "1.1")
    insertReportCommonIndicator(indicatorId = firstIndicatorId, value = 10)
    val secondIndicatorId = insertCommonIndicator(refId = "1.2")
    insertReportCommonIndicator(indicatorId = secondIndicatorId, value = 20)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$firstIndicatorId",
                    "reportIndicators" to listOf(mapOf("value" to "10")),
                ),
                mapOf(
                    "id" to "$secondIndicatorId",
                    "reportIndicators" to listOf(mapOf("value" to "20")),
                ),
            )
        )

    assertJsonEquals(
        expected,
        searchService.search(
            commonPrefix,
            listOf("id", "reportIndicators.value").map { commonPrefix.resolve(it) },
            mapOf(commonPrefix to NoConditionNode()),
        ),
    )
  }

  @Test
  fun `does not return report values to users who cannot read the report`() {
    insertProjectReportConfig()
    insertReport(startDate = LocalDate.of(2026, 1, 1), endDate = LocalDate.of(2026, 3, 31))

    val indicatorId = insertCommonIndicator()
    insertReportCommonIndicator(indicatorId = indicatorId, value = 10)

    every { user.canReadAllAcceleratorDetails() } returns false
    every { user.organizationRoles } returns emptyMap()

    val expected = SearchResults(listOf(mapOf("id" to "$indicatorId")))

    assertJsonEquals(
        expected,
        searchService.search(
            commonPrefix,
            listOf("id", "reportIndicators.value").map { commonPrefix.resolve(it) },
            mapOf(commonPrefix to NoConditionNode()),
        ),
    )
  }

  @Test
  fun `can search for flattened indicator fields from report indicators`() {
    insertProjectReportConfig()
    insertReport(startDate = LocalDate.of(2026, 1, 1), endDate = LocalDate.of(2026, 3, 31))

    val indicatorId = insertCommonIndicator(name = "Hectares restored")
    insertReportCommonIndicator(indicatorId = indicatorId, value = 10)

    val prefix = SearchFieldPrefix(searchTables.acceleratorReportCommonIndicators)
    val fields = listOf("value", "indicator_name").map { prefix.resolve(it) }

    val expected =
        SearchResults(listOf(mapOf("value" to "10", "indicator_name" to "Hectares restored")))

    assertJsonEquals(
        expected,
        searchService.search(prefix, fields, mapOf(prefix to NoConditionNode())),
    )
  }

  @Test
  fun `returns indicators ordered by reference ID by default`() {
    val secondIndicatorId = insertCommonIndicator(refId = "1.2")
    val firstIndicatorId = insertCommonIndicator(refId = "1.1")

    val expected =
        SearchResults(
            listOf(mapOf("id" to "$firstIndicatorId"), mapOf("id" to "$secondIndicatorId"))
        )

    assertJsonEquals(
        expected,
        searchService.search(
            commonPrefix,
            listOf(commonPrefix.resolve("id")),
            mapOf(commonPrefix to NoConditionNode()),
        ),
    )
  }

  @Test
  fun `returns only project indicators of projects the user can read`() {
    every { user.canReadAllAcceleratorDetails() } returns false

    val indicatorId = insertProjectIndicator(projectId = projectId)

    insertOrganization()
    insertProject(phase = AcceleratorPhase.Phase0DueDiligence)
    val otherIndicatorId = insertProjectIndicator()

    val fields = listOf(projectPrefix.resolve("id"))

    assertJsonEquals(
        SearchResults(listOf(mapOf("id" to "$indicatorId"))),
        searchService.search(projectPrefix, fields, mapOf(projectPrefix to NoConditionNode())),
        "Project indicators of other organizations should be excluded",
    )

    every { user.organizationRoles } returns mapOf(organizationId to Role.Contributor)

    assertJsonEquals(
        SearchResults(emptyList()),
        searchService.search(projectPrefix, fields, mapOf(projectPrefix to NoConditionNode())),
        "Contributors may not read project indicators",
    )

    every { user.canReadAllAcceleratorDetails() } returns true

    assertJsonEquals(
        SearchResults(listOf(mapOf("id" to "$indicatorId"), mapOf("id" to "$otherIndicatorId"))),
        searchService.search(projectPrefix, fields, mapOf(projectPrefix to NoConditionNode())),
        "Internal users should see project indicators of other organizations",
    )
  }

  @Test
  fun `returns common indicators to users without accelerator access`() {
    every { user.canReadAllAcceleratorDetails() } returns false
    every { user.organizationRoles } returns emptyMap()

    val indicatorId = insertCommonIndicator()

    assertJsonEquals(
        SearchResults(listOf(mapOf("id" to "$indicatorId"))),
        searchService.search(
            commonPrefix,
            listOf(commonPrefix.resolve("id")),
            mapOf(commonPrefix to NoConditionNode()),
        ),
        "Common indicators are not specific to any organization",
    )
  }
}
