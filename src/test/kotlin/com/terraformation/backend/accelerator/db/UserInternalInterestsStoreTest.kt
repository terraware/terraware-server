package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.customer.db.UserInternalInterestsStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.accelerator.InternalInterest
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UserInternalInterestsStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()

  private val store: UserInternalInterestsStore by lazy {
    UserInternalInterestsStore(clock, dslContext)
  }

  @Nested
  inner class FetchForUser {
    @Test
    fun `returns deliverable categories for user`() {
      every { user.canReadUserInternalInterests(any()) } returns true

      // Should not return categories of other users.
      insertUserInternalInterest(InternalInterest.FinancialViability, user.userId)

      val expected = setOf(InternalInterest.Compliance, InternalInterest.GIS)

      val targetUserId = insertUser()
      expected.forEach { insertUserInternalInterest(it) }

      assertSetEquals(
          expected,
          store.fetchForUser(targetUserId),
          "Should be accessible via fetch method",
      )
    }

    @Test
    fun `throws exception if no permission`() {
      val otherUserId = insertUser()

      assertThrows<UserNotFoundException> { store.fetchForUser(otherUserId) }
    }
  }

  @Nested
  inner class UpdateForUser {
    @Test
    fun `replaces existing categories with specified set`() {
      every { user.canReadUserInternalInterests(any()) } returns true
      every { user.canUpdateUserInternalInterests(any()) } returns true

      val targetUserId = insertUser()
      insertUserInternalInterest(InternalInterest.Compliance)
      insertUserInternalInterest(InternalInterest.FinancialViability)

      val otherUserId = insertUser()
      insertUserInternalInterest(InternalInterest.CarbonEligibility)

      store.updateForUser(
          targetUserId,
          setOf(InternalInterest.FinancialViability, InternalInterest.SupplementalFiles),
      )

      assertSetEquals(
          setOf(InternalInterest.FinancialViability, InternalInterest.SupplementalFiles),
          store.fetchForUser(targetUserId),
          "Should have updated categories of target user",
      )
      assertSetEquals(
          setOf(InternalInterest.CarbonEligibility),
          store.fetchForUser(otherUserId),
          "Should not have updated categories of other user",
      )
    }

    @Test
    fun `throws exception if no permission`() {
      val otherUserId = insertUser()

      assertThrows<UserNotFoundException> { store.updateForUser(otherUserId, emptySet()) }
    }
  }
}
