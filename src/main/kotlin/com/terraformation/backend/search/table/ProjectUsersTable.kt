package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import com.terraformation.backend.db.tables.references.USERS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField

class ProjectUsersTable(tables: SearchTables) : SearchTable() {
  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projects.asSingleValueSublist("project", PROJECT_USERS.PROJECT_ID.eq(PROJECTS.ID)),
          users.asSingleValueSublist("user", PROJECT_USERS.USER_ID.eq(USERS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField(
              "createdTime", "Project membership creation time", PROJECT_USERS.CREATED_TIME),
      )

  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECT_USERS.PROJECT_USER_ID

  override fun conditionForVisibility(): Condition {
    return PROJECT_USERS.PROJECT_ID.`in`(currentUser().projectRoles.keys)
  }
}
