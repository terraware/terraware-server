package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.accelerator.model.ExistingProjectMetricModel
import com.terraformation.backend.accelerator.model.ExistingStandardMetricModel
import com.terraformation.backend.accelerator.model.NewProjectMetricModel
import com.terraformation.backend.accelerator.model.NewStandardMetricModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectMetricNotFoundException
import com.terraformation.backend.db.StandardMetricNotFoundException
import com.terraformation.backend.db.accelerator.MetricComponent
import com.terraformation.backend.db.accelerator.MetricType
import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.accelerator.tables.records.ProjectMetricsRecord
import com.terraformation.backend.db.accelerator.tables.records.StandardMetricsRecord
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
                component = MetricComponent.Climate,
                description = "Climate standard metric description",
                isPublishable = true,
                name = "Climate Standard Metric",
                reference = "3.0",
                type = MetricType.Activity,
            )

        assertEquals(
            ExistingStandardMetricModel(
                id = metricId,
                component = MetricComponent.Climate,
                description = "Climate standard metric description",
                isPublishable = true,
                name = "Climate Standard Metric",
                reference = "3.0",
                type = MetricType.Activity,
            ),
            store.fetchOneStandardMetric(metricId),
        )
      }

      @Test
      fun `throws not found exception if no metric found`() {
        assertThrows<StandardMetricNotFoundException> {
          store.fetchOneStandardMetric(StandardMetricId(-1))
        }
      }

      @Test
      fun `throws access denied exception for non-global role users`() {
        val metricId =
            insertStandardMetric(
                component = MetricComponent.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                reference = "3.0",
                type = MetricType.Activity,
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
                component = MetricComponent.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                reference = "3.0",
                type = MetricType.Activity,
            )

        val standardMetricId2 =
            insertStandardMetric(
                component = MetricComponent.Community,
                description = "Community metric description",
                name = "Community Metric",
                reference = "5.0",
                type = MetricType.Outcome,
            )

        val standardMetricId3 =
            insertStandardMetric(
                component = MetricComponent.ProjectObjectives,
                description = "Project objectives metric description",
                isPublishable = false,
                name = "Project Objectives Metric",
                reference = "3.0",
                type = MetricType.Impact,
            )

        assertEquals(
            listOf(
                // Ordered by reference then ID
                ExistingStandardMetricModel(
                    id = standardMetricId1,
                    component = MetricComponent.Climate,
                    description = "Climate standard metric description",
                    isPublishable = true,
                    name = "Climate Standard Metric",
                    reference = "3.0",
                    type = MetricType.Activity,
                ),
                ExistingStandardMetricModel(
                    id = standardMetricId3,
                    component = MetricComponent.ProjectObjectives,
                    description = "Project objectives metric description",
                    isPublishable = false,
                    name = "Project Objectives Metric",
                    reference = "3.0",
                    type = MetricType.Impact,
                ),
                ExistingStandardMetricModel(
                    id = standardMetricId2,
                    component = MetricComponent.Community,
                    description = "Community metric description",
                    isPublishable = true,
                    name = "Community Metric",
                    reference = "5.0",
                    type = MetricType.Outcome,
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
    inner class FetchOneProjectMetric {
      @Test
      fun `returns one project metric`() {
        insertOrganization()
        val projectId = insertProject()
        val metricId =
            insertProjectMetric(
                component = MetricComponent.Climate,
                description = "Climate project metric description",
                name = "Climate Project Metric",
                isPublishable = false,
                projectId = projectId,
                reference = "3.0",
                type = MetricType.Activity,
                unit = "degrees",
            )

        assertEquals(
            ExistingProjectMetricModel(
                id = metricId,
                projectId = projectId,
                component = MetricComponent.Climate,
                description = "Climate project metric description",
                isPublishable = false,
                name = "Climate Project Metric",
                reference = "3.0",
                type = MetricType.Activity,
                unit = "degrees",
            ),
            store.fetchOneProjectMetric(metricId),
        )
      }

      @Test
      fun `throws not found exception if no metric found`() {
        assertThrows<ProjectMetricNotFoundException> {
          store.fetchOneProjectMetric(ProjectMetricId(-1))
        }
      }

      @Test
      fun `sorted by reference correctly`() {
        insertOrganization()
        val projectId = insertProject()

        val metricId1 = insertProjectMetric(reference = "2.0.2")
        val metricId2 = insertProjectMetric(reference = "10.0")
        val metricId3 = insertProjectMetric(reference = "1.0")
        val metricId4 = insertProjectMetric(reference = "2.0")
        val metricId5 = insertProjectMetric(reference = "1.1")
        val metricId6 = insertProjectMetric(reference = "1.1.1")
        val metricId7 = insertProjectMetric(reference = "1.2")

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
            store.fetchProjectMetricsForProject(projectId).map { it.id },
        )
      }

      @Test
      fun `throws access denied exception for non-global role users`() {
        insertOrganization()
        val projectId = insertProject()
        val metricId =
            insertProjectMetric(
                component = MetricComponent.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                projectId = projectId,
                reference = "3.0",
                type = MetricType.Activity,
            )

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        assertThrows<AccessDeniedException> { store.fetchOneProjectMetric(metricId) }

        insertUserGlobalRole(role = GlobalRole.ReadOnly)
        assertDoesNotThrow { store.fetchOneProjectMetric(metricId) }
      }
    }

    @Nested
    inner class FetchProjectMetricsForProject {
      @Test
      fun `returns all project metrics for one project`() {
        insertOrganization()
        val projectId = insertProject()
        val metricId1 =
            insertProjectMetric(
                component = MetricComponent.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                projectId = projectId,
                reference = "3.0",
                type = MetricType.Activity,
                unit = "%",
            )

        val metricId2 =
            insertProjectMetric(
                component = MetricComponent.Community,
                description = "Community metric description",
                isPublishable = false,
                name = "Community Metric",
                projectId = projectId,
                reference = "5.0",
                type = MetricType.Outcome,
                unit = "meters",
            )

        val metricId3 =
            insertProjectMetric(
                component = MetricComponent.ProjectObjectives,
                description = "Project objectives metric description",
                name = "Project Objectives Metric",
                projectId = projectId,
                reference = "3.0",
                type = MetricType.Impact,
                unit = "cm",
            )

        // Other project metrics will not be returned
        val otherProjectId = insertProject()
        insertProjectMetric(projectId = otherProjectId)

        assertEquals(
            listOf(
                // Ordered by reference then ID
                ExistingProjectMetricModel(
                    id = metricId1,
                    projectId = projectId,
                    component = MetricComponent.Climate,
                    description = "Climate standard metric description",
                    isPublishable = true,
                    name = "Climate Standard Metric",
                    reference = "3.0",
                    type = MetricType.Activity,
                    unit = "%",
                ),
                ExistingProjectMetricModel(
                    id = metricId3,
                    projectId = projectId,
                    component = MetricComponent.ProjectObjectives,
                    description = "Project objectives metric description",
                    isPublishable = true,
                    name = "Project Objectives Metric",
                    reference = "3.0",
                    type = MetricType.Impact,
                    unit = "cm",
                ),
                ExistingProjectMetricModel(
                    id = metricId2,
                    projectId = projectId,
                    component = MetricComponent.Community,
                    description = "Community metric description",
                    isPublishable = false,
                    name = "Community Metric",
                    reference = "5.0",
                    type = MetricType.Outcome,
                    unit = "meters",
                ),
            ),
            store.fetchProjectMetricsForProject(projectId),
        )
      }

      @Test
      fun `throws access denied exception for non-global role users`() {
        insertOrganization()
        val projectId = insertProject()
        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        assertThrows<AccessDeniedException> { store.fetchProjectMetricsForProject(projectId) }

        insertUserGlobalRole(role = GlobalRole.ReadOnly)
        assertDoesNotThrow { store.fetchProjectMetricsForProject(projectId) }
      }
    }

    @Nested
    inner class FetchSystemMetrics {
      @Test
      fun `returns all system metrics, ordered by reference`() {
        val sortedSystemMetrics =
            SystemMetric.entries.sortedWith { metric1, metric2 ->
              val metric1Parts = metric1.reference.split(".").map { it.toInt() }
              val metric2Parts = metric2.reference.split(".").map { it.toInt() }

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
                component = MetricComponent.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                reference = "3.0",
                type = MetricType.Activity,
            )

        val model =
            NewStandardMetricModel(
                id = null,
                component = MetricComponent.ProjectObjectives,
                description = "Project objectives metric description",
                isPublishable = false,
                name = "Project Objectives Metric",
                reference = "1.0",
                type = MetricType.Impact,
            )

        val newMetricId = store.createStandardMetric(model)

        assertTableEquals(
            listOf(
                StandardMetricsRecord(
                    id = existingMetricId,
                    componentId = MetricComponent.Climate,
                    description = "Climate standard metric description",
                    isPublishable = true,
                    name = "Climate Standard Metric",
                    reference = "3.0",
                    typeId = MetricType.Activity,
                ),
                StandardMetricsRecord(
                    id = newMetricId,
                    componentId = MetricComponent.ProjectObjectives,
                    description = "Project objectives metric description",
                    isPublishable = false,
                    name = "Project Objectives Metric",
                    reference = "1.0",
                    typeId = MetricType.Impact,
                ),
            )
        )
      }

      @Test
      fun `throws access denied exception for non-accelerator admin`() {
        val model =
            NewStandardMetricModel(
                id = null,
                component = MetricComponent.ProjectObjectives,
                description = "Project objectives metric description",
                isPublishable = true,
                name = "Project Objectives Metric",
                reference = "1.0",
                type = MetricType.Impact,
            )

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        insertUserGlobalRole(role = GlobalRole.TFExpert)
        assertThrows<AccessDeniedException> { store.createStandardMetric(model) }
      }
    }

    @Nested
    inner class ProjectMetric {
      @Test
      fun `inserts new record`() {
        insertOrganization()
        val projectId = insertProject()
        val existingMetricId =
            insertProjectMetric(
                component = MetricComponent.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                projectId = projectId,
                reference = "3.0",
                type = MetricType.Activity,
                unit = "meters",
            )

        val model =
            NewProjectMetricModel(
                id = null,
                projectId = projectId,
                component = MetricComponent.ProjectObjectives,
                description = "Project objectives metric description",
                isPublishable = false,
                name = "Project Objectives Metric",
                reference = "1.0",
                type = MetricType.Impact,
                unit = "%",
            )

        val newMetricId = store.createProjectMetric(model)

        assertTableEquals(
            listOf(
                ProjectMetricsRecord(
                    id = existingMetricId,
                    componentId = MetricComponent.Climate,
                    description = "Climate standard metric description",
                    isPublishable = true,
                    name = "Climate Standard Metric",
                    projectId = projectId,
                    reference = "3.0",
                    typeId = MetricType.Activity,
                    unit = "meters",
                ),
                ProjectMetricsRecord(
                    id = newMetricId,
                    componentId = MetricComponent.ProjectObjectives,
                    description = "Project objectives metric description",
                    isPublishable = false,
                    name = "Project Objectives Metric",
                    projectId = projectId,
                    reference = "1.0",
                    typeId = MetricType.Impact,
                    unit = "%",
                ),
            )
        )
      }

      @Test
      fun `throws access denied exception for non-accelerator admin`() {
        insertOrganization()
        val projectId = insertProject()
        val model =
            NewProjectMetricModel(
                id = null,
                component = MetricComponent.ProjectObjectives,
                description = "Project objectives metric description",
                isPublishable = true,
                name = "Project Objectives Metric",
                projectId = projectId,
                reference = "1.0",
                type = MetricType.Impact,
            )

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        insertUserGlobalRole(role = GlobalRole.TFExpert)
        assertThrows<AccessDeniedException> { store.createProjectMetric(model) }
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
                component = MetricComponent.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                reference = "3.0",
                type = MetricType.Activity,
            )

        val updated =
            ExistingStandardMetricModel(
                id = StandardMetricId(99), // this field is ignored
                component = MetricComponent.ProjectObjectives,
                description = "Project objectives metric description",
                isPublishable = false,
                name = "Project Objectives Metric",
                reference = "1.0",
                type = MetricType.Impact,
            )

        store.updateStandardMetric(existingMetricId) { updated }

        assertTableEquals(
            listOf(
                StandardMetricsRecord(
                    id = existingMetricId,
                    componentId = MetricComponent.ProjectObjectives,
                    description = "Project objectives metric description",
                    isPublishable = false,
                    name = "Project Objectives Metric",
                    reference = "1.0",
                    typeId = MetricType.Impact,
                )
            )
        )
      }

      @Test
      fun `throws access denied exception for non-accelerator admin`() {
        val existingMetricId =
            insertStandardMetric(
                component = MetricComponent.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                reference = "3.0",
                type = MetricType.Activity,
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
  inner class ProjectMetrics {
    @Test
    fun `updates existing record`() {
      insertOrganization()
      val projectId = insertProject()
      val existingMetricId =
          insertProjectMetric(
              component = MetricComponent.Climate,
              description = "Climate standard metric description",
              isPublishable = false,
              name = "Climate Standard Metric",
              projectId = projectId,
              reference = "3.0",
              type = MetricType.Activity,
              unit = "feet",
          )

      val updated =
          ExistingProjectMetricModel(
              id = ProjectMetricId(99), // this field is ignored
              component = MetricComponent.ProjectObjectives,
              description = "Project objectives metric description",
              isPublishable = true,
              name = "Project Objectives Metric",
              projectId = ProjectId(99), // this field is ignored
              reference = "1.0",
              type = MetricType.Impact,
              unit = "inches",
          )

      store.updateProjectMetric(existingMetricId) { updated }

      assertTableEquals(
          listOf(
              ProjectMetricsRecord(
                  id = existingMetricId,
                  componentId = MetricComponent.ProjectObjectives,
                  description = "Project objectives metric description",
                  isPublishable = true,
                  name = "Project Objectives Metric",
                  projectId = projectId,
                  reference = "1.0",
                  typeId = MetricType.Impact,
                  unit = "inches",
              )
          )
      )
    }

    @Test
    fun `throws access denied exception for non-accelerator admin`() {
      insertOrganization()
      val projectId = insertProject()
      val existingMetricId =
          insertProjectMetric(
              component = MetricComponent.Climate,
              description = "Climate standard metric description",
              name = "Climate Standard Metric",
              projectId = projectId,
              reference = "3.0",
              type = MetricType.Activity,
          )

      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.TFExpert)
      assertThrows<AccessDeniedException> {
        store.updateProjectMetric(existingMetricId) { it.copy(reference = "1.0") }
      }
    }
  }
}
