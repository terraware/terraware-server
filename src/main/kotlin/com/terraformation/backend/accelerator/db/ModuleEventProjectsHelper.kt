package com.terraformation.backend.accelerator.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.references.EVENTS
import com.terraformation.backend.db.accelerator.tables.references.EVENT_PROJECTS
import com.terraformation.backend.db.default_schema.ProjectId
import org.jooq.Field
import org.jooq.impl.DSL

class ModuleEventProjectsHelper {
  fun eventProjectsMultiset(): Field<Set<ProjectId>> {
    requirePermissions { readModuleEventParticipants() }
    return with(EVENT_PROJECTS) {
      DSL.multiset(DSL.select(PROJECT_ID).from(this).where(EVENT_ID.eq(EVENTS.ID))).convertFrom {
          result ->
        result.map { it.value1() }.toSet()
      }
    }
  }
}
