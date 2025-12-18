package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.model.ExistingProjectMetricModel
import com.terraformation.backend.accelerator.model.ExistingStandardMetricModel
import com.terraformation.backend.accelerator.model.NewProjectMetricModel
import com.terraformation.backend.accelerator.model.NewStandardMetricModel
import com.terraformation.backend.db.accelerator.MetricComponent
import com.terraformation.backend.db.accelerator.MetricType
import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.validation.constraints.Size

data class ExistingProjectMetricPayload(
    val id: ProjectMetricId,
    val projectId: ProjectId,
    val name: String,
    val description: String?,
    val component: MetricComponent,
    val type: MetricType,
    val reference: String,
    val isPublishable: Boolean,
    @field:Size(max = 25) val unit: String?,
) {
  constructor(
      model: ExistingProjectMetricModel
  ) : this(
      id = model.id,
      projectId = model.projectId,
      name = model.name,
      description = model.description,
      component = model.component,
      type = model.type,
      reference = model.reference,
      isPublishable = model.isPublishable,
      unit = model.unit,
  )

  fun toModel(): ExistingProjectMetricModel {
    return ExistingProjectMetricModel(
        id = id,
        projectId = projectId,
        name = name,
        description = description,
        component = component,
        type = type,
        reference = reference,
        isPublishable = isPublishable,
        unit = unit,
    )
  }
}

data class ExistingStandardMetricPayload(
    val id: StandardMetricId,
    val name: String,
    val description: String?,
    val component: MetricComponent,
    val type: MetricType,
    val reference: String,
    val isPublishable: Boolean,
) {
  constructor(
      model: ExistingStandardMetricModel
  ) : this(
      id = model.id,
      name = model.name,
      description = model.description,
      component = model.component,
      type = model.type,
      reference = model.reference,
      isPublishable = model.isPublishable,
  )

  fun toModel(): ExistingStandardMetricModel {
    return ExistingStandardMetricModel(
        id = id,
        name = name,
        description = description,
        component = component,
        type = type,
        reference = reference,
        isPublishable = isPublishable,
    )
  }
}

data class NewMetricPayload(
    val name: String,
    val description: String?,
    val component: MetricComponent,
    val type: MetricType,
    val reference: String,
    val isPublishable: Boolean,
    @field:Size(max = 25) val unit: String? = null,
) {
  fun toProjectMetricModel(projectId: ProjectId): NewProjectMetricModel {
    return NewProjectMetricModel(
        id = null,
        projectId = projectId,
        name = name,
        description = description,
        component = component,
        type = type,
        reference = reference,
        isPublishable = isPublishable,
        unit = unit,
    )
  }

  fun toStandardMetricModel(): NewStandardMetricModel {
    return NewStandardMetricModel(
        id = null,
        name = name,
        description = description,
        component = component,
        type = type,
        reference = reference,
        isPublishable = isPublishable,
        unit = unit,
    )
  }
}
