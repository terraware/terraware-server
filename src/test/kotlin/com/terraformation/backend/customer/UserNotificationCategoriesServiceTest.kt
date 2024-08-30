package com.terraformation.backend.customer

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.db.ApplicationRecipientsStore
import com.terraformation.backend.accelerator.db.UserDeliverableCategoriesStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.UserNotificationCategory
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class UserNotificationCategoriesServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  private val clock = TestClock()

  private val applicationRecipientsStore by lazy {
    ApplicationRecipientsStore(applicationRecipientsDao, clock, dslContext)
  }

  private val userDeliverableCategoriesStore by lazy {
    UserDeliverableCategoriesStore(clock, dslContext)
  }

  private val service: UserNotificationCategoriesService by lazy {
    UserNotificationCategoriesService(applicationRecipientsStore, userDeliverableCategoriesStore)
  }

  @BeforeEach
  fun setUp() {
    every { user.canReadApplicationRecipients() } returns true
    every { user.canManageApplicationRecipients() } returns true
    every { user.canReadUserDeliverableCategories(any()) } returns true
    every { user.canUpdateUserDeliverableCategories(any()) } returns true
  }

  @Test
  fun `returns set of deliverable categories and sourcing categories with get`() {
    val user1 = insertUser() // Carbon Eligibility, Compliance
    val user2 = insertUser() // Financial Viability, Sourcing
    val user3 = insertUser() // Sourcing
    val user4 = insertUser()

    val user1DeliverableCategories =
        setOf(DeliverableCategory.CarbonEligibility, DeliverableCategory.Compliance)
    val user2DeliverableCategories = setOf(DeliverableCategory.FinancialViability)

    user1DeliverableCategories.forEach { insertUserDeliverableCategory(it, user1) }
    user2DeliverableCategories.forEach { insertUserDeliverableCategory(it, user2) }

    insertApplicationRecipient(user2)
    insertApplicationRecipient(user3)

    assertEquals(
        setOf(UserNotificationCategory.CarbonEligibility, UserNotificationCategory.Compliance),
        service.get(user1),
        "Notification categories for user 1")

    assertEquals(
        setOf(UserNotificationCategory.FinancialViability, UserNotificationCategory.Sourcing),
        service.get(user2),
        "Notification categories for user 2")

    assertEquals(
        setOf(UserNotificationCategory.Sourcing),
        service.get(user3),
        "Notification categories for user 3")

    assertEquals(
        emptySet<UserNotificationCategory>(),
        service.get(user4),
        "Notification categories for user 4")
  }

  @Test
  fun `updates deliverable categories and application recipients`() {
    // Every user will start with CarbonEligibility, Compliance and Sourcing
    val user1 = insertUser() // Adding FinancialViability
    val user2 =
        insertUser() // Adding Financial Viability and Removing CarbonEligibility and Compliance
    val user3 = insertUser() // Removing Sourcing
    val user4 = insertUser() // Not changing anything
    val user5 = insertUser() // Removing all

    val allUsers = setOf(user1, user2, user3, user4, user5)

    allUsers.forEach {
      insertUserDeliverableCategory(DeliverableCategory.CarbonEligibility, it)
      insertUserDeliverableCategory(DeliverableCategory.Compliance, it)
      insertApplicationRecipient(it)
    }

    val originalNotificationCategories =
        setOf(
            UserNotificationCategory.CarbonEligibility,
            UserNotificationCategory.Compliance,
            UserNotificationCategory.Sourcing,
        )

    val user1NotificationCategories =
        originalNotificationCategories + UserNotificationCategory.FinancialViability
    val user2NotificationCategories =
        originalNotificationCategories + UserNotificationCategory.FinancialViability -
            UserNotificationCategory.CarbonEligibility -
            UserNotificationCategory.Compliance
    val user3NotificationCategories =
        originalNotificationCategories - UserNotificationCategory.Sourcing
    val user4NotificationCategories = originalNotificationCategories
    val user5NotificationCategories = emptySet<UserNotificationCategory>()

    service.update(user1, user1NotificationCategories)
    service.update(user2, user2NotificationCategories)
    service.update(user3, user3NotificationCategories)
    service.update(user4, user4NotificationCategories)
    service.update(user5, user5NotificationCategories)

    assertUserNotificationCategories(user1, user1NotificationCategories)
    assertUserNotificationCategories(user2, user2NotificationCategories)
    assertUserNotificationCategories(user3, user3NotificationCategories)
    assertUserNotificationCategories(user4, user4NotificationCategories)
    assertUserNotificationCategories(user5, user5NotificationCategories)
  }

  // Function that checks every available notification categories, and asserts that it matches the
  // source
  private fun assertUserNotificationCategories(
      userId: UserId,
      userNotificationCategories: Set<UserNotificationCategory>
  ) {
    val deliverableCategories = userDeliverableCategoriesStore.fetchForUser(userId)

    UserNotificationCategory.entries.forEach { userNotificationCategory ->
      userNotificationCategory.toDeliverableCategory()?.let { deliverableCategory ->
        assertEquals(
            deliverableCategories.contains(deliverableCategory),
            userNotificationCategories.contains(userNotificationCategory),
            "User $userId and $userNotificationCategory matches user deliverable categories table.")
      }

      if (userNotificationCategory == UserNotificationCategory.Sourcing) {
        assertEquals(
            applicationRecipientsStore.contains(userId),
            userNotificationCategories.contains(userNotificationCategory),
            "User $userId and $userNotificationCategory matches application recipients table.")
      }
    }
  }
}
