package com.terraformation.backend.funder.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import com.terraformation.backend.funder.model.FundingEntityModel
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class FundingEntityStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  fun fetchOneById(fundingEntityId: FundingEntityId): FundingEntityModel {
    requirePermissions { readFundingEntities() }

    return fetch(FUNDING_ENTITIES.ID.eq(fundingEntityId)).firstOrNull()
        ?: throw FundingEntityNotFoundException(fundingEntityId)
  }

  fun create(name: String): FundingEntityModel {
    requirePermissions { manageFundingEntities() }

    val userId = currentUser().userId
    val now = clock.instant()

    return dslContext.transactionResult { _ ->
      val fundingEntityId =
          with(FUNDING_ENTITIES) {
            dslContext
                .insertInto(FUNDING_ENTITIES)
                .set(NAME, name)
                .set(CREATED_BY, userId)
                .set(CREATED_TIME, now)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .onConflict()
                .doNothing()
                .returning(ID)
                .fetchOne(ID) ?: throw FundingEntityExistsException(name)
          }

      fetchOneById(fundingEntityId)
    }
  }

  private fun fetch(condition: Condition? = null): List<FundingEntityModel> {
    return with(FUNDING_ENTITIES) {
      dslContext
          .select(asterisk())
          .from(this)
          .apply { condition?.let { where(it) } }
          .orderBy(ID)
          .fetch { FundingEntityModel.of(it) }
    }
  }
}
