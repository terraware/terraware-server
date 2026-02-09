package com.terraformation.backend.search.table

import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.TableField

class ApplicationsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = APPLICATIONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(projects.asSingleValueSublist("project", APPLICATIONS.PROJECT_ID.eq(PROJECTS.ID)))
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", APPLICATIONS.ID) { ApplicationId(it) },
          enumField("status", APPLICATIONS.APPLICATION_STATUS_ID),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.projects
}
