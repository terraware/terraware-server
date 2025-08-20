package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VARIABLE_VALUES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SELECT_OPTIONS
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SELECT_OPTION_VALUES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class ProjectVariableValuesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECT_VARIABLE_VALUES.VARIABLE_VALUE_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          variableSelectOptions.asMultiValueSublist(
              "options",
              DSL.exists(
                  DSL.selectOne()
                      .from(VARIABLE_SELECT_OPTION_VALUES)
                      .where(
                          VARIABLE_SELECT_OPTION_VALUES.VARIABLE_VALUE_ID.eq(
                              PROJECT_VARIABLE_VALUES.VARIABLE_VALUE_ID
                          )
                      )
                      .and(VARIABLE_SELECT_OPTION_VALUES.OPTION_ID.eq(VARIABLE_SELECT_OPTIONS.ID))
              ),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField("dateValue", PROJECT_VARIABLE_VALUES.DATE_VALUE),
          textField("linkTitle", PROJECT_VARIABLE_VALUES.LINK_TITLE),
          uriField("linkUrl", PROJECT_VARIABLE_VALUES.LINK_URL),
          integerField("listPosition", PROJECT_VARIABLE_VALUES.LIST_POSITION),
          bigDecimalField("numberValue", PROJECT_VARIABLE_VALUES.NUMBER_VALUE),
          idWrapperField("projectId", PROJECT_VARIABLE_VALUES.PROJECT_ID) { ProjectId(it) },
          textField("textValue", PROJECT_VARIABLE_VALUES.TEXT_VALUE),
          idWrapperField("variableId", PROJECT_VARIABLE_VALUES.VARIABLE_ID) { VariableId(it) },
          idWrapperField("variableValueId", PROJECT_VARIABLE_VALUES.VARIABLE_VALUE_ID) {
            VariableValueId(it)
          },
      )

  override fun conditionForVisibility(): Condition {
    return if (currentUser().canReadAllAcceleratorDetails()) {
      DSL.trueCondition()
    } else {
      DSL.exists(
          DSL.selectOne()
              .from(PROJECTS)
              .where(PROJECT_VARIABLE_VALUES.PROJECT_ID.eq(PROJECTS.ID))
              .and(PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys))
      )
    }
  }

  override val defaultOrderFields =
      listOf(
          PROJECT_VARIABLE_VALUES.PROJECT_ID,
          PROJECT_VARIABLE_VALUES.VARIABLE_ID,
          PROJECT_VARIABLE_VALUES.LIST_POSITION,
      )
}
