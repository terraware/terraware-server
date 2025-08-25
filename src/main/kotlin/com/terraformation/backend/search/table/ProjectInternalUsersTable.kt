package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.tables.ProjectInternalUsers.Companion.PROJECT_INTERNAL_USERS
import com.terraformation.backend.db.default_schema.tables.Projects.Companion.PROJECTS
import com.terraformation.backend.db.default_schema.tables.Users.Companion.USERS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class ProjectInternalUsersTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECT_INTERNAL_USERS.PROJECT_INTERNAL_USER_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projects.asSingleValueSublist(
              "project",
              PROJECT_INTERNAL_USERS.PROJECT_ID.eq(PROJECTS.ID),
          ),
          users.asSingleValueSublist("user", PROJECT_INTERNAL_USERS.USER_ID.eq(USERS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          enumField("role", PROJECT_INTERNAL_USERS.PROJECT_INTERNAL_ROLE_ID),
          textField("roleName", PROJECT_INTERNAL_USERS.ROLE_NAME),
      )

  override fun conditionForVisibility(): Condition? {
    return if (currentUser().canReadAllAcceleratorDetails()) {
      DSL.trueCondition()
    } else {
      DSL.exists(
          DSL.selectOne()
              .from(PROJECTS)
              .where(PROJECTS.ID.eq(PROJECT_INTERNAL_USERS.PROJECT_ID))
              .and(PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys))
      )
    }
  }
}
