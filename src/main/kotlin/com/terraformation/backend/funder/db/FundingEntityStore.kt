package com.terraformation.backend.funder.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.funder.model.FundingEntityModel
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class FundingEntityStore(
    private val dslContext: DSLContext,
) {
  fun fetchOneById(
      fundingEntityId: FundingEntityId,
  ): FundingEntityModel {
    requirePermissions { readFundingEntities() }

    return fetchWithCondition(FUNDING_ENTITIES.ID.eq(fundingEntityId)).firstOrNull()
        ?: throw FundingEntityNotFoundException(fundingEntityId)
  }

  private fun fetchWithCondition(condition: Condition? = null): List<FundingEntityModel> {
    val projectsMultiset =
        DSL.multiset(
                DSL.select(FUNDING_ENTITY_PROJECTS.PROJECT_ID)
                    .from(FUNDING_ENTITY_PROJECTS)
                    .where(FUNDING_ENTITY_PROJECTS.FUNDING_ENTITY_ID.eq(FUNDING_ENTITIES.ID)))
            .convertFrom { result ->
              result.map { it[FUNDING_ENTITY_PROJECTS.PROJECT_ID.asNonNullable()] }
            }

    return dslContext
        .select(FUNDING_ENTITIES.asterisk(), projectsMultiset)
        .from(FUNDING_ENTITIES)
        .apply { condition?.let { where(it) } }
        .orderBy(FUNDING_ENTITIES.ID)
        .fetch { FundingEntityModel(it, projectsMultiset) }
  }
}
