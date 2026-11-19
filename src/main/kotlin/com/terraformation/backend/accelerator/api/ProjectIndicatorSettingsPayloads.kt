package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.model.ExistingCommonIndicatorModel
import com.terraformation.backend.accelerator.model.NewCommonIndicatorModel
import com.terraformation.backend.api.SuccessResponsePayload
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

data class ListCommonIndicatorDefinitionsResponsePayload(
    val indicators: List<CommonIndicatorDefinitionPayload>
) : SuccessResponsePayload

data class CreateCommonIndicatorDefinitionRequestPayload(
    @field:Valid val indicator: NewCommonIndicatorDefinitionPayload
)

data class CreateCommonIndicatorDefinitionResponsePayload(val indicatorId: CommonIndicatorId) :
    SuccessResponsePayload
