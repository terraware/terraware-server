package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationModuleStatus
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.APPLICATION_MODULES
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import org.jooq.Record

data class ApplicationModuleModel(
  val id: ModuleId,
  val name: String,
  val phase: CohortPhase,
  val overview: String? = null,
  val applicationId: ApplicationId?,
  val applicationModuleStatus: ApplicationModuleStatus?,
) {
  companion object {
    fun of(record: Record): ApplicationModuleModel {
      return ApplicationModuleModel(
          id = record[MODULES.ID]!!,
          name = record[MODULES.NAME]!!,
          phase = record[MODULES.PHASE_ID]!!,
          overview = record[MODULES.OVERVIEW],
          applicationId = record[APPLICATION_MODULES.APPLICATION_ID],
          applicationModuleStatus = record[APPLICATION_MODULES.APPLICATION_MODULE_STATUS_ID],
      )
    }
  }
}
