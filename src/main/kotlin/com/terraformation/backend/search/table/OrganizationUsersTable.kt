package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class OrganizationUsersTable(tables: SearchTables) : SearchTable() {
  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          organizations.asSingleValueSublist(
              "organization",
              ORGANIZATION_USERS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID),
          ),
          users.asSingleValueSublist("user", ORGANIZATION_USERS.USER_ID.eq(USERS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("createdTime", ORGANIZATION_USERS.CREATED_TIME),
          enumField("roleName", ORGANIZATION_USERS.ROLE_ID),
      )

  override val primaryKey: TableField<out Record, out Any?>
    get() = ORGANIZATION_USERS.ORGANIZATION_USER_ID

  override fun conditionForVisibility(): Condition {
    return ORGANIZATION_USERS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
        .and(
            DSL.exists(
                DSL.selectOne()
                    .from(USERS)
                    .where(USERS.ID.eq(ORGANIZATION_USERS.USER_ID))
                    .and(USERS.USER_TYPE_ID.eq(UserType.Individual))
            )
        )
  }
}
