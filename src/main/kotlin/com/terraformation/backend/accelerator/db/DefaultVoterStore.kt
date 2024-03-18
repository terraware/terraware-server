package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.DefaultVoterChangedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.references.DEFAULT_VOTERS
import com.terraformation.backend.db.default_schema.UserId
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher

@Named
class DefaultVoterStore(
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
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

  fun insert(userId: UserId, updateProjects: Boolean = false) {
    requirePermissions { updateDefaultVoters() }
    with(DEFAULT_VOTERS) {
      dslContext.insertInto(this).set(USER_ID, userId).onConflict().doNothing().execute()
    }
    if (updateProjects) {
      eventPublisher.publishEvent(DefaultVoterChangedEvent(userId))
    }
  }

  fun delete(userId: UserId, updateProjects: Boolean = false) {
    requirePermissions { updateDefaultVoters() }
    with(DEFAULT_VOTERS) { dslContext.deleteFrom(this).where(USER_ID.eq(userId)).execute() }
    if (updateProjects) {
      eventPublisher.publishEvent(DefaultVoterChangedEvent(userId))
    }
  }
}
