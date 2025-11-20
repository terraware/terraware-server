package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_LAND_USE_MODEL_TYPES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ProjectLandUseModelTypesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECT_LAND_USE_MODEL_TYPES.PROJECT_LAND_USE_MODEL_TYPE_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projects.asSingleValueSublist(
              "project",
              PROJECT_LAND_USE_MODEL_TYPES.PROJECT_ID.eq(PROJECTS.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          enumField("landUseModelType", PROJECT_LAND_USE_MODEL_TYPES.LAND_USE_MODEL_TYPE_ID),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.projects

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(PROJECTS).on(PROJECT_LAND_USE_MODEL_TYPES.PROJECT_ID.eq(PROJECTS.ID))
  }
}
