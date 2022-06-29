package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.tables.references.PROJECT_TYPE_SELECTIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField

class ProjectTypeSelectionsTable : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECT_TYPE_SELECTIONS.PROJECT_ID

  override val sublists: List<SublistField> = emptyList()

  override val fields: List<SearchField> =
      listOf(
          enumField("type", "Project type", PROJECT_TYPE_SELECTIONS.PROJECT_TYPE_ID),
      )

  override fun conditionForVisibility(): Condition {
    return PROJECT_TYPE_SELECTIONS.PROJECT_ID.`in`(currentUser().projectRoles.keys)
  }
}
