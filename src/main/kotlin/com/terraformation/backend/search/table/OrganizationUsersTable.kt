package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import com.terraformation.backend.db.tables.references.USERS
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
              "organization", ORGANIZATION_USERS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
          projectUsers.asMultiValueSublist(
              "projectMemberships",
              ORGANIZATION_USERS.USER_ID.eq(PROJECT_USERS.USER_ID)
                  .and(
                      ORGANIZATION_USERS.ORGANIZATION_ID.eq(
                          PROJECT_USERS.projects().ORGANIZATION_ID))),
          users.asSingleValueSublist("user", ORGANIZATION_USERS.USER_ID.eq(USERS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField(
              "createdTime",
              "Organization membership creation time",
              ORGANIZATION_USERS.CREATED_TIME),
          mappedField(
              "roleName",
              "User role name",
              ORGANIZATION_USERS.ROLE_ID,
              nullable = false,
              convertSearchFilter = { Role.of(it)?.id },
              convertDatabaseValue = { Role.of(it)?.displayName }),
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
                    .and(USERS.USER_TYPE_ID.`in`(UserType.Individual, UserType.SuperAdmin))))
  }
}
