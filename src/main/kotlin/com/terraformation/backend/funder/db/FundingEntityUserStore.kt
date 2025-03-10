package com.terraformation.backend.funder.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_USERS
import com.terraformation.backend.funder.model.FundingEntityModel
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class FundingEntityUserStore(private val dslContext: DSLContext) {
  fun getFundingEntityId(userId: UserId): FundingEntityId? {
    requirePermissions { readUser(userId) }

    return dslContext
        .select(FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID)
        .from(FUNDING_ENTITY_USERS)
        .where(FUNDING_ENTITY_USERS.USER_ID.eq(userId))
        .fetchOne(FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID)
  }

  fun fetchEntityByUserId(userId: UserId): FundingEntityModel? {
    requirePermissions { readUser(userId) }

    return dslContext
        .select(FUNDING_ENTITIES.asterisk())
        .from(FUNDING_ENTITIES)
        .join(FUNDING_ENTITY_USERS)
        .on(FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID.eq(FUNDING_ENTITIES.ID))
        .where(FUNDING_ENTITY_USERS.USER_ID.eq(userId))
        .fetchOne { FundingEntityModel.of(it) }
  }
}
