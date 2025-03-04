package com.terraformation.backend.funder

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.pojos.FundingEntitiesRow
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.funder.db.FundingEntityExistsException
import com.terraformation.backend.funder.db.FundingEntityStore
import com.terraformation.backend.funder.model.FundingEntityModel
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext
import org.springframework.dao.DuplicateKeyException

@Named
class FundingEntityService(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val fundingEntityStore: FundingEntityStore,
) {
  private val log = perClassLogger()

  fun create(name: String, projects: Set<ProjectId>? = null): FundingEntityModel {
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

      addProjectsToEntity(fundingEntityId, projects.orEmpty())

      fundingEntityStore.fetchOneById(fundingEntityId)
    }
  }

  fun update(
      row: FundingEntitiesRow,
      addProjects: Set<ProjectId>? = null,
      removeProjects: Set<ProjectId>? = null,
  ) {
    val fundingEntityId = row.id ?: throw IllegalArgumentException("Funding Entity ID must be set")

    requirePermissions { manageFundingEntities() }

    val userId = currentUser().userId
    val now = clock.instant()

    dslContext.transaction { _ ->
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

      removeProjectsFromEntity(fundingEntityId, removeProjects.orEmpty())
      addProjectsToEntity(fundingEntityId, addProjects.orEmpty())
    }
  }

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

  private fun addProjectsToEntity(fundingEntityId: FundingEntityId, projects: Set<ProjectId>) {
    for (projectId in projects) {
      with(FUNDING_ENTITY_PROJECTS) {
        dslContext
            .insertInto(FUNDING_ENTITY_PROJECTS)
            .set(FUNDING_ENTITY_ID, fundingEntityId)
            .set(PROJECT_ID, projectId)
            .onConflict()
            .doNothing()
            .execute()
      }
    }
  }

  private fun removeProjectsFromEntity(fundingEntityId: FundingEntityId, projects: Set<ProjectId>) {
    for (projectId in projects) {
      with(FUNDING_ENTITY_PROJECTS) {
        dslContext
            .deleteFrom(FUNDING_ENTITY_PROJECTS)
            .where(FUNDING_ENTITY_ID.eq(fundingEntityId))
            .and(PROJECT_ID.eq(projectId))
            .execute()
      }
    }
  }
}
