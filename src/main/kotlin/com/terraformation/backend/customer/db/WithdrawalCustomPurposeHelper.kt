package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.WithdrawalCustomPurposeId
import com.terraformation.backend.db.default_schema.WithdrawalCustomPurposeType
import com.terraformation.backend.db.default_schema.tables.references.WITHDRAWAL_CUSTOM_PURPOSES
import jakarta.inject.Named
import java.time.Clock
import org.jooq.DSLContext

@Named
class WithdrawalCustomPurposeHelper(
    private val clock: Clock,
    private val dslContext: DSLContext,
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
}
