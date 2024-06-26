package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UserDeliverableCategoriesStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()

  private val store: UserDeliverableCategoriesStore by lazy {
    UserDeliverableCategoriesStore(clock, dslContext)
  }

  @BeforeEach
  fun setUp() {
    insertUser()
  }

  @Nested
  inner class FetchForUser {
    @Test
    fun `returns deliverable categories for user`() {
      every { user.canReadUserDeliverableCategories(any()) } returns true

      // Should not return categories of other users.
      insertUserDeliverableCategory(DeliverableCategory.FinancialViability, user.userId)

      val expected = setOf(DeliverableCategory.Compliance, DeliverableCategory.GIS)

      val targetUserId = insertUser(10)
      expected.forEach { insertUserDeliverableCategory(it) }

      assertEquals(
          expected, store.fetchForUser(targetUserId), "Should be accessible via fetch method")
    }

    @Test
    fun `throws exception if no permission`() {
      val otherUserId = insertUser(10)

      assertThrows<UserNotFoundException> { store.fetchForUser(otherUserId) }
    }
  }

  @Nested
  inner class UpdateForUser {
    @Test
    fun `replaces existing categories with specified set`() {
      every { user.canReadUserDeliverableCategories(any()) } returns true
      every { user.canUpdateUserDeliverableCategories(any()) } returns true

      val targetUserId = insertUser(10)
      insertUserDeliverableCategory(DeliverableCategory.Compliance)
      insertUserDeliverableCategory(DeliverableCategory.FinancialViability)

      val otherUserId = insertUser(11)
      insertUserDeliverableCategory(DeliverableCategory.CarbonEligibility)

      store.updateForUser(
          targetUserId,
          setOf(DeliverableCategory.FinancialViability, DeliverableCategory.SupplementalFiles))

      assertEquals(
          setOf(DeliverableCategory.FinancialViability, DeliverableCategory.SupplementalFiles),
          store.fetchForUser(targetUserId),
          "Should have updated categories of target user")
      assertEquals(
          setOf(DeliverableCategory.CarbonEligibility),
          store.fetchForUser(otherUserId),
          "Should not have updated categories of other user")
    }

    @Test
    fun `throws exception if no permission`() {
      val otherUserId = insertUser(10)

      assertThrows<UserNotFoundException> { store.updateForUser(otherUserId, emptySet()) }
    }
  }
}
