package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.InternalInterest
import com.terraformation.backend.db.accelerator.tables.references.USER_INTERNAL_INTERESTS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.USERS
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class UserInternalInterestsStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  fun fetchForUser(userId: UserId): Set<InternalInterest> {
    requirePermissions { readUserDeliverableInternalInterests(userId) }

    return with(USER_INTERNAL_INTERESTS) {
      dslContext
          .select(INTERNAL_INTEREST_ID)
          .from(this)
          .where(USER_ID.eq(userId))
          .fetchSet(INTERNAL_INTEREST_ID.asNonNullable())
    }
  }

  /**
   * Returns a condition that can be added to a query on the users table to restrict the results to
   * users with a particular internal interest or with no internal interests at all.
   */
  fun conditionForUsers(internalInterest: InternalInterest): Condition {
    return with(USER_INTERNAL_INTERESTS) {
      DSL.exists(
          DSL.selectOne()
              .from(this)
              .where(INTERNAL_INTEREST_ID.eq(internalInterest))
              .and(USER_ID.eq(USERS.ID))
      )
    }
  }

  fun updateForUser(userId: UserId, internalInterests: Set<InternalInterest>) {
    requirePermissions { updateUserInternalInterests(userId) }

    val existingInterests = fetchForUser(userId)
    val interestsToDelete = existingInterests - internalInterests
    val interestsToInsert = internalInterests - existingInterests

    dslContext.transaction { _ ->
      with(USER_INTERNAL_INTERESTS) {
        if (interestsToDelete.isNotEmpty()) {
          dslContext
              .deleteFrom(this)
              .where(USER_ID.eq(userId))
              .and(INTERNAL_INTEREST_ID.`in`(interestsToDelete))
              .execute()
        }

        if (interestsToInsert.isNotEmpty()) {
          val currentUserId = currentUser().userId
          val now = clock.instant()

          dslContext
              .insertInto(
                  this,
                  CREATED_BY,
                  CREATED_TIME,
                  INTERNAL_INTEREST_ID,
                  USER_ID,
              )
              .valuesOfRows(interestsToInsert.map { DSL.row(currentUserId, now, it, userId) })
              .execute()
        }
      }
    }
  }
}
