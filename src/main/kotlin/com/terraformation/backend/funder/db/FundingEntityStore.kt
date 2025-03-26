package com.terraformation.backend.funder.db

import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.funder.model.FundingEntityModel
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class FundingEntityStore(
    private val dslContext: DSLContext,
    private val acceleratorProjectVariableValuesService: AcceleratorProjectVariableValuesService,
) {
  fun fetchAll(): List<FundingEntityModel> {
    requirePermissions { readFundingEntities() }

    return fetchWithCondition()
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
                PROJECTS.ID,
            )
            .from(FUNDING_ENTITIES)
            .leftJoin(FUNDING_ENTITY_PROJECTS)
            .on(FUNDING_ENTITIES.ID.eq(FUNDING_ENTITY_PROJECTS.FUNDING_ENTITY_ID))
            .leftJoin(PROJECTS)
            .on(FUNDING_ENTITY_PROJECTS.PROJECT_ID.eq(PROJECTS.ID))
            .apply { condition?.let { where(it) } }
            .orderBy(FUNDING_ENTITIES.ID, PROJECTS.NAME)
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
                      .filter { it[PROJECTS.ID] != null }
                      .map { record ->
                        val projectId = record[PROJECTS.ID]!!
                        val variableValues =
                            acceleratorProjectVariableValuesService.fetchValues(projectId)
                        ProjectAcceleratorDetailsModel(
                            projectId = projectId,
                            dealName = variableValues.dealName,
                        )
                      })
        }
  }
}
