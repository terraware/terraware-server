package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.model.ExistingProjectIndicatorModel
import com.terraformation.backend.accelerator.model.ExistingStandardMetricModel
import com.terraformation.backend.accelerator.model.NewProjectIndicatorModel
import com.terraformation.backend.accelerator.model.NewStandardMetricModel
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.validation.constraints.Size

data class ExistingProjectMetricPayload(
    val id: ProjectIndicatorId,
    val projectId: ProjectId,
    val name: String,
    val description: String?,
    val component: IndicatorCategory,
    val type: IndicatorLevel,
    val reference: String,
    val isPublishable: Boolean,
    @field:Size(max = 25) val unit: String?,
) {
  constructor(
      model: ExistingProjectIndicatorModel
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

  fun toModel(): ExistingProjectIndicatorModel {
    return ExistingProjectIndicatorModel(
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
    val id: CommonIndicatorId,
    val name: String,
    val description: String?,
    val component: IndicatorCategory,
    val type: IndicatorLevel,
    val reference: String,
    val isPublishable: Boolean,
    @field:Size(max = 25) val unit: String? = null,
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
      unit = model.unit,
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
        unit = unit,
    )
  }
}

data class NewMetricPayload(
    val name: String,
    val description: String?,
    val component: IndicatorCategory,
    val type: IndicatorLevel,
    val reference: String,
    val isPublishable: Boolean,
    @field:Size(max = 25) val unit: String? = null,
) {
  fun toProjectIndicatorModel(projectId: ProjectId): NewProjectIndicatorModel {
    return NewProjectIndicatorModel(
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
