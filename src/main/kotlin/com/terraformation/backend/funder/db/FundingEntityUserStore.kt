package com.terraformation.backend.funder.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_USERS
import com.terraformation.backend.funder.model.FunderUserModel
import com.terraformation.backend.funder.model.FundingEntityModel
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.Record

@Named
class FundingEntityUserStore(private val dslContext: DSLContext) {
  fun getFundingEntityId(userId: UserId): FundingEntityId? {
    requirePermissions { readUser(userId) }
    return findFundingEntityByUserId(userId) { record -> record.get(FUNDING_ENTITIES.ID) }
  }

  fun fetchEntityByUserId(userId: UserId): FundingEntityModel? {
    requirePermissions { readUser(userId) }
    return findFundingEntityByUserId(userId) { record -> FundingEntityModel.of(record) }
  }

  fun fetchFundersForEntity(entityId: FundingEntityId): List<FunderUserModel> {
    requirePermissions { listFundingEntityUsers(entityId) }

    return with(FUNDING_ENTITY_USERS) {
      dslContext
          .select(
              users.ID,
              users.EMAIL,
              users.FIRST_NAME,
              users.LAST_NAME,
              users.CREATED_TIME,
              users.AUTH_ID.isNotNull)
          .from(this)
          .where(FUNDING_ENTITY_ID.eq(entityId))
          .and(users.DELETED_TIME.isNull)
          .orderBy(users.ID)
          .fetch { FunderUserModel.of(it) }
    }
  }

  private fun <T> findFundingEntityByUserId(userId: UserId, mapper: (Record) -> T): T? {
    return dslContext
        .select(FUNDING_ENTITIES.asterisk())
        .from(FUNDING_ENTITIES)
        .join(FUNDING_ENTITY_USERS)
        .on(FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID.eq(FUNDING_ENTITIES.ID))
        .where(FUNDING_ENTITY_USERS.USER_ID.eq(userId))
        .fetchOne(mapper)
  }
}
