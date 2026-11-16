package com.terraformation.backend.auth.zerotap.db

import com.terraformation.backend.auth.zerotap.model.RestoreCredentialModel
import com.terraformation.backend.customer.event.UserDeletionStartedEvent
import com.terraformation.backend.db.default_schema.RestoreCredentialId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.RESTORE_CREDENTIALS
import com.terraformation.backend.db.default_schema.tables.references.RESTORE_CREDENTIAL_CHALLENGES
import com.terraformation.backend.db.default_schema.tables.references.USERS
import jakarta.inject.Named
import java.time.Duration
import java.time.InstantSource
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

/**
 * Stores Android restore credentials and the single-use WebAuthn challenges used to register and
 * authenticate with them.
 */
@Named
class RestoreCredentialStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  /** @param userId The user registering a credential, or null for an authentication challenge. */
  fun createChallenge(challenge: ByteArray, userId: UserId?, lifetime: Duration) {
    with(RESTORE_CREDENTIAL_CHALLENGES) {
      dslContext
          .insertInto(RESTORE_CREDENTIAL_CHALLENGES)
          .set(CHALLENGE, challenge)
          .set(EXPIRES_TIME, clock.instant().plus(lifetime))
          .set(USER_ID, userId)
          .execute()
    }
  }

  /**
   * Deletes a challenge so it can't be used again.
   *
   * @return true if the challenge existed, hadn't expired, and was issued to the same user (or to
   *   no user, if [userId] is null).
   */
  fun consumeChallenge(challenge: ByteArray, userId: UserId?): Boolean {
    return with(RESTORE_CREDENTIAL_CHALLENGES) {
      dslContext
          .deleteFrom(RESTORE_CREDENTIAL_CHALLENGES)
          .where(CHALLENGE.eq(challenge))
          .and(USER_ID.isNotDistinctFrom(userId))
          .and(EXPIRES_TIME.gt(clock.instant()))
          .execute() == 1
    }
  }

  @EventListener
  fun on(event: UserDeletionStartedEvent) {
    deleteByUserId(event.userId)
    dslContext
        .deleteFrom(RESTORE_CREDENTIAL_CHALLENGES)
        .where(RESTORE_CREDENTIAL_CHALLENGES.USER_ID.eq(event.userId))
        .execute()
  }

  fun deleteExpiredChallenges() {
    with(RESTORE_CREDENTIAL_CHALLENGES) {
      dslContext
          .deleteFrom(RESTORE_CREDENTIAL_CHALLENGES)
          .where(EXPIRES_TIME.le(clock.instant()))
          .execute()
    }
  }

  /**
   * Stores a newly-registered credential. If the user already has a credential with the same ID, it
   * is replaced, since a device only has one restore credential per app.
   *
   * @throws IllegalArgumentException The credential ID is registered to a different user.
   */
  fun createCredential(
      userId: UserId,
      credentialId: ByteArray,
      attestedCredentialData: ByteArray,
      signCount: Long,
  ): RestoreCredentialId {
    return with(RESTORE_CREDENTIALS) {
      dslContext
          .insertInto(RESTORE_CREDENTIALS)
          .set(ATTESTED_CREDENTIAL_DATA, attestedCredentialData)
          .set(CREATED_TIME, clock.instant())
          .set(CREDENTIAL_ID, credentialId)
          .set(SIGN_COUNT, signCount)
          .set(USER_ID, userId)
          .onConflict(CREDENTIAL_ID)
          .doUpdate()
          .set(ATTESTED_CREDENTIAL_DATA, attestedCredentialData)
          .set(CREATED_TIME, clock.instant())
          .set(SIGN_COUNT, signCount)
          .where(USER_ID.eq(userId))
          .returning(ID)
          .fetchOne(ID) ?: throw IllegalArgumentException("Credential belongs to another user")
    }
  }

  /** Returns a credential if it exists and its user hasn't been deleted. */
  fun fetchByCredentialId(credentialId: ByteArray): RestoreCredentialModel? {
    return dslContext
        .select(
            RESTORE_CREDENTIALS.ATTESTED_CREDENTIAL_DATA,
            RESTORE_CREDENTIALS.SIGN_COUNT,
            RESTORE_CREDENTIALS.USER_ID,
            USERS.AUTH_ID,
        )
        .from(RESTORE_CREDENTIALS)
        .join(USERS)
        .on(RESTORE_CREDENTIALS.USER_ID.eq(USERS.ID))
        .where(RESTORE_CREDENTIALS.CREDENTIAL_ID.eq(credentialId))
        .and(USERS.DELETED_TIME.isNull)
        .and(USERS.AUTH_ID.isNotNull)
        .fetchOne { record ->
          RestoreCredentialModel(
              attestedCredentialData = record[RESTORE_CREDENTIALS.ATTESTED_CREDENTIAL_DATA]!!,
              authId = record[USERS.AUTH_ID]!!,
              signCount = record[RESTORE_CREDENTIALS.SIGN_COUNT]!!,
              userId = record[RESTORE_CREDENTIALS.USER_ID]!!,
          )
        }
  }

  /** @return true if the credential existed. */
  fun deleteCredential(credentialId: ByteArray): Boolean {
    return dslContext
        .deleteFrom(RESTORE_CREDENTIALS)
        .where(RESTORE_CREDENTIALS.CREDENTIAL_ID.eq(credentialId))
        .execute() == 1
  }

  fun deleteByUserId(userId: UserId) {
    dslContext
        .deleteFrom(RESTORE_CREDENTIALS)
        .where(RESTORE_CREDENTIALS.USER_ID.eq(userId))
        .execute()
  }
}
