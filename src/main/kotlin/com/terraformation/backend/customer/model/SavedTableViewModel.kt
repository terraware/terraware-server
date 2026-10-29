package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SavedTableViewId
import com.terraformation.backend.db.default_schema.tables.references.SAVED_TABLE_VIEWS
import org.jooq.JSONB
import org.jooq.Record

data class SavedTableViewModel(
    val id: SavedTableViewId,
    val name: String,
    val organizationId: OrganizationId?,
    val settings: JSONB,
    val tableName: String,
) {
  companion object {
    fun of(record: Record): SavedTableViewModel {
      return SavedTableViewModel(
          id = record[SAVED_TABLE_VIEWS.ID]!!,
          name = record[SAVED_TABLE_VIEWS.NAME]!!,
          organizationId = record[SAVED_TABLE_VIEWS.ORGANIZATION_ID],
          settings = record[SAVED_TABLE_VIEWS.SETTINGS]!!,
          tableName = record[SAVED_TABLE_VIEWS.TABLE_NAME]!!,
      )
    }
  }
}
