package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.accelerator.model.ExistingProjectIndicatorModel
import com.terraformation.backend.accelerator.model.ExistingStandardMetricModel
import com.terraformation.backend.accelerator.model.NewProjectIndicatorModel
import com.terraformation.backend.accelerator.model.NewStandardMetricModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectIndicatorNotFoundException
import com.terraformation.backend.db.StandardMetricNotFoundException
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.tables.records.CommonIndicatorsRecord
import com.terraformation.backend.db.accelerator.tables.records.ProjectIndicatorsRecord
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ReportMetricStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val store: ReportMetricStore by lazy { ReportMetricStore(dslContext) }

  @BeforeEach
  fun setup() {
    insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
  }

  @Nested
  inner class Fetch {

    @Nested
    inner class FetchOneStandardMetric {
      @Test
      fun `returns one standard metric`() {
        val metricId =
            insertStandardMetric(
                component = IndicatorCategory.Climate,
                description = "Climate standard metric description",
                isPublishable = true,
                name = "Climate Standard Metric",
                reference = "3.0",
                type = IndicatorLevel.Activity,
                unit = "degrees",
            )

        assertEquals(
            ExistingStandardMetricModel(
                id = metricId,
                component = IndicatorCategory.Climate,
                description = "Climate standard metric description",
                isPublishable = true,
                name = "Climate Standard Metric",
                reference = "3.0",
                type = IndicatorLevel.Activity,
                unit = "degrees",
            ),
            store.fetchOneStandardMetric(metricId),
        )
      }

      @Test
      fun `throws not found exception if no metric found`() {
        assertThrows<StandardMetricNotFoundException> {
          store.fetchOneStandardMetric(CommonIndicatorId(-1))
        }
      }

      @Test
      fun `throws access denied exception for non-global role users`() {
        val metricId =
            insertStandardMetric(
                component = IndicatorCategory.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                reference = "3.0",
                type = IndicatorLevel.Activity,
                unit = "%",
            )

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        assertThrows<AccessDeniedException> { store.fetchOneStandardMetric(metricId) }

        insertUserGlobalRole(role = GlobalRole.ReadOnly)
        assertDoesNotThrow { store.fetchOneStandardMetric(metricId) }
      }
    }

    @Nested
    inner class FetchAllStandardMetrics {
      @Test
      fun `returns all standard metrics`() {
        val standardMetricId1 =
            insertStandardMetric(
                component = IndicatorCategory.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                reference = "3.0",
                type = IndicatorLevel.Activity,
                unit = "%",
            )

        val standardMetricId2 =
            insertStandardMetric(
                component = IndicatorCategory.Community,
                description = "Community metric description",
                name = "Community Metric",
                reference = "5.0",
                type = IndicatorLevel.Outcome,
                unit = "meters",
            )

        val standardMetricId3 =
            insertStandardMetric(
                component = IndicatorCategory.ProjectObjectives,
                description = "Project objectives metric description",
                isPublishable = false,
                name = "Project Objectives Metric",
                reference = "3.0",
                type = IndicatorLevel.Impact,
                unit = "cm",
            )

        assertEquals(
            listOf(
                // Ordered by reference then ID
                ExistingStandardMetricModel(
                    id = standardMetricId1,
                    component = IndicatorCategory.Climate,
                    description = "Climate standard metric description",
                    isPublishable = true,
                    name = "Climate Standard Metric",
                    reference = "3.0",
                    type = IndicatorLevel.Activity,
                    unit = "%",
                ),
                ExistingStandardMetricModel(
                    id = standardMetricId3,
                    component = IndicatorCategory.ProjectObjectives,
                    description = "Project objectives metric description",
                    isPublishable = false,
                    name = "Project Objectives Metric",
                    reference = "3.0",
                    type = IndicatorLevel.Impact,
                    unit = "cm",
                ),
                ExistingStandardMetricModel(
                    id = standardMetricId2,
                    component = IndicatorCategory.Community,
                    description = "Community metric description",
                    isPublishable = true,
                    name = "Community Metric",
                    reference = "5.0",
                    type = IndicatorLevel.Outcome,
                    unit = "meters",
                ),
            ),
            store.fetchAllStandardMetrics(),
        )
      }

      @Test
      fun `sorted by reference correctly`() {
        val metricId1 = insertStandardMetric(reference = "2.0.2")
        val metricId2 = insertStandardMetric(reference = "10.0")
        val metricId3 = insertStandardMetric(reference = "1.0")
        val metricId4 = insertStandardMetric(reference = "2.0")
        val metricId5 = insertStandardMetric(reference = "1.1")
        val metricId6 = insertStandardMetric(reference = "1.1.1")
        val metricId7 = insertStandardMetric(reference = "1.2")

        assertEquals(
            listOf(
                metricId3, // 1.0
                metricId5, // 1.1
                metricId6, // 1.1.1
                metricId7, // 1.2
                metricId4, // 2.0
                metricId1, // 2.0.2
                metricId2, // 10.0
            ),
            store.fetchAllStandardMetrics().map { it.id },
        )
      }

      @Test
      fun `throws access denied exception for non-global role users`() {
        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        assertThrows<AccessDeniedException> { store.fetchAllStandardMetrics() }

        insertUserGlobalRole(role = GlobalRole.ReadOnly)
        assertDoesNotThrow { store.fetchAllStandardMetrics() }
      }
    }

    @Nested
    inner class FetchOneProjectIndicator {
      @Test
      fun `returns one project indicator`() {
        insertOrganization()
        val projectId = insertProject()
        val indicatorId =
            insertProjectIndicator(
                component = IndicatorCategory.Climate,
                description = "Climate project indicator description",
                name = "Climate Project Indicator",
                isPublishable = false,
                projectId = projectId,
                reference = "3.0",
                type = IndicatorLevel.Activity,
                unit = "degrees",
            )

        assertEquals(
            ExistingProjectIndicatorModel(
                id = indicatorId,
                projectId = projectId,
                component = IndicatorCategory.Climate,
                description = "Climate project indicator description",
                isPublishable = false,
                name = "Climate Project Indicator",
                reference = "3.0",
                type = IndicatorLevel.Activity,
                unit = "degrees",
            ),
            store.fetchOneProjectIndicator(indicatorId),
        )
      }

      @Test
      fun `throws not found exception if no metric found`() {
        assertThrows<ProjectIndicatorNotFoundException> {
          store.fetchOneProjectIndicator(ProjectIndicatorId(-1))
        }
      }

      @Test
      fun `sorted by reference correctly`() {
        insertOrganization()
        val projectId = insertProject()

        val indicatorId1 = insertProjectIndicator(reference = "2.0.2")
        val indicatorId2 = insertProjectIndicator(reference = "10.0")
        val indicatorId3 = insertProjectIndicator(reference = "1.0")
        val indicatorId4 = insertProjectIndicator(reference = "2.0")
        val indicatorId5 = insertProjectIndicator(reference = "1.1")
        val indicatorId6 = insertProjectIndicator(reference = "1.1.1")
        val indicatorId7 = insertProjectIndicator(reference = "1.2")

        assertEquals(
            listOf(
                indicatorId3, // 1.0
                indicatorId5, // 1.1
                indicatorId6, // 1.1.1
                indicatorId7, // 1.2
                indicatorId4, // 2.0
                indicatorId1, // 2.0.2
                indicatorId2, // 10.0
            ),
            store.fetchProjectIndicatorsForProject(projectId).map { it.id },
        )
      }

      @Test
      fun `throws access denied exception for non-global role users`() {
        insertOrganization()
        val projectId = insertProject()
        val indicatorId =
            insertProjectIndicator(
                component = IndicatorCategory.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                projectId = projectId,
                reference = "3.0",
                type = IndicatorLevel.Activity,
            )

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        assertThrows<AccessDeniedException> { store.fetchOneProjectIndicator(indicatorId) }

        insertUserGlobalRole(role = GlobalRole.ReadOnly)
        assertDoesNotThrow { store.fetchOneProjectIndicator(indicatorId) }
      }
    }

    @Nested
    inner class FetchProjectIndicatorsForProject {
      @Test
      fun `returns all project indicators for one project`() {
        insertOrganization()
        val projectId = insertProject()
        val indicatorId1 =
            insertProjectIndicator(
                component = IndicatorCategory.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                projectId = projectId,
                reference = "3.0",
                type = IndicatorLevel.Activity,
                unit = "%",
            )

        val indicatorId2 =
            insertProjectIndicator(
                component = IndicatorCategory.Community,
                description = "Community metric description",
                isPublishable = false,
                name = "Community Metric",
                projectId = projectId,
                reference = "5.0",
                type = IndicatorLevel.Outcome,
                unit = "meters",
            )

        val indicatorId3 =
            insertProjectIndicator(
                component = IndicatorCategory.ProjectObjectives,
                description = "Project objectives metric description",
                name = "Project Objectives Metric",
                projectId = projectId,
                reference = "3.0",
                type = IndicatorLevel.Impact,
                unit = "cm",
            )

        // Other project indicators will not be returned
        val otherProjectId = insertProject()
        insertProjectIndicator(projectId = otherProjectId)

        assertEquals(
            listOf(
                // Ordered by reference then ID
                ExistingProjectIndicatorModel(
                    id = indicatorId1,
                    projectId = projectId,
                    component = IndicatorCategory.Climate,
                    description = "Climate standard metric description",
                    isPublishable = true,
                    name = "Climate Standard Metric",
                    reference = "3.0",
                    type = IndicatorLevel.Activity,
                    unit = "%",
                ),
                ExistingProjectIndicatorModel(
                    id = indicatorId3,
                    projectId = projectId,
                    component = IndicatorCategory.ProjectObjectives,
                    description = "Project objectives metric description",
                    isPublishable = true,
                    name = "Project Objectives Metric",
                    reference = "3.0",
                    type = IndicatorLevel.Impact,
                    unit = "cm",
                ),
                ExistingProjectIndicatorModel(
                    id = indicatorId2,
                    projectId = projectId,
                    component = IndicatorCategory.Community,
                    description = "Community metric description",
                    isPublishable = false,
                    name = "Community Metric",
                    reference = "5.0",
                    type = IndicatorLevel.Outcome,
                    unit = "meters",
                ),
            ),
            store.fetchProjectIndicatorsForProject(projectId),
        )
      }

      @Test
      fun `throws access denied exception for non-global role users`() {
        insertOrganization()
        val projectId = insertProject()
        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        assertThrows<AccessDeniedException> { store.fetchProjectIndicatorsForProject(projectId) }

        insertUserGlobalRole(role = GlobalRole.ReadOnly)
        assertDoesNotThrow { store.fetchProjectIndicatorsForProject(projectId) }
      }
    }

    @Nested
    inner class FetchSystemMetrics {
      @Test
      fun `returns all system metrics, ordered by reference`() {
        val sortedSystemMetrics =
            AutoCalculatedIndicator.entries.sortedWith { metric1, metric2 ->
              val metric1Parts = metric1.refId.split(".").map { it.toInt() }
              val metric2Parts = metric2.refId.split(".").map { it.toInt() }

              val size = maxOf(metric1Parts.size, metric2Parts.size)
              for (i in 0 until size) {
                val part1 = metric1Parts.getOrElse(i) { 0 }
                val part2 = metric2Parts.getOrElse(i) { 0 }
                if (part1 != part2) {
                  return@sortedWith part1.compareTo(part2)
                }
              }
              return@sortedWith 0
            }

        assertEquals(sortedSystemMetrics, store.fetchSystemMetrics())
      }

      @Test
      fun `throws access denied exception for non-global role users`() {
        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        assertThrows<AccessDeniedException> { store.fetchSystemMetrics() }

        insertUserGlobalRole(role = GlobalRole.ReadOnly)
        assertDoesNotThrow { store.fetchSystemMetrics() }
      }
    }
  }

  @Nested
  inner class Create {
    @Nested
    inner class StandardMetrics {
      @Test
      fun `inserts new record`() {
        val existingMetricId =
            insertStandardMetric(
                component = IndicatorCategory.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                reference = "3.0",
                type = IndicatorLevel.Activity,
                unit = "%",
            )

        val model =
            NewStandardMetricModel(
                id = null,
                component = IndicatorCategory.ProjectObjectives,
                description = "Project objectives metric description",
                isPublishable = false,
                name = "Project Objectives Metric",
                reference = "1.0",
                type = IndicatorLevel.Impact,
                unit = "meters",
            )

        val newMetricId = store.createStandardMetric(model)

        assertTableEquals(
            listOf(
                CommonIndicatorsRecord(
                    id = existingMetricId,
                    categoryId = IndicatorCategory.Climate,
                    description = "Climate standard metric description",
                    isPublishable = true,
                    name = "Climate Standard Metric",
                    refId = "3.0",
                    levelId = IndicatorLevel.Activity,
                    unit = "%",
                    active = true,
                ),
                CommonIndicatorsRecord(
                    id = newMetricId,
                    categoryId = IndicatorCategory.ProjectObjectives,
                    description = "Project objectives metric description",
                    isPublishable = false,
                    name = "Project Objectives Metric",
                    refId = "1.0",
                    levelId = IndicatorLevel.Impact,
                    unit = "meters",
                    active = true,
                ),
            )
        )
      }

      @Test
      fun `throws access denied exception for non-accelerator admin`() {
        val model =
            NewStandardMetricModel(
                id = null,
                component = IndicatorCategory.ProjectObjectives,
                description = "Project objectives metric description",
                isPublishable = true,
                name = "Project Objectives Metric",
                reference = "1.0",
                type = IndicatorLevel.Impact,
                unit = "%",
            )

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        insertUserGlobalRole(role = GlobalRole.TFExpert)
        assertThrows<AccessDeniedException> { store.createStandardMetric(model) }
      }
    }

    @Nested
    inner class ProjectIndicator {
      @Test
      fun `inserts new record`() {
        insertOrganization()
        val projectId = insertProject()
        val existingIndicatorId =
            insertProjectIndicator(
                component = IndicatorCategory.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                projectId = projectId,
                reference = "3.0",
                type = IndicatorLevel.Activity,
                unit = "meters",
            )

        val model =
            NewProjectIndicatorModel(
                id = null,
                projectId = projectId,
                component = IndicatorCategory.ProjectObjectives,
                description = "Project objectives metric description",
                isPublishable = false,
                name = "Project Objectives Metric",
                reference = "1.0",
                type = IndicatorLevel.Impact,
                unit = "%",
            )

        val newIndicatorId = store.createProjectIndicator(model)

        assertTableEquals(
            listOf(
                ProjectIndicatorsRecord(
                    id = existingIndicatorId,
                    categoryId = IndicatorCategory.Climate,
                    description = "Climate standard metric description",
                    isPublishable = true,
                    name = "Climate Standard Metric",
                    projectId = projectId,
                    refId = "3.0",
                    levelId = IndicatorLevel.Activity,
                    unit = "meters",
                    active = true,
                ),
                ProjectIndicatorsRecord(
                    id = newIndicatorId,
                    categoryId = IndicatorCategory.ProjectObjectives,
                    description = "Project objectives metric description",
                    isPublishable = false,
                    name = "Project Objectives Metric",
                    projectId = projectId,
                    refId = "1.0",
                    levelId = IndicatorLevel.Impact,
                    unit = "%",
                    active = true,
                ),
            )
        )
      }

      @Test
      fun `throws access denied exception for non-accelerator admin`() {
        insertOrganization()
        val projectId = insertProject()
        val model =
            NewProjectIndicatorModel(
                id = null,
                component = IndicatorCategory.ProjectObjectives,
                description = "Project objectives metric description",
                isPublishable = true,
                name = "Project Objectives Metric",
                projectId = projectId,
                reference = "1.0",
                type = IndicatorLevel.Impact,
            )

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        insertUserGlobalRole(role = GlobalRole.TFExpert)
        assertThrows<AccessDeniedException> { store.createProjectIndicator(model) }
      }
    }
  }

  @Nested
  inner class Update {
    @Nested
    inner class StandardMetrics {
      @Test
      fun `updates existing record`() {
        val existingMetricId =
            insertStandardMetric(
                component = IndicatorCategory.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                reference = "3.0",
                type = IndicatorLevel.Activity,
                unit = "%",
            )

        val updated =
            ExistingStandardMetricModel(
                id = CommonIndicatorId(99), // this field is ignored
                component = IndicatorCategory.ProjectObjectives,
                description = "Project objectives metric description",
                isPublishable = false,
                name = "Project Objectives Metric",
                reference = "1.0",
                type = IndicatorLevel.Impact,
                unit = "meters",
            )

        store.updateStandardMetric(existingMetricId) { updated }

        assertTableEquals(
            listOf(
                CommonIndicatorsRecord(
                    id = existingMetricId,
                    categoryId = IndicatorCategory.ProjectObjectives,
                    description = "Project objectives metric description",
                    isPublishable = false,
                    name = "Project Objectives Metric",
                    refId = "1.0",
                    levelId = IndicatorLevel.Impact,
                    unit = "meters",
                    active = true,
                )
            )
        )
      }

      @Test
      fun `throws access denied exception for non-accelerator admin`() {
        val existingMetricId =
            insertStandardMetric(
                component = IndicatorCategory.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                reference = "3.0",
                type = IndicatorLevel.Activity,
                unit = "%",
            )

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        insertUserGlobalRole(role = GlobalRole.TFExpert)
        assertThrows<AccessDeniedException> {
          store.updateStandardMetric(existingMetricId) { it.copy(reference = "1.0") }
        }
      }
    }
  }

  @Nested
  inner class ProjectIndicators {
    @Test
    fun `updates existing record`() {
      insertOrganization()
      val projectId = insertProject()
      val existingIndicatorId =
          insertProjectIndicator(
              component = IndicatorCategory.Climate,
              description = "Climate standard metric description",
              isPublishable = false,
              name = "Climate Standard Metric",
              projectId = projectId,
              reference = "3.0",
              type = IndicatorLevel.Activity,
              unit = "feet",
          )

      val updated =
          ExistingProjectIndicatorModel(
              id = ProjectIndicatorId(99), // this field is ignored
              component = IndicatorCategory.ProjectObjectives,
              description = "Project objectives metric description",
              isPublishable = true,
              name = "Project Objectives Metric",
              projectId = ProjectId(99), // this field is ignored
              reference = "1.0",
              type = IndicatorLevel.Impact,
              unit = "inches",
          )

      store.updateProjectIndicator(existingIndicatorId) { updated }

      assertTableEquals(
          listOf(
              ProjectIndicatorsRecord(
                  id = existingIndicatorId,
                  categoryId = IndicatorCategory.ProjectObjectives,
                  description = "Project objectives metric description",
                  isPublishable = true,
                  name = "Project Objectives Metric",
                  projectId = projectId,
                  refId = "1.0",
                  levelId = IndicatorLevel.Impact,
                  unit = "inches",
                  active = true,
              )
          )
      )
    }

    @Test
    fun `throws access denied exception for non-accelerator admin`() {
      insertOrganization()
      val projectId = insertProject()
      val existingIndicatorId =
          insertProjectIndicator(
              component = IndicatorCategory.Climate,
              description = "Climate standard metric description",
              name = "Climate Standard Metric",
              projectId = projectId,
              reference = "3.0",
              type = IndicatorLevel.Activity,
          )

      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.TFExpert)
      assertThrows<AccessDeniedException> {
        store.updateProjectIndicator(existingIndicatorId) { it.copy(reference = "1.0") }
      }
    }
  }
}
