package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.MetricComponent
import com.terraformation.backend.db.accelerator.MetricType
import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_METRICS
import com.terraformation.backend.db.default_schema.ProjectId
import org.jooq.Record

data class ProjectMetricModel<ID : ProjectMetricId?>(
    val id: ID,
    val projectId: ProjectId,
    val name: String,
    val description: String?,
    val component: MetricComponent,
    val type: MetricType,
    val reference: String,
    val isPublishable: Boolean,
    val unit: String? = null,
) {
  companion object {
    fun of(record: Record): ExistingProjectMetricModel {
      return with(PROJECT_METRICS) {
        ExistingProjectMetricModel(
            id = record[ID]!!,
            projectId = record[PROJECT_ID]!!,
            name = record[NAME]!!,
            description = record[DESCRIPTION],
            component = record[COMPONENT_ID]!!,
            type = record[TYPE_ID]!!,
            reference = record[REFERENCE]!!,
            isPublishable = record[IS_PUBLISHABLE]!!,
            unit = record[UNIT],
        )
      }
    }
  }
}

typealias ExistingProjectMetricModel = ProjectMetricModel<ProjectMetricId>

typealias NewProjectMetricModel = ProjectMetricModel<Nothing?>
