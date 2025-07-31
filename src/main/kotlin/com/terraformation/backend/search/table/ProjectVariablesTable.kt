package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VARIABLES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VARIABLE_VALUES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class ProjectVariablesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECT_VARIABLES.PROJECT_VARIABLE_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projectVariableValues.asMultiValueSublist(
              "variableValues",
              PROJECT_VARIABLES.VARIABLE_ID.eq(PROJECT_VARIABLE_VALUES.VARIABLE_ID)
                  .and(PROJECT_VARIABLES.PROJECT_ID.eq(PROJECT_VARIABLE_VALUES.PROJECT_ID))),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("projectId", PROJECT_VARIABLES.PROJECT_ID) { ProjectId(it) },
          idWrapperField("variableId", PROJECT_VARIABLES.VARIABLE_ID) { VariableId(it) },
          stableIdField("stableId", PROJECT_VARIABLES.STABLE_ID),
          textField("variableName", PROJECT_VARIABLES.NAME),
          booleanField("isList", PROJECT_VARIABLES.IS_LIST),
          booleanField("isMultiSelect", PROJECT_VARIABLES.IS_MULTI_SELECT),
          enumField("variableType", PROJECT_VARIABLES.VARIABLE_TYPE_ID, false),
      )

  override fun conditionForVisibility(): Condition {
    return if (currentUser().canReadAllAcceleratorDetails()) {
      DSL.trueCondition()
    } else {
      DSL.exists(
          DSL.selectOne()
              .from(PROJECTS)
              .where(PROJECT_VARIABLES.PROJECT_ID.eq(PROJECTS.ID))
              .and(PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)))
    }
  }

  override val defaultOrderFields =
      listOf(PROJECT_VARIABLES.PROJECT_ID, PROJECT_VARIABLES.STABLE_ID)
}
