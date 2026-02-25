package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_INDICATORS
import com.terraformation.backend.db.default_schema.ProjectId
import org.jooq.Record

data class ProjectIndicatorModel<ID : ProjectIndicatorId?>(
    val id: ID,
    val category: IndicatorCategory,
    val description: String?,
    val isPublishable: Boolean,
    val level: IndicatorLevel,
    val name: String,
    val projectId: ProjectId,
    val refId: String,
    val unit: String? = null,
) {
  companion object {
    fun of(record: Record): ExistingProjectIndicatorModel {
      return with(PROJECT_INDICATORS) {
        ExistingProjectIndicatorModel(
            id = record[ID]!!,
            category = record[CATEGORY_ID]!!,
            description = record[DESCRIPTION],
            isPublishable = record[IS_PUBLISHABLE]!!,
            level = record[LEVEL_ID]!!,
            name = record[NAME]!!,
            refId = record[REF_ID]!!,
            projectId = record[PROJECT_ID]!!,
            unit = record[UNIT],
        )
      }
    }
  }
}

typealias ExistingProjectIndicatorModel = ProjectIndicatorModel<ProjectIndicatorId>

typealias NewProjectIndicatorModel = ProjectIndicatorModel<Nothing?>
