package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ModuleDeliverableModel
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import org.jooq.Field
import org.jooq.impl.DSL

fun deliverablesMultiset(): Field<List<ModuleDeliverableModel>> {
  return with(DELIVERABLES) {
    DSL.multiset(DSL.selectFrom(this).where(MODULE_ID.eq(MODULES.ID)).orderBy(POSITION))
        .convertFrom { result -> result.map { ModuleDeliverableModel.of(it) } }
  }
}
