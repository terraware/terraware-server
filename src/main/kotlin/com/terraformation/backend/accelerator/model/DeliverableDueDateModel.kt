package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.default_schema.ProjectId
import java.time.LocalDate

/** Deliverable due date for a project */
data class DeliverableDueDateModel(
    val deliverableId: DeliverableId,
    val moduleId: ModuleId,
    val moduleDueDate: LocalDate,
    val projectDueDate: LocalDate?,
    val projectId: ProjectId,
)
