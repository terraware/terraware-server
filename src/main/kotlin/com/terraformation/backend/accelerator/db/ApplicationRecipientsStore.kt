package com.terraformation.backend.accelerator.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.daos.ApplicationRecipientsDao
import com.terraformation.backend.db.accelerator.tables.references.APPLICATION_RECIPIENTS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.USERS
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class ApplicationRecipientsStore(
    private val applicationRecipientsDao: ApplicationRecipientsDao,
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  fun fetch(): Set<UserId> {
    requirePermissions { readApplicationRecipients() }

    return with(APPLICATION_RECIPIENTS) {
      dslContext.selectFrom(this).fetchSet(USER_ID.asNonNullable())
    }
  }

  fun contains(userId: UserId): Boolean {
    requirePermissions { readApplicationRecipients() }

    return applicationRecipientsDao.fetchOneByUserId(userId) != null
  }

  /** Returns a condition that can be added to a query on the users table */
  fun conditionForUsers(): Condition {
    return with(APPLICATION_RECIPIENTS) {
      DSL.exists(DSL.selectOne().from(APPLICATION_RECIPIENTS).where(USER_ID.eq(USERS.ID)))
    }
  }

  fun add(userId: UserId) {
    requirePermissions { manageApplicationRecipients() }

    with(APPLICATION_RECIPIENTS) {
      dslContext
          .insertInto(this)
          .set(USER_ID, userId)
          .set(CREATED_TIME, clock.instant())
          .set(CREATED_BY, currentUser().userId)
          .onConflictDoNothing()
          .execute()
    }
  }

  fun remove(userId: UserId) {
    requirePermissions { manageApplicationRecipients() }

    with(APPLICATION_RECIPIENTS) { dslContext.deleteFrom(this).where(USER_ID.eq(userId)).execute() }
  }
}
