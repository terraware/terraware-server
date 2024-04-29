package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.default_schema.ProjectId
import java.time.LocalDate
import org.jooq.Field
import org.jooq.Record

data class CohortModuleModel(
    val cohortId: CohortId,
    val moduleId: ModuleId,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val projects: Set<ProjectId>? = null,
) {
  companion object {
    fun of(record: Record, projectsField: Field<Set<ProjectId>>? = null): CohortModuleModel {
      return with(COHORT_MODULES) {
        CohortModuleModel(
            cohortId = record[COHORT_ID]!!,
            moduleId = record[MODULE_ID]!!,
            startDate = record[START_DATE]!!,
            endDate = record[END_DATE]!!,
            projects = projectsField?.let { record[it] },
        )
      }
    }
  }
}
