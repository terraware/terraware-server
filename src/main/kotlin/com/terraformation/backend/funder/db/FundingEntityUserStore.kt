package com.terraformation.backend.funder.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_USERS
import com.terraformation.backend.funder.model.FunderUserModel
import com.terraformation.backend.funder.model.FundingEntityModel
import com.terraformation.backend.funder.model.FundingProjectModel
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class FundingEntityUserStore(private val dslContext: DSLContext) {
  fun getFundingEntityId(userId: UserId): FundingEntityId? {
    requirePermissions { readUser(userId) }

    return with(FUNDING_ENTITY_USERS) {
      dslContext
          .select(FUNDING_ENTITY_ID)
          .from(FUNDING_ENTITY_USERS)
          .where(USER_ID.eq(userId))
          .fetchOne(FUNDING_ENTITY_ID)
    }
  }

  fun fetchEntityByUserId(userId: UserId): FundingEntityModel? {
    requirePermissions { readUser(userId) }

    val projectsMultiset =
        DSL.multiset(
                DSL.select(
                        FUNDING_ENTITY_PROJECTS.PROJECT_ID,
                        PROJECT_ACCELERATOR_DETAILS.DEAL_NAME,
                        PROJECTS.NAME,
                    )
                    .from(FUNDING_ENTITY_PROJECTS)
                    .join(PROJECTS)
                    .on(FUNDING_ENTITY_PROJECTS.PROJECT_ID.eq(PROJECTS.ID))
                    .leftJoin(PROJECT_ACCELERATOR_DETAILS)
                    .on(
                        FUNDING_ENTITY_PROJECTS.PROJECT_ID.eq(
                            PROJECT_ACCELERATOR_DETAILS.PROJECT_ID
                        )
                    )
                    .where(
                        FUNDING_ENTITY_PROJECTS.FUNDING_ENTITY_ID.eq(
                            FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID
                        )
                    )
                    .orderBy(FUNDING_ENTITY_PROJECTS.PROJECT_ID)
            )
            .convertFrom { result ->
              result.map { record ->
                FundingProjectModel(
                    dealName =
                        record[PROJECT_ACCELERATOR_DETAILS.DEAL_NAME] ?: record[PROJECTS.NAME]!!,
                    projectId = record[FUNDING_ENTITY_PROJECTS.PROJECT_ID]!!,
                )
              }
            }

    return dslContext
        .select(FUNDING_ENTITIES.asterisk(), projectsMultiset)
        .from(FUNDING_ENTITY_USERS)
        .join(FUNDING_ENTITIES)
        .on(FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID.eq(FUNDING_ENTITIES.ID))
        .where(FUNDING_ENTITY_USERS.USER_ID.eq(userId))
        .fetchOne { FundingEntityModel.of(it, projectsMultiset) }
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
              users.AUTH_ID.isNotNull,
          )
          .from(this)
          .where(FUNDING_ENTITY_ID.eq(entityId))
          .and(users.DELETED_TIME.isNull)
          .orderBy(users.ID)
          .fetch { FunderUserModel.of(it) }
    }
  }
}
