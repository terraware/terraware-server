package com.terraformation.backend.customer.db

import com.terraformation.backend.api.DuplicateNameException
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.WithdrawalCustomPurposeModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.WithdrawalCustomPurposeNotFoundException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.WithdrawalCustomPurposeId
import com.terraformation.backend.db.default_schema.WithdrawalCustomPurposeType
import com.terraformation.backend.db.default_schema.tables.references.WITHDRAWAL_CUSTOM_PURPOSES
import jakarta.inject.Named
import java.time.Clock
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException

@Named
class WithdrawalCustomPurposeHelper(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val parentFetcher: ParentFetcher,
) {
  fun fetchNameById(customPurposeId: WithdrawalCustomPurposeId): String? =
      dslContext
          .select(WITHDRAWAL_CUSTOM_PURPOSES.NAME)
          .from(WITHDRAWAL_CUSTOM_PURPOSES)
          .where(WITHDRAWAL_CUSTOM_PURPOSES.ID.eq(customPurposeId))
          .fetchOne(WITHDRAWAL_CUSTOM_PURPOSES.NAME)

  /**
   * Resolves a saved purpose after checking permission to create custom purposes in the
   * organization.
   */
  fun findOrCreate(
      organizationId: OrganizationId,
      type: WithdrawalCustomPurposeType,
      name: String,
  ): WithdrawalCustomPurposeId {
    requirePermissions { createWithdrawalCustomPurpose(organizationId) }

    val trimmedName = name.trim()
    val now = clock.instant()
    val userId = currentUser().userId
    return with(WITHDRAWAL_CUSTOM_PURPOSES) {
      dslContext
          .insertInto(this)
          .set(CREATED_BY, userId)
          .set(CREATED_TIME, now)
          .set(MODIFIED_BY, userId)
          .set(MODIFIED_TIME, now)
          .set(NAME, trimmedName)
          .set(TYPE_ID, type)
          .set(ORGANIZATION_ID, organizationId)
          .onConflict(ORGANIZATION_ID, TYPE_ID, NAME)
          .doUpdate()
          .set(IS_ARCHIVED, false)
          .set(MODIFIED_BY, userId)
          .set(MODIFIED_TIME, now)
          .where(IS_ARCHIVED.isTrue)
          .returning(ID)
          .fetchOne(ID)
          ?: dslContext
              .select(ID)
              .from(this)
              .where(ORGANIZATION_ID.eq(organizationId))
              .and(TYPE_ID.eq(type))
              .and(NAME.eq(trimmedName))
              .fetchSingle(ID)!!
    }
  }

  fun fetchByOrganizationId(
      organizationId: OrganizationId,
      type: WithdrawalCustomPurposeType,
      includeArchived: Boolean = false,
  ): List<WithdrawalCustomPurposeModel> {
    requirePermissions { readWithdrawalCustomPurposes(organizationId) }

    return with(WITHDRAWAL_CUSTOM_PURPOSES) {
      dslContext
          .selectFrom(this)
          .where(ORGANIZATION_ID.eq(organizationId))
          .and(TYPE_ID.eq(type))
          .and(if (includeArchived) DSL.noCondition() else IS_ARCHIVED.isFalse)
          .orderBy(NAME)
          .fetch {
            WithdrawalCustomPurposeModel(
                id = it[ID]!!,
                isArchived = it[IS_ARCHIVED]!!,
                name = it[NAME]!!,
            )
          }
    }
  }

  /** Renames or archives a saved purpose. */
  fun update(
      customPurposeId: WithdrawalCustomPurposeId,
      isArchived: Boolean? = null,
      name: String? = null,
  ) {
    val organizationId =
        parentFetcher.getOrganizationId(customPurposeId)
            ?: throw WithdrawalCustomPurposeNotFoundException(customPurposeId)
    requirePermissions { updateWithdrawalCustomPurpose(customPurposeId, organizationId) }

    val trimmedName = name?.trim()
    require(trimmedName == null || trimmedName.length in 1..50) {
      "Custom purpose name must be between 1 and 50 characters"
    }

    try {
      dslContext.transaction { _ ->
        with(WITHDRAWAL_CUSTOM_PURPOSES) {
          val count =
              dslContext
                  .update(this)
                  .set(MODIFIED_BY, currentUser().userId)
                  .set(MODIFIED_TIME, clock.instant())
                  .apply {
                    isArchived?.let { set(IS_ARCHIVED, it) }
                    trimmedName?.let { set(NAME, it) }
                  }
                  .where(ID.eq(customPurposeId))
                  .execute()
          if (count == 0) {
            throw WithdrawalCustomPurposeNotFoundException(customPurposeId)
          }
        }
      }
    } catch (e: DuplicateKeyException) {
      throw DuplicateNameException("A withdrawal custom purpose with that name already exists")
    }
  }
}
