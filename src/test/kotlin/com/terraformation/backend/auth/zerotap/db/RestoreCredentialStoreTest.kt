package com.terraformation.backend.auth.zerotap.db

import com.terraformation.backend.customer.event.UserDeletionStartedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.tables.records.RestoreCredentialChallengesRecord
import com.terraformation.backend.db.default_schema.tables.records.RestoreCredentialsRecord
import com.terraformation.backend.db.default_schema.tables.references.RESTORE_CREDENTIALS
import com.terraformation.backend.db.default_schema.tables.references.RESTORE_CREDENTIAL_CHALLENGES
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

class RestoreCredentialStoreTest : DatabaseTest() {
  private val store: RestoreCredentialStore by lazy { RestoreCredentialStore(clock, dslContext) }

  private val challenge = byteArrayOf(1, 2, 3)
  private val credentialId = byteArrayOf(4, 5, 6)

  @BeforeEach
  fun setUp() {
    insertUser(authId = "auth-id")
  }

  @Nested
  inner class Challenges {
    @Test
    fun `createChallenge inserts challenge with expiration time`() {
      store.createChallenge(challenge, inserted.userId, Duration.ofSeconds(30))

      assertTableEquals(
          RestoreCredentialChallengesRecord(
              challenge = challenge,
              expiresTime = clock.instant().plusSeconds(30),
              userId = inserted.userId,
          )
      )
    }

    @Test
    fun `consumeChallenge only accepts unexpired challenges for the same user once`() {
      val otherUserId = insertUser()
      val expiredChallenge = byteArrayOf(7)
      val userChallenge = byteArrayOf(8)

      insertRestoreCredentialChallenge(challenge = challenge)
      insertRestoreCredentialChallenge(
          challenge = expiredChallenge,
          expiresTime = clock.instant(),
      )
      insertRestoreCredentialChallenge(challenge = userChallenge, userId = otherUserId)

      assertAll(
          { assertEquals(false, store.consumeChallenge(expiredChallenge, null), "Expired") },
          { assertEquals(false, store.consumeChallenge(userChallenge, null), "Wrong user") },
          { assertEquals(false, store.consumeChallenge(byteArrayOf(9), null), "Nonexistent") },
          { assertEquals(true, store.consumeChallenge(challenge, null), "First use") },
          { assertEquals(false, store.consumeChallenge(challenge, null), "Second use") },
          { assertEquals(true, store.consumeChallenge(userChallenge, otherUserId), "Right user") },
      )
    }

    @Test
    fun `deleteExpiredChallenges only deletes expired challenges`() {
      insertRestoreCredentialChallenge(expiresTime = clock.instant())
      insertRestoreCredentialChallenge(challenge = challenge)

      store.deleteExpiredChallenges()

      assertTableEquals(
          RestoreCredentialChallengesRecord(
              challenge = challenge,
              expiresTime = clock.instant().plusSeconds(60),
          )
      )
    }
  }

  @Nested
  inner class Credentials {
    @Test
    fun `createCredential inserts credential`() {
      val restoreCredentialId =
          store.createCredential(inserted.userId, credentialId, byteArrayOf(10), 5)

      assertTableEquals(
          RestoreCredentialsRecord(
              attestedCredentialData = byteArrayOf(10),
              createdTime = clock.instant(),
              credentialId = credentialId,
              id = restoreCredentialId,
              signCount = 5,
              userId = inserted.userId,
          )
      )
    }

    @Test
    fun `createCredential replaces user's existing credential with the same ID`() {
      val restoreCredentialId =
          insertRestoreCredential(
              credentialId = credentialId,
              attestedCredentialData = byteArrayOf(1),
              createdTime = clock.instant().minusSeconds(60),
              signCount = 1,
          )

      store.createCredential(inserted.userId, credentialId, byteArrayOf(10), 5)

      assertTableEquals(
          RestoreCredentialsRecord(
              attestedCredentialData = byteArrayOf(10),
              createdTime = clock.instant(),
              credentialId = credentialId,
              id = restoreCredentialId,
              signCount = 5,
              userId = inserted.userId,
          )
      )
    }

    @Test
    fun `createCredential rejects credential ID registered to another user`() {
      insertRestoreCredential(credentialId = credentialId)
      val expected = dslContext.fetch(RESTORE_CREDENTIALS)
      val otherUserId = insertUser()

      assertThrows<IllegalArgumentException> {
        store.createCredential(otherUserId, credentialId, byteArrayOf(10), 5)
      }

      assertTableEquals(expected)
    }

    @Test
    fun `fetchByCredentialId returns credential with user's auth ID`() {
      insertRestoreCredential(
          credentialId = credentialId,
          attestedCredentialData = byteArrayOf(10),
          signCount = 5,
      )

      val credential = store.fetchByCredentialId(credentialId)!!

      assertArrayEquals(byteArrayOf(10), credential.attestedCredentialData, "Credential data")
      assertEquals(
          listOf("auth-id", 5L, inserted.userId),
          listOf(credential.authId, credential.signCount, credential.userId),
          "Auth ID, sign count, and user ID",
      )
    }

    @Test
    fun `fetchByCredentialId returns null for unknown credentials and deleted users`() {
      insertUser(deletedTime = clock.instant())
      val deletedUserCredentialId = byteArrayOf(11)
      insertRestoreCredential(credentialId = deletedUserCredentialId)

      assertAll(
          { assertNull(store.fetchByCredentialId(credentialId), "Unknown credential") },
          { assertNull(store.fetchByCredentialId(deletedUserCredentialId), "Deleted user") },
      )
    }

    @Test
    fun `deleteByUserId deletes user's credentials but not other users'`() {
      val userId = inserted.userId
      insertRestoreCredential()
      insertUser()
      insertRestoreCredential()
      val expected = dslContext.fetch(RESTORE_CREDENTIALS).filter { it.userId != userId }

      store.deleteByUserId(userId)

      assertTableEquals(expected)
    }

    @Test
    fun `deleteCredential only deletes the credential once`() {
      insertRestoreCredential(credentialId = credentialId)
      val otherCredentialId = insertRestoreCredential()
      val expected = dslContext.fetch(RESTORE_CREDENTIALS).filter { it.id == otherCredentialId }

      assertAll(
          { assertEquals(true, store.deleteCredential(credentialId), "First deletion") },
          { assertEquals(false, store.deleteCredential(credentialId), "Second deletion") },
      )

      assertTableEquals(expected)
    }
  }

  @Nested
  inner class UserDeletion {
    @Test
    fun `deletes user's credentials and challenges but not other users'`() {
      val deletedUserId = inserted.userId
      insertRestoreCredential()
      insertRestoreCredentialChallenge(userId = deletedUserId)

      insertUser()
      insertRestoreCredential()
      insertRestoreCredentialChallenge(userId = inserted.userId)
      val expectedCredentials =
          dslContext.fetch(RESTORE_CREDENTIALS).filter { it.userId != deletedUserId }
      val expectedChallenges =
          dslContext.fetch(RESTORE_CREDENTIAL_CHALLENGES).filter { it.userId != deletedUserId }

      store.on(UserDeletionStartedEvent(deletedUserId))

      assertTableEquals(expectedCredentials, "Credentials")
      assertTableEquals(expectedChallenges, "Challenges")
    }
  }
}
