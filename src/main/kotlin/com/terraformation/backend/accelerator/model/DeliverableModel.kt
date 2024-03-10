package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import org.jooq.Record

data class DeliverableModel<ID : DeliverableId?>(
    val deliverableCategory: DeliverableCategory,
    val deliverableType: DeliverableType,
    val descriptionHtml: String?,
    val id: ID,
    val isSensitive: Boolean,
    val isRequired: Boolean,
    val moduleId: ModuleId,
    val name: String,
    val position: Int,
) {
  companion object {
    fun of(
        record: Record,
    ): ExistingDeliverableModel {
      return ExistingDeliverableModel(
          deliverableCategory = record[DELIVERABLES.DELIVERABLE_CATEGORY_ID]!!,
          deliverableType = record[DELIVERABLES.DELIVERABLE_TYPE_ID]!!,
          descriptionHtml = record[DELIVERABLES.DESCRIPTION_HTML],
          id = record[DELIVERABLES.ID]!!,
          isSensitive = record[DELIVERABLES.IS_SENSITIVE]!!,
          isRequired = record[DELIVERABLES.IS_REQUIRED]!!,
          moduleId = record[DELIVERABLES.MODULE_ID]!!,
          name = record[DELIVERABLES.NAME]!!,
          position = record[DELIVERABLES.POSITION]!!,
      )
    }
  }
}

typealias ExistingDeliverableModel = DeliverableModel<DeliverableId>
