package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.DisclaimerModel
import com.terraformation.backend.customer.model.UserDisclaimerModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.DisclaimerNotFoundException
import com.terraformation.backend.db.default_schema.DisclaimerId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.daos.DisclaimersDao
import com.terraformation.backend.db.default_schema.tables.daos.UserDisclaimersDao
import com.terraformation.backend.db.default_schema.tables.pojos.DisclaimersRow
import com.terraformation.backend.db.default_schema.tables.pojos.UserDisclaimersRow
import com.terraformation.backend.db.default_schema.tables.references.DISCLAIMERS
import com.terraformation.backend.db.default_schema.tables.references.USER_DISCLAIMERS
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.Instant
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class DisclaimerStore(
    private val clock: InstantSource,
    private val disclaimersDao: DisclaimersDao,
    private val dslContext: DSLContext,
    private val userDisclaimersDao: UserDisclaimersDao,
) {
  private val log = perClassLogger()

  fun createDisclaimer(content: String, effectiveOn: Instant): DisclaimerId {
    requirePermissions { manageDisclaimers() }

    val row =
        DisclaimersRow(
            content = content,
            effectiveOn = effectiveOn,
        )

    disclaimersDao.insert(row)
    return row.id!!
  }

  fun deleteDisclaimer(disclaimerId: DisclaimerId) {
    requirePermissions { manageDisclaimers() }

    disclaimersDao.deleteById(disclaimerId)
  }

  fun deleteDisclaimerAcceptance(disclaimerId: DisclaimerId, userId: UserId) {
    requirePermissions { manageDisclaimers() }

    dslContext
        .deleteFrom(USER_DISCLAIMERS)
        .where(USER_DISCLAIMERS.USER_ID.eq(userId))
        .and(USER_DISCLAIMERS.DISCLAIMER_ID.eq(disclaimerId))
        .execute()
  }

  fun fetchAllDisclaimers(): List<DisclaimerModel> {
    requirePermissions { manageDisclaimers() }
    return fetchDisclaimersByCondition(DSL.trueCondition())
  }

  fun fetchOneDisclaimer(disclaimerId: DisclaimerId): DisclaimerModel {
    requirePermissions { manageDisclaimers() }
    return fetchDisclaimersByCondition(DISCLAIMERS.ID.eq(disclaimerId)).firstOrNull()
        ?: throw DisclaimerNotFoundException(disclaimerId)
  }

  /**
   * Fetch the latest effective disclaimer. Omits future disclaimers. Null if no effective
   * disclaimer exists.
   */
  fun fetchCurrentDisclaimer(): UserDisclaimerModel? {
    requirePermissions { readCurrentDisclaimer() }

    return with(DISCLAIMERS) {
      dslContext
          .select(
              ID,
              CONTENT,
              EFFECTIVE_ON,
              USER_DISCLAIMERS.ACCEPTED_ON,
          )
          .from(this)
          .leftJoin(USER_DISCLAIMERS)
          .on(USER_DISCLAIMERS.DISCLAIMER_ID.eq(ID))
          .and(USER_DISCLAIMERS.USER_ID.eq(currentUser().userId))
          .where(DISCLAIMERS.EFFECTIVE_ON.le(clock.instant()))
          .orderBy(EFFECTIVE_ON.desc())
          .limit(1)
          .fetchOne { UserDisclaimerModel.of(it) }
    }
  }

  /** Accepts the disclaimer */
  fun acceptCurrentDisclaimer() {
    requirePermissions { acceptCurrentDisclaimer() }

    val currentDisclaimer = fetchCurrentDisclaimer()

    if (currentDisclaimer == null) {
      throw IllegalStateException("No current disclaimer exists.")
    }

    if (currentDisclaimer.acceptedOn != null) {
      log.warn(
          "Disclaimer ${currentDisclaimer.id} has already been accepted by user " +
              "${currentUser().userId}")
      return
    }

    userDisclaimersDao.insert(
        UserDisclaimersRow(
            disclaimerId = currentDisclaimer.id,
            userId = currentUser().userId,
            acceptedOn = clock.instant(),
        ))
  }

  private fun fetchDisclaimersByCondition(condition: Condition): List<DisclaimerModel> {
    val usersMultiset =
        with(USER_DISCLAIMERS) {
          DSL.multiset(
                  DSL.select(
                          USER_ID,
                          ACCEPTED_ON,
                      )
                      .from(this)
                      .where(DISCLAIMER_ID.eq(DISCLAIMERS.ID)))
              .convertFrom { result ->
                result.associate { record -> record[USER_ID]!! to record[ACCEPTED_ON]!! }
              }
        }

    return with(DISCLAIMERS) {
      dslContext
          .select(
              ID,
              CONTENT,
              EFFECTIVE_ON,
              usersMultiset,
          )
          .from(this)
          .where(condition)
          .orderBy(EFFECTIVE_ON.desc(), ID)
          .fetch { DisclaimerModel.of(it, usersMultiset) }
    }
  }
}
