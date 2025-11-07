package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SimpleUserModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.i18n.Messages
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class SimpleUserStore(private val dslContext: DSLContext, private val messages: Messages) {
  fun fetchSimpleUsersById(userIds: Collection<UserId>): Map<UserId, SimpleUserModel> {
    return with(USERS) {
      val userInSameOrgCondition =
          if (currentUser() is SystemUser) {
            DSL.trueCondition()
          } else {
            DSL.exists(
                DSL.selectOne()
                    .from(ORGANIZATION_USERS)
                    .where(ORGANIZATION_USERS.USER_ID.eq(ID))
                    .and(
                        ORGANIZATION_USERS.ORGANIZATION_ID.`in`(
                            currentUser().organizationRoles.keys
                        )
                    )
            )
          }

      val existingUsers =
          dslContext
              .select(
                  ID.asNonNullable(),
                  EMAIL.asNonNullable(),
                  FIRST_NAME,
                  LAST_NAME,
                  userInSameOrgCondition,
              )
              .from(USERS)
              .where(ID.`in`(userIds))
              .and(DELETED_TIME.isNull)
              .fetchMap(ID.asNonNullable()) { (userId, email, firstName, lastName, isInSameOrg) ->
                SimpleUserModel.create(
                    email = email,
                    firstName = firstName,
                    lastName = lastName,
                    messages = messages,
                    userId = userId,
                    userIsDeleted = false,
                    userIsInSameOrg = isInSameOrg,
                )
              }

      val nonexistentUsers =
          (userIds.toSet() - existingUsers.keys).associateWith {
            SimpleUserModel(it, messages.formerUser())
          }

      existingUsers + nonexistentUsers
    }
  }
}
