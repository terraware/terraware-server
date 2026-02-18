package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.DeliverableDueDateModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_PROJECT_DUE_DATES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_MODULES
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.inject.Named
import java.time.LocalDate
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class DeliverableDueDateStore(
    private val dslContext: DSLContext,
) {
  fun fetchDeliverableDueDates(
      projectId: ProjectId? = null,
      moduleId: ModuleId? = null,
      deliverableId: DeliverableId? = null,
  ): List<DeliverableDueDateModel> {
    requirePermissions { readAllDeliverables() }

    val conditions =
        listOfNotNull(
            projectId?.let { PROJECT_MODULES.PROJECT_ID.eq(projectId) },
            deliverableId?.let { DELIVERABLES.ID.eq(deliverableId) },
            moduleId?.let { DELIVERABLES.MODULE_ID.eq(moduleId) },
        )

    val projectDueDatesMultiset =
        DSL.multiset(
                DSL.select(
                        DELIVERABLE_PROJECT_DUE_DATES.PROJECT_ID,
                        DELIVERABLE_PROJECT_DUE_DATES.DUE_DATE,
                    )
                    .from(DELIVERABLE_PROJECT_DUE_DATES)
                    .where(DELIVERABLE_PROJECT_DUE_DATES.DELIVERABLE_ID.eq(DELIVERABLES.ID))
            )
            .convertFrom { result ->
              result.associate { record ->
                record[DELIVERABLE_PROJECT_DUE_DATES.PROJECT_ID]!! to
                    record[DELIVERABLE_PROJECT_DUE_DATES.DUE_DATE]!!
              }
            }

    return dslContext
        .select(
            PROJECT_MODULES.PROJECT_ID,
            PROJECT_MODULES.END_DATE,
            PROJECT_MODULES.MODULE_ID,
            DELIVERABLES.ID,
            DELIVERABLE_PROJECT_DUE_DATES.DUE_DATE,
            projectDueDatesMultiset,
        )
        .from(PROJECT_MODULES)
        .join(DELIVERABLES)
        .on(DELIVERABLES.MODULE_ID.eq(PROJECT_MODULES.MODULE_ID))
        .leftJoin(DELIVERABLE_PROJECT_DUE_DATES)
        .on(PROJECT_MODULES.PROJECT_ID.eq(DELIVERABLE_PROJECT_DUE_DATES.PROJECT_ID))
        .and(DELIVERABLES.ID.eq(DELIVERABLE_PROJECT_DUE_DATES.DELIVERABLE_ID))
        .where(conditions)
        .fetch { record ->
          DeliverableDueDateModel(
              deliverableId = record[DELIVERABLES.ID]!!,
              moduleId = record[PROJECT_MODULES.MODULE_ID]!!,
              moduleDueDate = record[PROJECT_MODULES.END_DATE]!!,
              projectId = record[PROJECT_MODULES.PROJECT_ID]!!,
              projectDueDate = record[DELIVERABLE_PROJECT_DUE_DATES.DUE_DATE],
          )
        }
  }

  fun upsertDeliverableProjectDueDate(
      deliverableId: DeliverableId,
      projectId: ProjectId,
      dueDate: LocalDate,
  ) {
    requirePermissions { manageDeliverables() }

    with(DELIVERABLE_PROJECT_DUE_DATES) {
      dslContext
          .insertInto(this)
          .set(DELIVERABLE_ID, deliverableId)
          .set(PROJECT_ID, projectId)
          .set(DUE_DATE, dueDate)
          .onDuplicateKeyUpdate()
          .set(DUE_DATE, dueDate)
          .execute()
    }
  }

  fun deleteDeliverableProjectDueDate(
      deliverableId: DeliverableId,
      projectId: ProjectId,
  ) {
    requirePermissions { manageDeliverables() }

    with(DELIVERABLE_PROJECT_DUE_DATES) {
      dslContext
          .deleteFrom(this)
          .where(DELIVERABLE_ID.eq(deliverableId))
          .and(PROJECT_ID.eq(projectId))
          .execute()
    }
  }
}
