package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.records.DeliverablesRecord
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES

/** Deliverable data class without submission information. */
data class ModuleDeliverableModel(
    val id: DeliverableId,
    val category: DeliverableCategory,
    val descriptionHtml: String?,
    val moduleId: ModuleId,
    val name: String,
    val position: Int,
    val required: Boolean,
    val sensitive: Boolean,
    val type: DeliverableType,
) {
  companion object {
    fun of(record: DeliverablesRecord): ModuleDeliverableModel =
        with(DELIVERABLES) {
          ModuleDeliverableModel(
              id = record[ID]!!,
              category = record[DELIVERABLE_CATEGORY_ID]!!,
              descriptionHtml = record[DESCRIPTION_HTML],
              moduleId = record[MODULE_ID]!!,
              name = record[NAME]!!,
              position = record[POSITION]!!,
              required = record[IS_REQUIRED]!!,
              sensitive = record[IS_SENSITIVE]!!,
              type = record[DELIVERABLE_TYPE_ID]!!,
          )
        }
  }
}
