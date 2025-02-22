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
import com.terraformation.backend.db.accelerator.tables.records.ProjectMetricsRecord
import com.terraformation.backend.db.accelerator.tables.records.StandardMetricsRecord
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
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
                name = "Climate Standard Metric",
                reference = "3.0",
                type = MetricType.Activity,
            )

        assertEquals(
            ExistingStandardMetricModel(
                id = metricId,
                component = MetricComponent.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                reference = "3.0",
                type = MetricType.Activity,
            ),
            store.fetchOneStandardMetric(metricId))
      }

      @Test
      fun `throws not found exception if no metric found`() {
        assertThrows<StandardMetricNotFoundException> {
          store.fetchOneStandardMetric(StandardMetricId(-1))
        }
      }

      @Test
      fun `throws access denied exception for non-accelerator admin`() {
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
                    name = "Climate Standard Metric",
                    reference = "3.0",
                    type = MetricType.Activity,
                ),
                ExistingStandardMetricModel(
                    id = standardMetricId3,
                    component = MetricComponent.ProjectObjectives,
                    description = "Project objectives metric description",
                    name = "Project Objectives Metric",
                    reference = "3.0",
                    type = MetricType.Impact,
                ),
                ExistingStandardMetricModel(
                    id = standardMetricId2,
                    component = MetricComponent.Community,
                    description = "Community metric description",
                    name = "Community Metric",
                    reference = "5.0",
                    type = MetricType.Outcome,
                ),
            ),
            store.fetchAllStandardMetrics())
      }

      @Test
      fun `throws access denied exception for non-accelerator admin`() {
        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        assertThrows<AccessDeniedException> { store.fetchAllStandardMetrics() }
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
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                projectId = projectId,
                reference = "3.0",
                type = MetricType.Activity,
            )

        assertEquals(
            ExistingProjectMetricModel(
                id = metricId,
                projectId = projectId,
                component = MetricComponent.Climate,
                description = "Climate standard metric description",
                name = "Climate Standard Metric",
                reference = "3.0",
                type = MetricType.Activity,
            ),
            store.fetchOneProjectMetric(metricId))
      }

      @Test
      fun `throws not found exception if no metric found`() {
        assertThrows<ProjectMetricNotFoundException> {
          store.fetchOneProjectMetric(ProjectMetricId(-1))
        }
      }

      @Test
      fun `throws access denied exception for non-accelerator admin`() {
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
            )

        val metricId2 =
            insertProjectMetric(
                component = MetricComponent.Community,
                description = "Community metric description",
                name = "Community Metric",
                projectId = projectId,
                reference = "5.0",
                type = MetricType.Outcome,
            )

        val metricId3 =
            insertProjectMetric(
                component = MetricComponent.ProjectObjectives,
                description = "Project objectives metric description",
                name = "Project Objectives Metric",
                projectId = projectId,
                reference = "3.0",
                type = MetricType.Impact,
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
                    name = "Climate Standard Metric",
                    reference = "3.0",
                    type = MetricType.Activity,
                ),
                ExistingProjectMetricModel(
                    id = metricId3,
                    projectId = projectId,
                    component = MetricComponent.ProjectObjectives,
                    description = "Project objectives metric description",
                    name = "Project Objectives Metric",
                    reference = "3.0",
                    type = MetricType.Impact,
                ),
                ExistingProjectMetricModel(
                    id = metricId2,
                    projectId = projectId,
                    component = MetricComponent.Community,
                    description = "Community metric description",
                    name = "Community Metric",
                    reference = "5.0",
                    type = MetricType.Outcome,
                ),
            ),
            store.fetchProjectMetricsForProject(projectId))
      }

      @Test
      fun `throws access denied exception for non-accelerator admin`() {
        insertOrganization()
        val projectId = insertProject()
        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        assertThrows<AccessDeniedException> { store.fetchProjectMetricsForProject(projectId) }
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
                    name = "Climate Standard Metric",
                    reference = "3.0",
                    typeId = MetricType.Activity,
                ),
                StandardMetricsRecord(
                    id = newMetricId,
                    componentId = MetricComponent.ProjectObjectives,
                    description = "Project objectives metric description",
                    name = "Project Objectives Metric",
                    reference = "1.0",
                    typeId = MetricType.Impact,
                )))
      }

      @Test
      fun `throws access denied exception for non-accelerator admin`() {
        val model =
            NewStandardMetricModel(
                id = null,
                component = MetricComponent.ProjectObjectives,
                description = "Project objectives metric description",
                name = "Project Objectives Metric",
                reference = "1.0",
                type = MetricType.Impact,
            )

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
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
            )

        val model =
            NewProjectMetricModel(
                id = null,
                projectId = projectId,
                component = MetricComponent.ProjectObjectives,
                description = "Project objectives metric description",
                name = "Project Objectives Metric",
                reference = "1.0",
                type = MetricType.Impact,
            )

        val newMetricId = store.createProjectMetric(model)

        assertTableEquals(
            listOf(
                ProjectMetricsRecord(
                    id = existingMetricId,
                    componentId = MetricComponent.Climate,
                    description = "Climate standard metric description",
                    name = "Climate Standard Metric",
                    projectId = projectId,
                    reference = "3.0",
                    typeId = MetricType.Activity,
                ),
                ProjectMetricsRecord(
                    id = newMetricId,
                    componentId = MetricComponent.ProjectObjectives,
                    description = "Project objectives metric description",
                    name = "Project Objectives Metric",
                    projectId = projectId,
                    reference = "1.0",
                    typeId = MetricType.Impact,
                )))
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
                name = "Project Objectives Metric",
                projectId = projectId,
                reference = "1.0",
                type = MetricType.Impact,
            )

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
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
                    name = "Project Objectives Metric",
                    reference = "1.0",
                    typeId = MetricType.Impact,
                )))
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
              name = "Climate Standard Metric",
              projectId = projectId,
              reference = "3.0",
              type = MetricType.Activity,
          )

      val updated =
          ExistingProjectMetricModel(
              id = ProjectMetricId(99), // this field is ignored
              component = MetricComponent.ProjectObjectives,
              description = "Project objectives metric description",
              name = "Project Objectives Metric",
              projectId = ProjectId(99), // this field is ignored
              reference = "1.0",
              type = MetricType.Impact,
          )

      store.updateProjectMetric(existingMetricId) { updated }

      assertTableEquals(
          listOf(
              ProjectMetricsRecord(
                  id = existingMetricId,
                  componentId = MetricComponent.ProjectObjectives,
                  description = "Project objectives metric description",
                  name = "Project Objectives Metric",
                  projectId = projectId,
                  reference = "1.0",
                  typeId = MetricType.Impact,
              )))
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
      assertThrows<AccessDeniedException> {
        store.updateProjectMetric(existingMetricId) { it.copy(reference = "1.0") }
      }
    }
  }
}
