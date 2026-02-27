package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorFrequency
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_INDICATORS
import com.terraformation.backend.db.default_schema.ProjectId
import org.jooq.Record

data class ProjectIndicatorModel<ID : ProjectIndicatorId?>(
    val id: ID,
    val active: Boolean = true,
    val category: IndicatorCategory,
    val classId: IndicatorClass? = null,
    val description: String?,
    val frequency: IndicatorFrequency? = null,
    val isPublishable: Boolean,
    val level: IndicatorLevel,
    val name: String,
    val notes: String? = null,
    val primaryDataSource: String? = null,
    val precision: Int,
    val projectId: ProjectId,
    val refId: String,
    val tfOwner: String? = null,
    val unit: String? = null,
) {
  companion object {
    fun of(record: Record): ExistingProjectIndicatorModel {
      return with(PROJECT_INDICATORS) {
        ExistingProjectIndicatorModel(
            id = record[ID]!!,
            active = record[ACTIVE]!!,
            category = record[CATEGORY_ID]!!,
            classId = record[CLASS_ID],
            description = record[DESCRIPTION],
            frequency = record[FREQUENCY_ID],
            isPublishable = record[IS_PUBLISHABLE]!!,
            level = record[LEVEL_ID]!!,
            name = record[NAME]!!,
            notes = record[NOTES],
            primaryDataSource = record[PRIMARY_DATA_SOURCE],
            precision = record[PRECISION]!!,
            projectId = record[PROJECT_ID]!!,
            refId = record[REF_ID]!!,
            tfOwner = record[TF_OWNER],
            unit = record[UNIT],
        )
      }
    }
  }
}

typealias ExistingProjectIndicatorModel = ProjectIndicatorModel<ProjectIndicatorId>

typealias NewProjectIndicatorModel = ProjectIndicatorModel<Nothing?>
