package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class ApplicationsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = APPLICATIONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(projects.asSingleValueSublist("project", APPLICATIONS.PROJECT_ID.eq(PROJECTS.ID)))
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", APPLICATIONS.ID) { ApplicationId(it) },
          enumField("status", APPLICATIONS.APPLICATION_STATUS_ID),
      )

  override fun conditionForVisibility(): Condition {
    return if (currentUser().canReadAllAcceleratorDetails()) {
      DSL.trueCondition()
    } else {
      DSL.exists(
          DSL.selectOne()
              .from(PROJECTS)
              .join(APPLICATIONS)
              .on(APPLICATIONS.PROJECT_ID.eq(PROJECTS.ID))
              .where(PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys))
      )
    }
  }
}
