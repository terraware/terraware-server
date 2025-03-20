package com.terraformation.backend.funder

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.UserDeletionStartedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.pojos.FundingEntitiesRow
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_USERS
import com.terraformation.backend.funder.db.EmailExistsException
import com.terraformation.backend.funder.db.FundingEntityExistsException
import com.terraformation.backend.funder.db.FundingEntityNotFoundException
import com.terraformation.backend.funder.db.FundingEntityStore
import com.terraformation.backend.funder.db.FundingEntityUserStore
import com.terraformation.backend.funder.event.FunderInvitedToFundingEntityEvent
import com.terraformation.backend.funder.model.FundingEntityModel
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.dao.DuplicateKeyException

@Named
class FundingEntityService(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val fundingEntityStore: FundingEntityStore,
    private val fundingEntityUserStore: FundingEntityUserStore,
    private val publisher: ApplicationEventPublisher,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  fun create(name: String, projects: Set<ProjectId>? = null): FundingEntityModel {
    requirePermissions { createFundingEntities() }

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

      setProjectsForEntity(fundingEntityId, projects.orEmpty())

      fundingEntityStore.fetchOneById(fundingEntityId)
    }
  }

  fun update(
      row: FundingEntitiesRow,
      projects: Set<ProjectId>? = null,
  ) {
    val fundingEntityId = row.id ?: throw IllegalArgumentException("Funding Entity ID must be set")

    requirePermissions { updateFundingEntities() }

    val userId = currentUser().userId
    val now = clock.instant()

    dslContext.transaction { _ ->
      try {
        val updatedRows =
            with(FUNDING_ENTITIES) {
              dslContext
                  .update(FUNDING_ENTITIES)
                  .set(NAME, row.name)
                  .set(MODIFIED_BY, userId)
                  .set(MODIFIED_TIME, now)
                  .where(ID.eq(fundingEntityId))
                  .execute()
            }

        if (updatedRows == 0) {
          throw FundingEntityNotFoundException(fundingEntityId)
        }
      } catch (e: DuplicateKeyException) {
        throw FundingEntityExistsException(row.name!!)
      }

      if (projects != null) {
        setProjectsForEntity(fundingEntityId, projects)
      }
    }
  }

  fun deleteFundingEntity(fundingEntityId: FundingEntityId) {
    requirePermissions { deleteFundingEntities() }

    log.info("Deleting funding entity $fundingEntityId")

    dslContext.transaction { _ ->
      val funders = userStore.fetchByFundingEntityId(fundingEntityId)
      funders.forEach { userStore.deleteFunderById(it.userId) }

      // no need to remove users from funding_entity_users table because funding_entities performs
      // cascade deletes

      val deletedRows =
          dslContext
              .deleteFrom(FUNDING_ENTITIES)
              .where(FUNDING_ENTITIES.ID.eq(fundingEntityId))
              .execute()

      if (deletedRows == 0) {
        throw FundingEntityNotFoundException(fundingEntityId)
      }
    }
  }

  fun inviteFunder(fundingEntityId: FundingEntityId, email: String) {
    requirePermissions { updateFundingEntityUsers(fundingEntityId) }

    val existingUser = userStore.fetchTerrawareUserByEmail(email)
    if (existingUser != null) {
      if (existingUser.userType == UserType.Funder) {
        val existingUserEntityId = fundingEntityUserStore.getFundingEntityId(existingUser.userId)
        // If the user already belongs to a different Funding Entity or has already registered,
        // throw an error
        if (existingUserEntityId != fundingEntityId || existingUser.authId != null) {
          throw EmailExistsException(email)
        }
      } else {
        throw EmailExistsException(email)
      }
    }

    dslContext.transactionResult { _ ->
      // the checks above should ensure that we throw an error if the email is already used
      // incorrectly. If the user exists at this point, then we just need to republish the event,
      // but don't need to recreate the user.
      if (existingUser == null) {
        val funderUser = userStore.createFunderUser(email)
        addFunderToEntity(fundingEntityId, funderUser.userId)
      }

      publisher.publishEvent(
          FunderInvitedToFundingEntityEvent(
              email = email,
              fundingEntityId = fundingEntityId,
          ),
      )
    }
  }

  @EventListener
  fun on(event: UserDeletionStartedEvent) {
    val fundingEntityId = fundingEntityUserStore.getFundingEntityId(event.userId)

    if (fundingEntityId != null) {
      removeFunderFromEntity(fundingEntityId, event.userId)
    }
  }

  private fun setProjectsForEntity(fundingEntityId: FundingEntityId, projectIds: Set<ProjectId>) {
    requirePermissions { updateFundingEntityProjects() }

    with(FUNDING_ENTITY_PROJECTS) {
      dslContext
          .deleteFrom(FUNDING_ENTITY_PROJECTS)
          .where(FUNDING_ENTITY_ID.eq(fundingEntityId))
          .and(PROJECT_ID.notIn(projectIds))
          .execute()
    }

    for (projectId in projectIds) {
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

  private fun addFunderToEntity(fundingEntityId: FundingEntityId, userId: UserId) {
    with(FUNDING_ENTITY_USERS) {
      dslContext
          .insertInto(FUNDING_ENTITY_USERS)
          .set(FUNDING_ENTITY_ID, fundingEntityId)
          .set(USER_ID, userId)
          .onConflict()
          .doNothing()
          .execute()
    }
  }

  private fun removeFunderFromEntity(fundingEntityId: FundingEntityId, userId: UserId) {
    dslContext
        .deleteFrom(FUNDING_ENTITY_USERS)
        .where(FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID.eq(fundingEntityId))
        .and(FUNDING_ENTITY_USERS.USER_ID.eq(userId))
        .execute()
  }
}
