package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_INDICATORS
import com.terraformation.backend.db.default_schema.ProjectId
import org.jooq.Record

data class ProjectIndicatorModel<ID : ProjectIndicatorId?>(
    val id: ID,
    val projectId: ProjectId,
    val name: String,
    val description: String?,
    val category: IndicatorCategory,
    val level: IndicatorLevel,
    val reference: String,
    val isPublishable: Boolean,
    val unit: String? = null,
) {
  companion object {
    fun of(record: Record): ExistingProjectIndicatorModel {
      return with(PROJECT_INDICATORS) {
        ExistingProjectIndicatorModel(
            id = record[ID]!!,
            projectId = record[PROJECT_ID]!!,
            name = record[NAME]!!,
            description = record[DESCRIPTION],
            category = record[CATEGORY_ID]!!,
            level = record[LEVEL_ID]!!,
            reference = record[REF_ID]!!,
            isPublishable = record[IS_PUBLISHABLE]!!,
            unit = record[UNIT],
        )
      }
    }
  }
}

typealias ExistingProjectIndicatorModel = ProjectIndicatorModel<ProjectIndicatorId>

typealias NewProjectIndicatorModel = ProjectIndicatorModel<Nothing?>
