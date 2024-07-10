package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.accelerator.tables.references.APPLICATION_HISTORIES
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.field.withRestriction
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ApplicationsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = APPLICATIONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          applicationHistories.asMultiValueSublist(
              "history", APPLICATIONS.ID.eq(APPLICATION_HISTORIES.APPLICATION_ID)),
          projects.asSingleValueSublist("project", APPLICATIONS.PROJECT_ID.eq(PROJECTS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", APPLICATIONS.BOUNDARY),
          idWrapperField("createdBy", APPLICATIONS.CREATED_BY) { UserId(it) }.internalOnly(),
          timestampField("createdTime", APPLICATIONS.CREATED_TIME),
          textField("feedback", APPLICATIONS.FEEDBACK),
          idWrapperField("id", APPLICATIONS.ID) { ApplicationId(it) },
          textField("internalComment", APPLICATIONS.INTERNAL_COMMENT).internalOnly(),
          textField("internalName", APPLICATIONS.INTERNAL_NAME).internalOnly(),
          idWrapperField("modifiedBy", APPLICATIONS.MODIFIED_BY) { UserId(it) }.internalOnly(),
          timestampField("modifiedTime", APPLICATIONS.MODIFIED_TIME),
          enumField("status", APPLICATIONS.STATUS_ID),
      )

  override val inheritsVisibilityFrom: SearchTable = tables.projects

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(PROJECTS).on(APPLICATIONS.PROJECT_ID.eq(PROJECTS.ID))
  }

  private fun SearchField.internalOnly() = withRestriction {
    currentUser().canReadAllAcceleratorDetails()
  }
}
