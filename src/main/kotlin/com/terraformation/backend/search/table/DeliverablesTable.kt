package com.terraformation.backend.search.table

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_DELIVERABLES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.field.column
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.Table
import org.jooq.TableField

class DeliverablesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = DELIVERABLES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projectDeliverables.asMultiValueSublist(
              "projectDeliverables",
              DELIVERABLES.ID,
              PROJECT_DELIVERABLES.DELIVERABLE_ID,
          ),
          modules.asSingleValueSublist("module", DELIVERABLES.MODULE_ID, MODULES.ID),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", DELIVERABLES.ID) { DeliverableId(it) },
          enumField("category", DELIVERABLES.DELIVERABLE_CATEGORY_ID),
          textField("description", DELIVERABLES.DESCRIPTION_HTML),
          textField("name", DELIVERABLES.NAME),
          integerField("position", DELIVERABLES.POSITION),
          booleanField("required", DELIVERABLES.IS_REQUIRED),
          booleanField("sensitive", DELIVERABLES.IS_SENSITIVE),
          enumField("type", DELIVERABLES.DELIVERABLE_TYPE_ID),
      )

  override val defaultOrderFields = listOf(DELIVERABLES.ID)

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.modules

  override fun <T : Record> joinForVisibility(
      query: SelectJoinStep<T>,
      table: Table<*>,
  ): SelectJoinStep<T> {
    return query.join(MODULES).on(table.column(DELIVERABLES.MODULE_ID).eq(MODULES.ID))
  }
}
