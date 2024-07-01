package com.terraformation.backend.accelerator.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.tables.references.USER_DELIVERABLE_CATEGORIES
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.UserId
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class UserDeliverableCategoriesStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  fun fetchForUser(userId: UserId): Set<DeliverableCategory> {
    requirePermissions { readUserDeliverableCategories(userId) }

    return with(USER_DELIVERABLE_CATEGORIES) {
      dslContext
          .select(DELIVERABLE_CATEGORY_ID)
          .from(USER_DELIVERABLE_CATEGORIES)
          .where(USER_ID.eq(userId))
          .fetchSet(DELIVERABLE_CATEGORY_ID.asNonNullable())
    }
  }

  fun updateForUser(userId: UserId, deliverableCategories: Set<DeliverableCategory>) {
    requirePermissions { updateUserDeliverableCategories(userId) }

    val existingCategories = fetchForUser(userId)
    val categoriesToDelete = existingCategories - deliverableCategories
    val categoriesToInsert = deliverableCategories - existingCategories

    dslContext.transaction { _ ->
      with(USER_DELIVERABLE_CATEGORIES) {
        if (categoriesToDelete.isNotEmpty()) {
          dslContext
              .deleteFrom(USER_DELIVERABLE_CATEGORIES)
              .where(USER_ID.eq(userId))
              .and(DELIVERABLE_CATEGORY_ID.`in`(categoriesToDelete))
              .execute()
        }

        if (categoriesToInsert.isNotEmpty()) {
          val currentUserId = currentUser().userId
          val now = clock.instant()

          dslContext
              .insertInto(
                  USER_DELIVERABLE_CATEGORIES,
                  CREATED_BY,
                  CREATED_TIME,
                  DELIVERABLE_CATEGORY_ID,
                  USER_ID,
              )
              .valuesOfRows(categoriesToInsert.map { DSL.row(currentUserId, now, it, userId) })
              .execute()
        }
      }
    }
  }
}
