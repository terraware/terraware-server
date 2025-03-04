package com.terraformation.backend.funder.db

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_USERS
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class FundingEntityUserStore(private val dslContext: DSLContext) {
  fun getFundingEntityId(userId: UserId): FundingEntityId? {
    return dslContext
        .select(FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID)
        .from(FUNDING_ENTITY_USERS)
        .where(FUNDING_ENTITY_USERS.USER_ID.eq(userId))
        .fetchOne()
        ?.getValue(FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID)
  }
}
