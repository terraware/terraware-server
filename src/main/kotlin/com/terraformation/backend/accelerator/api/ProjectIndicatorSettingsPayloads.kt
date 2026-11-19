package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.model.ExistingCommonIndicatorModel
import com.terraformation.backend.accelerator.model.NewCommonIndicatorModel
import com.terraformation.backend.accelerator.model.ProjectAutoCalculatedIndicatorModel
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorFrequency
import com.terraformation.backend.db.accelerator.IndicatorLevel
import jakarta.validation.Valid
import jakarta.validation.constraints.Size

data class CommonIndicatorDefinitionPayload(
    val category: IndicatorCategory,
    val classId: IndicatorClass,
    val description: String?,
    val frequency: IndicatorFrequency?,
    val id: CommonIndicatorId,
    val level: IndicatorLevel,
    val name: String,
    val precision: Int,
    val tfOwner: String?,
    val unit: String?,
) {
  constructor(
      model: ExistingCommonIndicatorModel
  ) : this(
      category = model.category,
      classId = model.classId,
      description = model.description,
      frequency = model.frequency,
      id = model.id,
      level = model.level,
      name = model.name,
      precision = model.precision,
      tfOwner = model.tfOwner,
      unit = model.unit,
  )
}

data class NewCommonIndicatorDefinitionPayload(
    val category: IndicatorCategory,
    val classId: IndicatorClass,
    val description: String?,
    val frequency: IndicatorFrequency?,
    val level: IndicatorLevel,
    val name: String,
    val precision: Int,
    val tfOwner: String?,
    @field:Size(max = 25) val unit: String? = null,
) {
  fun toModel(): NewCommonIndicatorModel =
      NewCommonIndicatorModel(
          category = category,
          classId = classId,
          description = description,
          frequency = frequency,
          id = null,
          isPublishable = false,
          level = level,
          name = name,
          precision = precision,
          refId = null,
          tfOwner = tfOwner,
          unit = unit,
      )
}

data class ProjectAutoCalculatedIndicatorPayload(
    val active: Boolean,
    val indicator: AutoCalculatedIndicator,
    val isPublishable: Boolean,
    val notes: String?,
    val primaryDataSource: String?,
    val refId: String?,
    val category: IndicatorCategory = indicator.categoryId,
    val classId: IndicatorClass = indicator.classId,
    val description: String = indicator.description,
    val frequency: IndicatorFrequency? = indicator.frequencyId,
    val level: IndicatorLevel = indicator.levelId,
    val name: String = indicator.jsonValue,
    val precision: Int = indicator.precision,
    val tfOwner: String? = indicator.tfOwner,
    val unit: String? = indicator.unit,
) {
  constructor(
      model: ProjectAutoCalculatedIndicatorModel
  ) : this(
      active = model.active,
      indicator = model.indicator,
      isPublishable = model.isPublishable,
      notes = model.notes,
      primaryDataSource = model.primaryDataSource,
      refId = model.refId,
  )
}

data class ProjectAutoCalculatedIndicatorSettingsPayload(
    val active: Boolean,
    val isPublishable: Boolean,
    val notes: String?,
    val refId: String?,
)

data class ListCommonIndicatorDefinitionsResponsePayload(
    val indicators: List<CommonIndicatorDefinitionPayload>
) : SuccessResponsePayload

data class CreateCommonIndicatorDefinitionRequestPayload(
    @field:Valid val indicator: NewCommonIndicatorDefinitionPayload
)

data class CreateCommonIndicatorDefinitionResponsePayload(val indicatorId: CommonIndicatorId) :
    SuccessResponsePayload

data class ListProjectAutoCalculatedIndicatorsResponsePayload(
    val indicators: List<ProjectAutoCalculatedIndicatorPayload>
) : SuccessResponsePayload

data class UpdateProjectAutoCalculatedIndicatorRequestPayload(
    @field:Valid val indicator: ProjectAutoCalculatedIndicatorSettingsPayload
)
