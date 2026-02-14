package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.default_schema.ProjectId
import java.time.LocalDate

/** Deliverable due date for a cohort, and any project due dates overrides */
data class DeliverableDueDateModel(
    val cohortId: CohortId,
    val deliverableId: DeliverableId,
    val moduleId: ModuleId,
    val moduleDueDate: LocalDate,
    val projectDueDates: Map<ProjectId, LocalDate>,
)
