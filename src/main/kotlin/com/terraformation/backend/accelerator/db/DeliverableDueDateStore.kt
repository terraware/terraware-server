package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.DeliverableDueDateModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_COHORT_DUE_DATES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_PROJECT_DUE_DATES
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
      cohortId: CohortId? = null,
      moduleId: ModuleId? = null,
      deliverableId: DeliverableId? = null,
  ): List<DeliverableDueDateModel> {
    requirePermissions { readAllDeliverables() }

    val conditions =
        listOfNotNull(
            cohortId?.let { COHORT_MODULES.COHORT_ID.eq(cohortId) },
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
            COHORT_MODULES.COHORT_ID,
            COHORT_MODULES.END_DATE,
            COHORT_MODULES.MODULE_ID,
            DELIVERABLES.ID,
            DELIVERABLE_COHORT_DUE_DATES.DUE_DATE,
            projectDueDatesMultiset,
        )
        .from(COHORT_MODULES)
        .join(DELIVERABLES)
        .on(DELIVERABLES.MODULE_ID.eq(COHORT_MODULES.MODULE_ID))
        .leftJoin(DELIVERABLE_COHORT_DUE_DATES)
        .on(DELIVERABLE_COHORT_DUE_DATES.DELIVERABLE_ID.eq(DELIVERABLES.ID))
        .where(conditions)
        .fetch { record ->
          DeliverableDueDateModel(
              cohortId = record[COHORT_MODULES.COHORT_ID]!!,
              deliverableId = record[DELIVERABLES.ID]!!,
              moduleId = record[COHORT_MODULES.MODULE_ID]!!,
              cohortDueDate = record[DELIVERABLE_COHORT_DUE_DATES.DUE_DATE],
              moduleDueDate = record[COHORT_MODULES.END_DATE]!!,
              projectDueDates = record[projectDueDatesMultiset],
          )
        }
  }

  fun upsertDeliverableCohortDueDate(
      deliverableId: DeliverableId,
      cohortId: CohortId,
      dueDate: LocalDate,
  ) {
    requirePermissions { manageDeliverables() }

    with(DELIVERABLE_COHORT_DUE_DATES) {
      dslContext
          .insertInto(this)
          .set(DELIVERABLE_ID, deliverableId)
          .set(COHORT_ID, cohortId)
          .set(DUE_DATE, dueDate)
          .onDuplicateKeyUpdate()
          .set(DUE_DATE, dueDate)
          .execute()
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

  fun deleteDeliverableCohortDueDate(
      deliverableId: DeliverableId,
      cohortId: CohortId,
  ) {
    requirePermissions { manageDeliverables() }

    with(DELIVERABLE_COHORT_DUE_DATES) {
      dslContext
          .deleteFrom(this)
          .where(DELIVERABLE_ID.eq(deliverableId))
          .and(COHORT_ID.eq(cohortId))
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
