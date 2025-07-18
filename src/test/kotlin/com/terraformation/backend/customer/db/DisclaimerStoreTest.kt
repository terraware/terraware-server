package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.DisclaimerModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.UserDisclaimerModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.DisclaimerNotFoundException
import com.terraformation.backend.db.default_schema.DisclaimerId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.records.DisclaimersRecord
import com.terraformation.backend.db.default_schema.tables.records.UserDisclaimersRecord
import com.terraformation.backend.db.default_schema.tables.references.DISCLAIMERS
import com.terraformation.backend.db.default_schema.tables.references.USER_DISCLAIMERS
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class DisclaimerStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val store: DisclaimerStore by lazy {
    DisclaimerStore(clock, disclaimersDao, dslContext, userDisclaimersDao)
  }

  private val funderUserId: UserId by lazy { insertUser(type = UserType.Funder) }

  @Nested
  inner class CreateDisclaimer {
    @Test
    fun `inserts a disclaimer record`() {
      insertUserGlobalRole(currentUser().userId, GlobalRole.SuperAdmin)

      val existingDisclaimerId =
          insertDisclaimer(
              content = "Existing Disclaimer",
              effectiveOn = Instant.ofEpochSecond(3000),
          )

      val newDisclaimerId =
          store.createDisclaimer(
              content = "New Disclaimer",
              effectiveOn = Instant.ofEpochSecond(6000),
          )

      assertTableEquals(
          listOf(
              DisclaimersRecord(
                  id = existingDisclaimerId,
                  content = "Existing Disclaimer",
                  effectiveOn = Instant.ofEpochSecond(3000),
              ),
              DisclaimersRecord(
                  id = newDisclaimerId,
                  content = "New Disclaimer",
                  effectiveOn = Instant.ofEpochSecond(6000),
              ),
          ))
    }

    @Test
    fun `throws exception for non-super-admins`() {
      assertThrows<AccessDeniedException> {
        store.createDisclaimer(
            content = "New Disclaimer",
            effectiveOn = Instant.ofEpochSecond(6000),
        )
      }
    }
  }

  @Nested
  inner class DeleteDisclaimer {
    @Test
    fun `deletes a disclaimer record`() {
      insertUserGlobalRole(currentUser().userId, GlobalRole.SuperAdmin)

      val existingDisclaimerId =
          insertDisclaimer(
              content = "Existing Disclaimer",
              effectiveOn = Instant.ofEpochSecond(3000),
          )

      store.deleteDisclaimer(existingDisclaimerId)

      assertTableEmpty(DISCLAIMERS)
    }

    @Test
    fun `throws exception for non-super-admins`() {
      val existingDisclaimerId =
          insertDisclaimer(
              content = "Existing Disclaimer",
              effectiveOn = Instant.ofEpochSecond(3000),
          )

      assertThrows<AccessDeniedException> { store.deleteDisclaimer(existingDisclaimerId) }
    }
  }

  @Nested
  inner class DeleteDisclaimerAcceptance {
    @Test
    fun `deletes a user disclaimer record`() {
      insertUserGlobalRole(currentUser().userId, GlobalRole.SuperAdmin)

      val disclaimerId1 =
          insertDisclaimer(
              content = "Disclaimer 1",
              effectiveOn = Instant.ofEpochSecond(3000),
          )
      val disclaimerId2 =
          insertDisclaimer(
              content = "Disclaimer 2",
              effectiveOn = Instant.ofEpochSecond(6000),
          )

      val funderUserId1 = insertUser(type = UserType.Funder)
      insertUserDisclaimer(disclaimerId = disclaimerId1, acceptedOn = Instant.ofEpochSecond(3001))
      insertUserDisclaimer(disclaimerId = disclaimerId2, acceptedOn = Instant.ofEpochSecond(6001))
      val funderUserId2 = insertUser(type = UserType.Funder)
      insertUserDisclaimer(disclaimerId = disclaimerId1, acceptedOn = Instant.ofEpochSecond(3002))

      store.deleteDisclaimerAcceptance(disclaimerId1, funderUserId1)

      assertTableEquals(
          listOf(
              UserDisclaimersRecord(
                  userId = funderUserId2,
                  disclaimerId = disclaimerId1,
                  acceptedOn = Instant.ofEpochSecond(3002)),
              UserDisclaimersRecord(
                  userId = funderUserId1,
                  disclaimerId = disclaimerId2,
                  acceptedOn = Instant.ofEpochSecond(6001))))
    }

    @Test
    fun `does nothing if disclaimer or user does not exist`() {
      insertUserGlobalRole(currentUser().userId, GlobalRole.SuperAdmin)
      store.deleteDisclaimerAcceptance(DisclaimerId(-1), UserId(-1))

      assertTableEmpty(USER_DISCLAIMERS)
    }

    @Test
    fun `throws exception for non-super-admins`() {
      val disclaimerId =
          insertDisclaimer(
              content = "Disclaimer",
              effectiveOn = Instant.ofEpochSecond(3000),
          )

      val funderUserId1 = insertUser(type = UserType.Funder)
      insertUserDisclaimer(disclaimerId = disclaimerId, acceptedOn = Instant.ofEpochSecond(3001))

      assertThrows<AccessDeniedException> {
        store.deleteDisclaimerAcceptance(disclaimerId, funderUserId1)
      }
    }
  }

  @Nested
  inner class FetchAllDisclaimers {
    @Test
    fun `returns list of disclaimers with user opt-in time`() {
      insertUserGlobalRole(currentUser().userId, GlobalRole.SuperAdmin)

      val disclaimerId1 =
          insertDisclaimer(
              content = "Disclaimer 1",
              effectiveOn = Instant.ofEpochSecond(9000),
          )

      val disclaimerId2 =
          insertDisclaimer(
              content = "Disclaimer 2",
              effectiveOn = Instant.ofEpochSecond(3000),
          )

      val disclaimerId3 =
          insertDisclaimer(
              content = "Disclaimer 3",
              effectiveOn = Instant.ofEpochSecond(6000),
          )

      val funderUserId1 = insertUser(type = UserType.Funder)
      insertUserDisclaimer(disclaimerId = disclaimerId1, acceptedOn = Instant.ofEpochSecond(9001))
      insertUserDisclaimer(disclaimerId = disclaimerId2, acceptedOn = Instant.ofEpochSecond(3001))

      val funderUserId2 = insertUser(type = UserType.Funder)
      insertUserDisclaimer(disclaimerId = disclaimerId1, acceptedOn = Instant.ofEpochSecond(9002))

      val funderUserId3 = insertUser(type = UserType.Funder)
      insertUserDisclaimer(disclaimerId = disclaimerId2, acceptedOn = Instant.ofEpochSecond(3002))

      insertUser(type = UserType.Funder)

      val expected =
          listOf(
              DisclaimerModel(
                  id = disclaimerId1,
                  content = "Disclaimer 1",
                  effectiveOn = Instant.ofEpochSecond(9000),
                  users =
                      mapOf(
                          funderUserId1 to Instant.ofEpochSecond(9001),
                          funderUserId2 to Instant.ofEpochSecond(9002),
                      )),
              DisclaimerModel(
                  id = disclaimerId3,
                  content = "Disclaimer 3",
                  effectiveOn = Instant.ofEpochSecond(6000),
                  users = emptyMap()),
              DisclaimerModel(
                  id = disclaimerId2,
                  content = "Disclaimer 2",
                  effectiveOn = Instant.ofEpochSecond(3000),
                  users =
                      mapOf(
                          funderUserId1 to Instant.ofEpochSecond(3001),
                          funderUserId3 to Instant.ofEpochSecond(3002),
                      )),
          )

      assertEquals(expected, store.fetchAllDisclaimers())
    }

    @Test
    fun `returns empty list for no disclaimers`() {
      insertUserGlobalRole(currentUser().userId, GlobalRole.SuperAdmin)
      assertEquals(emptyList<DisclaimerModel>(), store.fetchAllDisclaimers())
    }

    @Test
    fun `throws exception for non-super-admins`() {
      assertThrows<AccessDeniedException> { store.fetchAllDisclaimers() }
    }
  }

  @Nested
  inner class FetchOneDisclaimer {
    @Test
    fun `returns disclaimer with user opt-in time`() {
      insertUserGlobalRole(currentUser().userId, GlobalRole.SuperAdmin)

      val disclaimerId1 =
          insertDisclaimer(
              content = "Disclaimer 1",
              effectiveOn = Instant.ofEpochSecond(9000),
          )

      val disclaimerId2 =
          insertDisclaimer(
              content = "Disclaimer 2",
              effectiveOn = Instant.ofEpochSecond(3000),
          )

      val disclaimerId3 =
          insertDisclaimer(
              content = "Disclaimer 3",
              effectiveOn = Instant.ofEpochSecond(6000),
          )

      val funderUserId1 = insertUser(type = UserType.Funder)
      insertUserDisclaimer(disclaimerId = disclaimerId1, acceptedOn = Instant.ofEpochSecond(9001))
      insertUserDisclaimer(disclaimerId = disclaimerId2, acceptedOn = Instant.ofEpochSecond(3001))

      val funderUserId2 = insertUser(type = UserType.Funder)
      insertUserDisclaimer(disclaimerId = disclaimerId1, acceptedOn = Instant.ofEpochSecond(9002))

      val funderUserId3 = insertUser(type = UserType.Funder)
      insertUserDisclaimer(disclaimerId = disclaimerId2, acceptedOn = Instant.ofEpochSecond(3002))

      insertUser(type = UserType.Funder)

      assertEquals(
          DisclaimerModel(
              id = disclaimerId1,
              content = "Disclaimer 1",
              effectiveOn = Instant.ofEpochSecond(9000),
              users =
                  mapOf(
                      funderUserId1 to Instant.ofEpochSecond(9001),
                      funderUserId2 to Instant.ofEpochSecond(9002),
                  )),
          store.fetchOneDisclaimer(disclaimerId1),
          "Disclaimer 1")

      assertEquals(
          DisclaimerModel(
              id = disclaimerId2,
              content = "Disclaimer 2",
              effectiveOn = Instant.ofEpochSecond(3000),
              users =
                  mapOf(
                      funderUserId1 to Instant.ofEpochSecond(3001),
                      funderUserId3 to Instant.ofEpochSecond(3002),
                  )),
          store.fetchOneDisclaimer(disclaimerId2),
          "Disclaimer 2")

      assertEquals(
          DisclaimerModel(
              id = disclaimerId3,
              content = "Disclaimer 3",
              effectiveOn = Instant.ofEpochSecond(6000),
              users = emptyMap()),
          store.fetchOneDisclaimer(disclaimerId3),
          "Disclaimer 3")
    }

    @Test
    fun `throws disclaimer not found exception if disclaimer does not exist`() {
      insertUserGlobalRole(currentUser().userId, GlobalRole.SuperAdmin)
      assertThrows<DisclaimerNotFoundException> { store.fetchOneDisclaimer(DisclaimerId(-1)) }
    }

    @Test
    fun `throws exception for non-super-admins`() {
      assertThrows<AccessDeniedException> { store.fetchOneDisclaimer(DisclaimerId(-1)) }
    }
  }

  @Nested
  inner class FetchCurrentDisclaimer {
    @Test
    fun `returns current disclaimer depending on time`() {
      switchToUser(funderUserId)

      assertNull(store.fetchCurrentDisclaimer(), "No disclaimer inserted yet")
      val disclaimerId1 =
          insertDisclaimer(
              content = "Disclaimer 1",
              effectiveOn = Instant.ofEpochSecond(3000),
          )
      insertUserDisclaimer(
          userId = funderUserId,
          disclaimerId = disclaimerId1,
          acceptedOn = Instant.ofEpochSecond(3001))

      val disclaimerId2 =
          insertDisclaimer(
              content = "Disclaimer 2",
              effectiveOn = Instant.ofEpochSecond(6000),
          )

      clock.instant = Instant.ofEpochSecond(2999)
      assertEquals(null, store.fetchCurrentDisclaimer(), "All disclaimers are in the future")

      clock.instant = Instant.ofEpochSecond(3000)
      assertEquals(
          UserDisclaimerModel(
              id = disclaimerId1,
              content = "Disclaimer 1",
              acceptedOn = Instant.ofEpochSecond(3001),
              effectiveOn = Instant.ofEpochSecond(3000),
          ),
          store.fetchCurrentDisclaimer(),
          "First disclaimer with accepted date")

      clock.instant = Instant.ofEpochSecond(6000)
      assertEquals(
          UserDisclaimerModel(
              id = disclaimerId2,
              content = "Disclaimer 2",
              effectiveOn = Instant.ofEpochSecond(6000),
          ),
          store.fetchCurrentDisclaimer(),
          "Second disclaimer without accepted date")
    }

    @Test
    fun `throws exception for non-funder user`() {
      assertThrows<AccessDeniedException> { store.fetchCurrentDisclaimer() }
    }
  }

  @Nested
  inner class AcceptCurrentDisclaimer {
    @Test
    fun `inserts user disclaimer record at current time`() {
      switchToUser(funderUserId)

      val existingDisclaimerId =
          insertDisclaimer(
              content = "Existing Disclaimer",
              effectiveOn = Instant.ofEpochSecond(3000),
          )
      insertUserDisclaimer(
          userId = funderUserId,
          disclaimerId = existingDisclaimerId,
          acceptedOn = Instant.ofEpochSecond(3001))

      val disclaimerId =
          insertDisclaimer(
              content = "Disclaimer",
              effectiveOn = Instant.ofEpochSecond(6000),
          )

      clock.instant = Instant.ofEpochSecond(6001)

      store.acceptCurrentDisclaimer()

      assertTableEquals(
          listOf(
              UserDisclaimersRecord(
                  userId = funderUserId,
                  disclaimerId = existingDisclaimerId,
                  acceptedOn = Instant.ofEpochSecond(3001),
              ),
              UserDisclaimersRecord(
                  userId = funderUserId,
                  disclaimerId = disclaimerId,
                  acceptedOn = Instant.ofEpochSecond(6001),
              )))
    }

    @Test
    fun `does nothing if current disclaimer has already been accepted`() {
      switchToUser(funderUserId)
      val disclaimerId =
          insertDisclaimer(
              content = "Disclaimer",
              effectiveOn = Instant.ofEpochSecond(3000),
          )
      insertUserDisclaimer(
          userId = funderUserId,
          disclaimerId = disclaimerId,
          acceptedOn = Instant.ofEpochSecond(3000))

      clock.instant = Instant.ofEpochSecond(6000)
      store.acceptCurrentDisclaimer()

      assertTableEquals(
          UserDisclaimersRecord(
              userId = funderUserId,
              disclaimerId = disclaimerId,
              acceptedOn = Instant.ofEpochSecond(3000)))
    }

    @Test
    fun `throws exception if no current disclaimer found`() {
      switchToUser(funderUserId)
      assertThrows<IllegalStateException>("No disclaimer") { store.acceptCurrentDisclaimer() }

      insertDisclaimer(
          content = "Future Disclaimer",
          effectiveOn = Instant.ofEpochSecond(3000),
      )
      clock.instant = Instant.ofEpochSecond(2999)

      assertThrows<IllegalStateException>("Only has future disclaimer") {
        store.acceptCurrentDisclaimer()
      }
    }

    @Test
    fun `throws exception for non-funder user`() {
      assertThrows<AccessDeniedException> { store.acceptCurrentDisclaimer() }
    }
  }
}
