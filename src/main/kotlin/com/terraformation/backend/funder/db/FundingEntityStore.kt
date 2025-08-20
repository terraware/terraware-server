package com.terraformation.backend.funder.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.funder.model.FundingEntityModel
import com.terraformation.backend.funder.model.FundingProjectModel
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class FundingEntityStore(
    private val dslContext: DSLContext,
) {
  fun fetchAll(): List<FundingEntityModel> {
    requirePermissions { readFundingEntities() }

    return fetchWithCondition()
  }

  fun fetchByProjectId(projectId: ProjectId): List<FundingEntityModel> {
    requirePermissions { readFundingEntities() }
    requirePermissions { readProject(projectId) }

    return fetchWithCondition(FUNDING_ENTITY_PROJECTS.PROJECT_ID.eq(projectId))
  }

  fun fetchOneById(
      fundingEntityId: FundingEntityId,
  ): FundingEntityModel {
    requirePermissions { readFundingEntity(fundingEntityId) }

    return fetchWithCondition(FUNDING_ENTITIES.ID.eq(fundingEntityId)).firstOrNull()
        ?: throw FundingEntityNotFoundException(fundingEntityId)
  }

  private fun fetchWithCondition(condition: Condition? = null): List<FundingEntityModel> {
    val records =
        dslContext
            .select(
                FUNDING_ENTITIES.ID,
                FUNDING_ENTITIES.NAME,
                FUNDING_ENTITIES.CREATED_TIME,
                FUNDING_ENTITIES.MODIFIED_TIME,
                FUNDING_ENTITY_PROJECTS.PROJECT_ID,
                PROJECT_ACCELERATOR_DETAILS.DEAL_NAME,
            )
            .from(FUNDING_ENTITIES)
            .leftJoin(FUNDING_ENTITY_PROJECTS)
            .on(FUNDING_ENTITIES.ID.eq(FUNDING_ENTITY_PROJECTS.FUNDING_ENTITY_ID))
            .leftJoin(PROJECT_ACCELERATOR_DETAILS)
            .on(FUNDING_ENTITY_PROJECTS.PROJECT_ID.eq(PROJECT_ACCELERATOR_DETAILS.PROJECT_ID))
            .apply { condition?.let { where(it) } }
            .orderBy(FUNDING_ENTITIES.ID, PROJECT_ACCELERATOR_DETAILS.DEAL_NAME)
            .fetch()

    return records
        .groupBy { it.get(FUNDING_ENTITIES.ID) }
        .map { (entityId, groupRecords) ->
          val entity = groupRecords.first()

          FundingEntityModel(
              id = entityId!!,
              name = entity[FUNDING_ENTITIES.NAME]!!,
              createdTime = entity[FUNDING_ENTITIES.CREATED_TIME]!!,
              modifiedTime = entity[FUNDING_ENTITIES.MODIFIED_TIME]!!,
              projects =
                  groupRecords
                      .filter { it[FUNDING_ENTITY_PROJECTS.PROJECT_ID] != null }
                      .map { record ->
                        FundingProjectModel(
                            projectId = record[FUNDING_ENTITY_PROJECTS.PROJECT_ID]!!,
                            dealName = record[PROJECT_ACCELERATOR_DETAILS.DEAL_NAME].orEmpty(),
                        )
                      },
          )
        }
  }
}
