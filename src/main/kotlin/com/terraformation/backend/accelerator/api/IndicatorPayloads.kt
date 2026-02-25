package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.model.ExistingCommonIndicatorModel
import com.terraformation.backend.accelerator.model.ExistingProjectIndicatorModel
import com.terraformation.backend.accelerator.model.NewCommonIndicatorModel
import com.terraformation.backend.accelerator.model.NewProjectIndicatorModel
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.default_schema.ProjectId
import io.swagger.v3.oas.annotations.media.Schema
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
      component = model.category,
      type = model.level,
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
        category = component,
        level = type,
        reference = reference,
        isPublishable = isPublishable,
        unit = unit,
    )
  }
}

@Schema(description = "Use ExistingCommonIndicatorPayload instead", deprecated = true)
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
      model: ExistingCommonIndicatorModel
  ) : this(
      id = model.id,
      name = model.name,
      description = model.description,
      component = model.category,
      type = model.level,
      reference = model.reference,
      isPublishable = model.isPublishable,
      unit = model.unit,
  )

  fun toModel(): ExistingCommonIndicatorModel {
    return ExistingCommonIndicatorModel(
        id = id,
        name = name,
        description = description,
        category = component,
        level = type,
        reference = reference,
        isPublishable = isPublishable,
        unit = unit,
    )
  }
}

data class ExistingCommonIndicatorPayload(
    val id: CommonIndicatorId,
    val name: String,
    val description: String?,
    val category: IndicatorCategory,
    val level: IndicatorLevel,
    val reference: String,
    val isPublishable: Boolean,
    @field:Size(max = 25) val unit: String? = null,
) {
  constructor(
      model: ExistingCommonIndicatorModel
  ) : this(
      id = model.id,
      name = model.name,
      description = model.description,
      category = model.category,
      level = model.level,
      reference = model.reference,
      isPublishable = model.isPublishable,
      unit = model.unit,
  )

  fun toModel(): ExistingCommonIndicatorModel {
    return ExistingCommonIndicatorModel(
        id = id,
        name = name,
        description = description,
        category = category,
        level = level,
        reference = reference,
        isPublishable = isPublishable,
        unit = unit,
    )
  }
}

@Schema(description = "Use NewIndicatorPayload instead", deprecated = true)
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
        category = component,
        level = type,
        reference = reference,
        isPublishable = isPublishable,
        unit = unit,
    )
  }

  fun toCommonIndicatorModel(): NewCommonIndicatorModel {
    return NewCommonIndicatorModel(
        id = null,
        name = name,
        description = description,
        category = component,
        level = type,
        reference = reference,
        isPublishable = isPublishable,
        unit = unit,
    )
  }
}

data class NewIndicatorPayload(
    val name: String,
    val description: String?,
    val category: IndicatorCategory,
    val level: IndicatorLevel,
    val reference: String,
    val isPublishable: Boolean,
    @field:Size(max = 25) val unit: String? = null,
) {
  fun toCommonIndicatorModel(): NewCommonIndicatorModel {
    return NewCommonIndicatorModel(
        id = null,
        name = name,
        description = description,
        category = category,
        level = level,
        reference = reference,
        isPublishable = isPublishable,
        unit = unit,
    )
  }
}
