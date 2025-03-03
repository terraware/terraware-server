package com.terraformation.backend.funder

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class FundingEntityService(
    private val dslContext: DSLContext,
) {
  private val log = perClassLogger()

  fun deleteFundingEntity(fundingEntityId: FundingEntityId) {
    requirePermissions { manageFundingEntities() }

    log.info("Deleting funding entity $fundingEntityId")

    dslContext.transaction { _ ->
      dslContext
          .deleteFrom(FUNDING_ENTITIES)
          .where(FUNDING_ENTITIES.ID.eq(fundingEntityId))
          .execute()
    }
  }
}
