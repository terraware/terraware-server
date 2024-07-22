package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ApplicationModuleModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.tables.references.APPLICATION_MODULES
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class ApplicationModuleStore(
  private val dslContext: DSLContext,
) {
  fun fetch(applicationId: ApplicationId): List<ApplicationModuleModel> {
    requirePermissions { readApplication(applicationId) }

    return with(MODULES) {
      dslContext
          .select(asterisk())
          .from(this)
          .leftJoin(APPLICATION_MODULES)
          .on(APPLICATION_MODULES.MODULE_ID.eq(ID))
          .and(APPLICATION_MODULES.APPLICATION_ID.eq(applicationId))
          .where(PHASE_ID.eq(CohortPhase.PreScreen))
          .or(PHASE_ID.eq(CohortPhase.Application))
          .orderBy(MODULES.PHASE_ID)
          .fetch { ApplicationModuleModel.of(it) }
    }
  }
}
