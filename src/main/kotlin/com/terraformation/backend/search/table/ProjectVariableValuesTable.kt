package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VARIABLE_VALUES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.tables.references.VARIABLES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_LINK_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SELECTS
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SELECT_OPTIONS
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SELECT_OPTION_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_VALUES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class ProjectVariableValuesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECT_VARIABLE_VALUES.PROJECT_VARIABLE_VALUE_ID

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
                              PROJECT_VARIABLE_VALUES.VARIABLE_VALUE_ID))
                      .and(VARIABLE_SELECT_OPTION_VALUES.OPTION_ID.eq(VARIABLE_SELECT_OPTIONS.ID))),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("projectId", PROJECT_VARIABLE_VALUES.PROJECT_ID) { ProjectId(it) },
          idWrapperField("variableId", PROJECT_VARIABLE_VALUES.VARIABLE_ID) { VariableId(it) },
          idWrapperField("variableValueId", PROJECT_VARIABLE_VALUES.VARIABLE_VALUE_ID) {
            VariableValueId(it)
          },
          stableIdField("stableId", PROJECT_VARIABLE_VALUES.STABLE_ID),
          textField(
              "variableName",
              DSL.field(
                  DSL.select(VARIABLES.NAME)
                      .from(VARIABLES)
                      .where(VARIABLES.ID.eq(PROJECT_VARIABLE_VALUES.VARIABLE_ID)),
              ),
          ),
          enumField(
              "variableType",
              DSL.field(
                  DSL.select(VARIABLE_VALUES.VARIABLE_TYPE_ID)
                      .from(VARIABLE_VALUES)
                      .where(VARIABLE_VALUES.ID.eq(PROJECT_VARIABLE_VALUES.VARIABLE_VALUE_ID))),
              false,
          ),
          textField(
              "textValue",
              DSL.field(
                  DSL.select(VARIABLE_VALUES.TEXT_VALUE)
                      .from(VARIABLE_VALUES)
                      .where(VARIABLE_VALUES.ID.eq(PROJECT_VARIABLE_VALUES.VARIABLE_VALUE_ID)))),
          bigDecimalField(
              "numberValue",
              DSL.field(
                  DSL.select(VARIABLE_VALUES.NUMBER_VALUE)
                      .from(VARIABLE_VALUES)
                      .where(VARIABLE_VALUES.ID.eq(PROJECT_VARIABLE_VALUES.VARIABLE_VALUE_ID)))),
          dateField(
              "dateValue",
              DSL.field(
                  DSL.select(VARIABLE_VALUES.DATE_VALUE)
                      .from(VARIABLE_VALUES)
                      .where(VARIABLE_VALUES.ID.eq(PROJECT_VARIABLE_VALUES.VARIABLE_VALUE_ID)))),
          textField(
              "linkValue",
              DSL.field(
                  DSL.select(VARIABLE_LINK_VALUES.URL)
                      .from(VARIABLE_LINK_VALUES)
                      .where(
                          VARIABLE_LINK_VALUES.VARIABLE_VALUE_ID.eq(
                              PROJECT_VARIABLE_VALUES.VARIABLE_VALUE_ID)))),
          booleanField(
              "isMultiSelect",
              DSL.field(
                  DSL.select(VARIABLE_SELECTS.IS_MULTIPLE)
                      .from(VARIABLE_SELECTS)
                      .where(
                          VARIABLE_SELECTS.VARIABLE_ID.eq(PROJECT_VARIABLE_VALUES.VARIABLE_ID)))),
      )

  override fun conditionForVisibility(): Condition {
    return if (currentUser().canReadAllAcceleratorDetails()) {
      DSL.trueCondition()
    } else {
      DSL.exists(
          DSL.selectOne()
              .from(PROJECTS)
              .where(PROJECT_VARIABLE_VALUES.PROJECT_ID.eq(PROJECTS.ID))
              .and(PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)))
    }
  }

  override val defaultOrderFields =
      listOf(PROJECT_VARIABLE_VALUES.PROJECT_ID, PROJECT_VARIABLE_VALUES.STABLE_ID)
}
