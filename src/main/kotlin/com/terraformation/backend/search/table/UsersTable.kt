package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.USERS
import com.terraformation.backend.search.SearchScope
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class UsersTable(private val tables: SearchTables) : SearchTable() {
  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          organizationUsers.asMultiValueSublist(
              "organizationMemberships", USERS.ID.eq(ORGANIZATION_USERS.USER_ID)),
      )
    }
  }

  override val fields: List<SearchField> by lazy {
    listOf(
        timestampField("createdTime", "User creation time", USERS.CREATED_TIME),
        textField("email", "User email address", USERS.EMAIL),
        textField("firstName", "User first name", USERS.FIRST_NAME),
        idWrapperField("id", "User ID", USERS.ID) { UserId(it) },
        timestampField("lastActivityTime", "User last activity time", USERS.LAST_ACTIVITY_TIME),
        textField("lastName", "User last name", USERS.LAST_NAME),
    )
  }

  override val primaryKey: TableField<out Record, out Any?>
    get() = USERS.ID

  // Users are only visible to other people in the same organizations, and device manager users are
  // not visible via this table.
  override fun conditionForVisibility(): Condition {
    return USERS.USER_TYPE_ID.`in`(UserType.Individual, UserType.SuperAdmin)
        .and(
            DSL.exists(
                DSL.selectOne()
                    .from(ORGANIZATION_USERS)
                    .where(USERS.ID.eq(ORGANIZATION_USERS.USER_ID))
                    .and(
                        ORGANIZATION_USERS.ORGANIZATION_ID.`in`(
                            currentUser().organizationRoles.keys))))
  }

  override fun conditionForScope(scope: SearchScope): Condition? = null
}
