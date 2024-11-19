package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class ModuleStore(
    private val dslContext: DSLContext,
) {
  fun fetchOneById(moduleId: ModuleId): ModuleModel {
    return fetch(MODULES.ID.eq(moduleId)).firstOrNull() ?: throw ModuleNotFoundException(moduleId)
  }

  fun fetchAllModules(): List<ModuleModel> {
    return fetch()
  }

  fun fetchCohortPhase(moduleId: ModuleId): CohortPhase {
    requirePermissions { readModule(moduleId) }

    return dslContext
        .select(MODULES.PHASE_ID)
        .from(MODULES)
        .where(MODULES.ID.eq(moduleId))
        .fetchOne(MODULES.PHASE_ID) ?: throw ModuleNotFoundException(moduleId)
  }

  private fun fetch(condition: Condition? = null): List<ModuleModel> {
    return with(MODULES) {
      dslContext
          .select(asterisk())
          .from(this)
          .apply { condition?.let { where(it) } }
          .orderBy(ID)
          .fetch { ModuleModel.of(it) }
          .filter { currentUser().canReadModule(it.id) }
    }
  }
}
