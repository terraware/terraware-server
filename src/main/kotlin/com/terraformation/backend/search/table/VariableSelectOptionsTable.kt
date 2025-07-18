package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.docprod.VariableSelectOptionId
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SELECT_OPTIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class VariableSelectOptionsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = VARIABLE_SELECT_OPTIONS.ID

  override val sublists: List<SublistField> = emptyList()

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", VARIABLE_SELECT_OPTIONS.ID) { VariableSelectOptionId(it) },
          textField("name", VARIABLE_SELECT_OPTIONS.NAME),
          integerField("position", VARIABLE_SELECT_OPTIONS.POSITION),
      )

  override fun conditionForVisibility(): Condition {
    return if (currentUser().canReadAllAcceleratorDetails()) {
      DSL.trueCondition()
    } else {
      DSL.falseCondition()
    }
  }

  override val defaultOrderFields =
      listOf(VARIABLE_SELECT_OPTIONS.ID, VARIABLE_SELECT_OPTIONS.POSITION)
}
