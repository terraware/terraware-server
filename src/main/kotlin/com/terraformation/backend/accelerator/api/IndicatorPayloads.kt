package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.model.ExistingCommonIndicatorModel
import com.terraformation.backend.accelerator.model.ExistingProjectIndicatorModel
import com.terraformation.backend.accelerator.model.NewCommonIndicatorModel
import com.terraformation.backend.accelerator.model.NewProjectIndicatorModel
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorFrequency
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
      reference = model.refId,
      isPublishable = model.isPublishable,
      unit = model.unit,
  )

  fun toModel(): ExistingProjectIndicatorModel {
    return ExistingProjectIndicatorModel(
        id = id,
        classId = IndicatorClass.Level,
        projectId = projectId,
        name = name,
        description = description,
        category = component,
        level = type,
        precision = 0,
        refId = reference,
        isPublishable = isPublishable,
        unit = unit,
    )
  }
}

data class ExistingProjectIndicatorPayload(
    val active: Boolean,
    val category: IndicatorCategory,
    val classId: IndicatorClass,
    val description: String?,
    val frequency: IndicatorFrequency?,
    val id: ProjectIndicatorId,
    val isPublishable: Boolean,
    val level: IndicatorLevel,
    val name: String,
    val notes: String?,
    val precision: Int,
    val primaryDataSource: String?,
    val projectId: ProjectId,
    val refId: String,
    val tfOwner: String?,
    @field:Size(max = 25) val unit: String? = null,
) {
  constructor(
      model: ExistingProjectIndicatorModel
  ) : this(
      active = model.active,
      category = model.category,
      classId = model.classId,
      description = model.description,
      frequency = model.frequency,
      id = model.id,
      isPublishable = model.isPublishable,
      level = model.level,
      name = model.name,
      notes = model.notes,
      precision = model.precision,
      primaryDataSource = model.primaryDataSource,
      projectId = model.projectId,
      refId = model.refId,
      tfOwner = model.tfOwner,
      unit = model.unit,
  )

  fun toModel(): ExistingProjectIndicatorModel {
    return ExistingProjectIndicatorModel(
        active = active,
        category = category,
        classId = classId,
        description = description,
        frequency = frequency,
        id = id,
        isPublishable = isPublishable,
        level = level,
        name = name,
        notes = notes,
        precision = precision,
        primaryDataSource = primaryDataSource,
        projectId = projectId,
        refId = refId,
        tfOwner = tfOwner,
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
      reference = model.refId,
      isPublishable = model.isPublishable,
      unit = model.unit,
  )

  fun toModel(): ExistingCommonIndicatorModel {
    return ExistingCommonIndicatorModel(
        id = id,
        classId = IndicatorClass.Level,
        name = name,
        description = description,
        category = component,
        level = type,
        precision = 0,
        refId = reference,
        isPublishable = isPublishable,
        unit = unit,
    )
  }
}

data class ExistingCommonIndicatorPayload(
    val active: Boolean,
    val category: IndicatorCategory,
    val classId: IndicatorClass,
    val description: String?,
    val frequency: IndicatorFrequency?,
    val id: CommonIndicatorId,
    val isPublishable: Boolean,
    val level: IndicatorLevel,
    val name: String,
    val notes: String?,
    val precision: Int,
    val primaryDataSource: String?,
    val refId: String,
    val tfOwner: String?,
    @field:Size(max = 25) val unit: String? = null,
) {
  constructor(
      model: ExistingCommonIndicatorModel
  ) : this(
      active = model.active,
      category = model.category,
      classId = model.classId,
      description = model.description,
      frequency = model.frequency,
      id = model.id,
      isPublishable = model.isPublishable,
      level = model.level,
      name = model.name,
      notes = model.notes,
      precision = model.precision,
      primaryDataSource = model.primaryDataSource,
      refId = model.refId,
      tfOwner = model.tfOwner,
      unit = model.unit,
  )

  fun toModel(): ExistingCommonIndicatorModel {
    return ExistingCommonIndicatorModel(
        active = active,
        category = category,
        classId = classId,
        description = description,
        frequency = frequency,
        id = id,
        isPublishable = isPublishable,
        level = level,
        name = name,
        notes = notes,
        precision = precision,
        primaryDataSource = primaryDataSource,
        refId = refId,
        tfOwner = tfOwner,
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
        classId = IndicatorClass.Level,
        projectId = projectId,
        name = name,
        description = description,
        category = component,
        level = type,
        precision = 0,
        refId = reference,
        isPublishable = isPublishable,
        unit = unit,
    )
  }

  fun toCommonIndicatorModel(): NewCommonIndicatorModel {
    return NewCommonIndicatorModel(
        id = null,
        classId = IndicatorClass.Level,
        name = name,
        description = description,
        category = component,
        level = type,
        precision = 0,
        refId = reference,
        isPublishable = isPublishable,
        unit = unit,
    )
  }
}

data class NewIndicatorPayload(
    val active: Boolean,
    val category: IndicatorCategory,
    val classId: IndicatorClass,
    val description: String?,
    val frequency: IndicatorFrequency?,
    val isPublishable: Boolean,
    val level: IndicatorLevel,
    val name: String,
    val notes: String?,
    val precision: Int,
    val primaryDataSource: String?,
    val refId: String,
    val tfOwner: String?,
    @field:Size(max = 25) val unit: String? = null,
) {
  fun toProjectIndicatorModel(projectId: ProjectId): NewProjectIndicatorModel {
    return NewProjectIndicatorModel(
        active = active,
        category = category,
        classId = classId,
        description = description,
        frequency = frequency,
        id = null,
        isPublishable = isPublishable,
        level = level,
        name = name,
        notes = notes,
        precision = precision,
        primaryDataSource = primaryDataSource,
        projectId = projectId,
        refId = refId,
        tfOwner = tfOwner,
        unit = unit,
    )
  }

  fun toCommonIndicatorModel(): NewCommonIndicatorModel {
    return NewCommonIndicatorModel(
        active = active,
        category = category,
        classId = classId,
        description = description,
        frequency = frequency,
        id = null,
        isPublishable = isPublishable,
        level = level,
        name = name,
        notes = notes,
        precision = precision,
        primaryDataSource = primaryDataSource,
        refId = refId,
        tfOwner = tfOwner,
        unit = unit,
    )
  }
}
