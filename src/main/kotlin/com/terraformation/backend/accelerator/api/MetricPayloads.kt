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

data class ExistingProjectMetricPayload(
    val id: ProjectMetricId,
    val projectId: ProjectId,
    val name: String,
    val description: String?,
    val component: MetricComponent,
    val type: MetricType,
    val reference: Int,
    val subReference: Int?,
    val subSubReference: Int?,
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
      subReference = model.subReference,
      subSubReference = model.subSubReference,
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
    )
  }
}

data class ExistingStandardMetricPayload(
    val id: StandardMetricId,
    val name: String,
    val description: String?,
    val component: MetricComponent,
    val type: MetricType,
    val reference: Int,
    val subReference: Int?,
    val subSubReference: Int?,
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
      subReference = model.subReference,
      subSubReference = model.subSubReference,
  )

  fun toModel(): ExistingStandardMetricModel {
    return ExistingStandardMetricModel(
        id = id,
        name = name,
        description = description,
        component = component,
        type = type,
        reference = reference,
        subReference = subReference,
        subSubReference = subSubReference,
    )
  }
}

data class NewMetricPayload(
    val name: String,
    val description: String?,
    val component: MetricComponent,
    val type: MetricType,
    val reference: Int,
    val subReference: Int?,
    val subSubReference: Int?,
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
        subReference = subReference,
        subSubReference = subSubReference,
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
        subReference = subReference,
        subSubReference = subSubReference,
    )
  }
}
