package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_DELIVERABLES
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class ProjectDeliverablesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECT_DELIVERABLES.PROJECT_DELIVERABLE_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          deliverables.asSingleValueSublist(
              "deliverable",
              DELIVERABLES.ID.eq(PROJECT_DELIVERABLES.DELIVERABLE_ID),
          ),
          modules.asSingleValueSublist("module", MODULES.ID.eq(PROJECT_DELIVERABLES.MODULE_ID)),
          projects.asSingleValueSublist("project", PROJECTS.ID.eq(PROJECT_DELIVERABLES.PROJECT_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", PROJECT_DELIVERABLES.DELIVERABLE_ID) { DeliverableId(it) },
          enumField("category", PROJECT_DELIVERABLES.DELIVERABLE_CATEGORY_ID),
          textField("description", PROJECT_DELIVERABLES.DESCRIPTION_HTML),
          dateField("dueDate", PROJECT_DELIVERABLES.DUE_DATE),
          textField("feedback", PROJECT_DELIVERABLES.SUBMISSION_FEEDBACK),
          textField("name", PROJECT_DELIVERABLES.NAME),
          integerField("position", PROJECT_DELIVERABLES.POSITION),
          booleanField("required", PROJECT_DELIVERABLES.IS_REQUIRED),
          booleanField("sensitive", PROJECT_DELIVERABLES.IS_SENSITIVE),
          enumField("status", PROJECT_DELIVERABLES.SUBMISSION_STATUS_ID),
          idWrapperField("submissionId", PROJECT_DELIVERABLES.SUBMISSION_ID) { SubmissionId(it) },
          enumField("type", PROJECT_DELIVERABLES.DELIVERABLE_TYPE_ID),
      )

  override val defaultOrderFields =
      listOf(PROJECT_DELIVERABLES.DUE_DATE, PROJECT_DELIVERABLES.POSITION)

  override fun conditionForVisibility(): Condition {
    return if (currentUser().canReadAllAcceleratorDetails()) {
      DSL.trueCondition()
    } else {
      DSL.exists(
          DSL.selectOne()
              .from(PROJECTS)
              .where(PROJECT_DELIVERABLES.PROJECT_ID.eq(PROJECTS.ID))
              .and(PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys))
      )
    }
  }
}
