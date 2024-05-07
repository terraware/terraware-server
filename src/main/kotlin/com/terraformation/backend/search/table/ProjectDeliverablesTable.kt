package com.terraformation.backend.search.table

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_DELIVERABLE_SUMMARIES
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ProjectDeliverablesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECT_DELIVERABLE_SUMMARIES.PROJECT_DELIVERABLE_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projects.asSingleValueSublist(
              "project", PROJECTS.ID.eq(PROJECT_DELIVERABLE_SUMMARIES.PROJECT_ID)),
          modules.asSingleValueSublist(
              "module", MODULES.ID.eq(PROJECT_DELIVERABLE_SUMMARIES.MODULE_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          enumField(
              "category", PROJECT_DELIVERABLE_SUMMARIES.DELIVERABLE_CATEGORY_ID, nullable = false),
          idWrapperField("id", PROJECT_DELIVERABLE_SUMMARIES.DELIVERABLE_ID) { DeliverableId(it) },
          textField("description", PROJECT_DELIVERABLE_SUMMARIES.DESCRIPTION_HTML),
          dateField("dueDate", PROJECT_DELIVERABLE_SUMMARIES.DUE_DATE, nullable = false),
          textField("feedback", PROJECT_DELIVERABLE_SUMMARIES.SUBMISSION_FEEDBACK),
          textField("name", PROJECT_DELIVERABLE_SUMMARIES.NAME, nullable = false),
          booleanField("required", PROJECT_DELIVERABLE_SUMMARIES.IS_REQUIRED, nullable = false),
          booleanField("sensitive", PROJECT_DELIVERABLE_SUMMARIES.IS_SENSITIVE, nullable = false),
          enumField("status", PROJECT_DELIVERABLE_SUMMARIES.SUBMISSION_STATUS_ID),
          enumField("type", PROJECT_DELIVERABLE_SUMMARIES.DELIVERABLE_TYPE_ID, nullable = false),
      )

  override val defaultOrderFields = listOf(PROJECT_DELIVERABLE_SUMMARIES.DUE_DATE)

  override val inheritsVisibilityFrom: SearchTable = tables.projects

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> =
      query.join(PROJECTS).on(PROJECTS.ID.eq(PROJECT_DELIVERABLE_SUMMARIES.PROJECT_ID))
}
