package com.terraformation.backend.funder.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.pojos.FundingEntitiesRow
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import com.terraformation.backend.funder.model.FundingEntityModel
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.springframework.dao.DuplicateKeyException

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
                .onConflict(NAME)
                .doNothing()
                .returning(ID)
                .fetchOne(ID) ?: throw FundingEntityExistsException(name)
          }

      fetchOneById(fundingEntityId)
    }
  }

  fun update(row: FundingEntitiesRow) {
    val fundingEntityId = row.id ?: throw IllegalArgumentException("Funding Entity ID must be set")

    requirePermissions { manageFundingEntities() }

    val userId = currentUser().userId
    val now = clock.instant()

    try {
      with(FUNDING_ENTITIES) {
        dslContext
            .update(FUNDING_ENTITIES)
            .set(NAME, row.name)
            .set(MODIFIED_BY, userId)
            .set(MODIFIED_TIME, now)
            .where(ID.eq(fundingEntityId))
            .execute()
      }
    } catch (e: DuplicateKeyException) {
      throw FundingEntityExistsException(row.name!!)
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
