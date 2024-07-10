package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ApplicationHistoryId
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.accelerator.tables.references.APPLICATION_HISTORIES
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.field.withRestriction
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ApplicationHistoriesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = APPLICATION_HISTORIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          applications.asSingleValueSublist(
              "application", APPLICATION_HISTORIES.APPLICATION_ID.eq(APPLICATIONS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", APPLICATION_HISTORIES.BOUNDARY),
          textField("feedback", APPLICATION_HISTORIES.FEEDBACK),
          idWrapperField("id", APPLICATION_HISTORIES.ID) { ApplicationHistoryId(it) },
          textField("internalComment", APPLICATION_HISTORIES.INTERNAL_COMMENT).internalOnly(),
          idWrapperField("modifiedBy", APPLICATION_HISTORIES.MODIFIED_BY) { UserId(it) }
              .internalOnly(),
          timestampField("modifiedTime", APPLICATION_HISTORIES.MODIFIED_TIME),
          enumField("status", APPLICATION_HISTORIES.STATUS_ID),
      )

  override val inheritsVisibilityFrom: SearchTable = tables.applications

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(APPLICATIONS).on(APPLICATION_HISTORIES.APPLICATION_ID.eq(APPLICATIONS.ID))
  }

  private fun SearchField.internalOnly() = withRestriction {
    currentUser().canReadAllAcceleratorDetails()
  }
}
