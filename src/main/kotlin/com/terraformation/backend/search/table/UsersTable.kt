package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_INTERNAL_USERS
import com.terraformation.backend.db.default_schema.tables.references.USERS
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
              "organizationMemberships",
              USERS.ID.eq(ORGANIZATION_USERS.USER_ID),
          ),
          projectInternalUsers.asMultiValueSublist(
              "projectInternalMemberships",
              USERS.ID.eq(PROJECT_INTERNAL_USERS.USER_ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> by lazy {
    listOf(
        timestampField("createdTime", USERS.CREATED_TIME),
        textField("email", USERS.EMAIL),
        textField("firstName", USERS.FIRST_NAME),
        idWrapperField("id", USERS.ID) { UserId(it) },
        timestampField("lastActivityTime", USERS.LAST_ACTIVITY_TIME),
        textField("lastName", USERS.LAST_NAME),
        zoneIdField("timeZone", USERS.TIME_ZONE),
    )
  }

  override val primaryKey: TableField<out Record, out Any?>
    get() = USERS.ID

  // Users are only visible to other people in the same organizations, and device manager users are
  // not visible via this table.
  override fun conditionForVisibility(): Condition {
    return USERS.USER_TYPE_ID.eq(UserType.Individual)
        .and(
            DSL.or(
                listOf(
                    DSL.exists(
                        DSL.selectOne()
                            .from(ORGANIZATION_USERS)
                            .where(USERS.ID.eq(ORGANIZATION_USERS.USER_ID))
                            .and(
                                ORGANIZATION_USERS.ORGANIZATION_ID.`in`(
                                    currentUser().organizationRoles.keys
                                )
                            )
                    ),
                    DSL.exists(
                        DSL.selectOne()
                            .from(PROJECTS)
                            .join(PROJECT_INTERNAL_USERS)
                            .on(PROJECTS.ID.eq(PROJECT_INTERNAL_USERS.PROJECT_ID))
                            .where(USERS.ID.eq(PROJECT_INTERNAL_USERS.USER_ID))
                            .and(
                                PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
                            )
                    ),
                )
            )
        )
  }
}
