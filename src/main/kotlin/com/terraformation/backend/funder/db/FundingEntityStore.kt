package com.terraformation.backend.funder.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.funder.model.FundingEntityModel
import com.terraformation.backend.funder.model.FundingEntityProjectModel
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
      depth: FetchDepth = FetchDepth.FundingEntity
  ): FundingEntityModel {
    requirePermissions { readFundingEntities() }

    return fetchForDepth(depth, FUNDING_ENTITIES.ID.eq(fundingEntityId)).firstOrNull()
        ?: throw FundingEntityNotFoundException(fundingEntityId)
  }

  private fun fetchForDepth(
      depth: FetchDepth,
      condition: Condition? = null
  ): List<FundingEntityModel> {
    val projectsMultiset =
        if (depth.level >= FetchDepth.Project.level) {
          DSL.multiset(
                  DSL.select(
                          PROJECTS.ID,
                          PROJECTS.NAME,
                          PROJECTS.DESCRIPTION,
                      )
                      .from(PROJECTS)
                      .join(FUNDING_ENTITY_PROJECTS)
                      .on(PROJECTS.ID.eq(FUNDING_ENTITY_PROJECTS.PROJECT_ID))
                      .where(FUNDING_ENTITY_PROJECTS.FUNDING_ENTITY_ID.eq(FUNDING_ENTITIES.ID)))
              .convertFrom { result -> result.map { FundingEntityProjectModel(it) } }
        } else {
          DSL.value(null as List<FundingEntityProjectModel>?)
        }

    return dslContext
        .select(FUNDING_ENTITIES.asterisk(), projectsMultiset)
        .from(FUNDING_ENTITIES)
        .apply { condition?.let { where(it) } }
        .orderBy(FUNDING_ENTITIES.ID)
        .fetch { FundingEntityModel(it, projectsMultiset) }
  }

  enum class FetchDepth(val level: Int) {
    FundingEntity(1),
    Project(2)
  }
}
