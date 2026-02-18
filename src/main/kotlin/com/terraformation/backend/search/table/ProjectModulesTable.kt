package com.terraformation.backend.search.table

import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_MODULES
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ProjectModulesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECT_MODULES.PROJECT_MODULE_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projects.asSingleValueSublist("project", PROJECT_MODULES.PROJECT_ID.eq(PROJECTS.ID)),
          modules.asSingleValueSublist("module", PROJECT_MODULES.MODULE_ID.eq(MODULES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("title", PROJECT_MODULES.TITLE),
          dateField("startDate", PROJECT_MODULES.START_DATE),
          dateField("endDate", PROJECT_MODULES.END_DATE),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.projects

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> =
      query.join(PROJECTS).on(PROJECT_MODULES.PROJECT_ID.eq(PROJECTS.ID))
}
