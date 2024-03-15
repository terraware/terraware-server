package com.terraformation.backend.accelerator.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.references.DEFAULT_VOTERS
import com.terraformation.backend.db.default_schema.UserId
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class DefaultVoterStore(
    private val dslContext: DSLContext,
) {
  fun findAll(): List<UserId> {
    requirePermissions { readDefaultVoters() }
    return with(DEFAULT_VOTERS) {
      dslContext.selectFrom(this).orderBy(USER_ID).fetch(USER_ID).filterNotNull()
    }
  }

  fun exists(userId: UserId): Boolean {
    requirePermissions { readDefaultVoters() }
    return dslContext.fetchExists(DEFAULT_VOTERS, DEFAULT_VOTERS.USER_ID.eq(userId))
  }

  fun insert(userId: UserId) {
    requirePermissions { updateDefaultVoters() }
    with(DEFAULT_VOTERS) {
      dslContext.insertInto(this).set(USER_ID, userId).onConflict().doNothing().execute()
    }
  }

  fun delete(userId: UserId) {
    requirePermissions { updateDefaultVoters() }
    with(DEFAULT_VOTERS) { dslContext.deleteFrom(this).where(USER_ID.eq(userId)).execute() }
  }
}
